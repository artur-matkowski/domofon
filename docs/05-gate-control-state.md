# 05 — Gate control + live state in the app (milestone M4)

Goal: the phone UI shows the real gate state live and the buttons actually drive the
gate — all through the MQTT contract from ch. 02.

## 1. MQTT client library

Use the **HiveMQ MQTT Client** (`com.hivemq:hivemq-mqtt-client`) — actively maintained,
MQTT 3.1.1/5, built-in automatic reconnect. (Avoid the old Paho *Android Service*
artifact — unmaintained and broken by modern Android background rules.)

```kotlin
dependencies {
    implementation("com.hivemq:hivemq-mqtt-client:1.3.5") // check latest 1.3.x
}
```

## 2. GateRepository — the single MQTT owner

`gate/GateRepository.kt` (the only class in the app that speaks MQTT):

```kotlin
data class GateState(val state: String, val changedAt: String)

object GateRepository {
    private val _gateState = MutableStateFlow(GateState("unknown", ""))
    val gateState: StateFlow<GateState> = _gateState

    private val _bridgeOnline = MutableStateFlow(false)
    val bridgeOnline: StateFlow<Boolean> = _bridgeOnline

    private var client: Mqtt3AsyncClient? = null

    fun connect(cfg: MqttConfig) {
        if (client != null) return
        client = MqttClient.builder()
            .useMqttVersion3()
            .identifier("domofon-app-${Build.MODEL}")
            .serverHost(cfg.host).serverPort(cfg.port)
            .automaticReconnectWithDefaultConfig()
            .buildAsync()
            .also { c ->
                c.connectWith()
                    .simpleAuth().username(cfg.user)
                        .password(cfg.pass.toByteArray()).applySimpleAuth()
                    .cleanSession(false)
                    .send().whenComplete { _, err ->
                        if (err != null) Log.w("Domofon", "MQTT connect failed", err)
                        else subscribe(c)
                    }
            }
    }

    private fun subscribe(c: Mqtt3AsyncClient) {
        c.subscribeWith().topicFilter("domofon/gate/state").qos(MqttQos.AT_LEAST_ONCE)
            .callback { msg ->
                val json = JSONObject(String(msg.payloadAsBytes))
                _gateState.value = GateState(
                    json.optString("state", "unknown"), json.optString("changed_at"))
            }.send()
        c.subscribeWith().topicFilter("domofon/bridge/status").qos(MqttQos.AT_LEAST_ONCE)
            .callback { msg ->
                _bridgeOnline.value = String(msg.payloadAsBytes) == "online"
            }.send()
    }

    fun sendCommand(action: String) {
        val payload = JSONObject()
            .put("action", action)
            .put("request_id", UUID.randomUUID().toString().take(8))
            .put("ts", Instant.now().toString())
            .toString()
        client?.publishWith()?.topic("domofon/gate/command")
            ?.qos(MqttQos.AT_LEAST_ONCE)?.payload(payload.toByteArray())?.send()
    }

    fun disconnect() { client?.disconnect(); client = null }
}
```

Because `domofon/gate/state` is **retained**, `_gateState` gets the real value within
milliseconds of connecting — the UI never shows "unknown" for long.

Broker host/port/user/pass go in the same settings store as the RTSP URL (ch. 04).

## 3. Wire it to QML

In `MainActivity` (QML ready callback from ch. 03):

```kotlin
// Kotlin → QML: state flows into the property
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { GateRepository.gateState.collect {
            mainQml.setProperty("gateState", it.state) } }
        launch { GateRepository.bridgeOnline.collect {
            mainQml.setProperty("bridgeOnline", it) } }
    }
}

// QML → Kotlin: button presses become MQTT commands
mainQml.connectSignalListener("commandRequested", String::class.java)
{ _, action -> GateRepository.sendCommand(action) }

// Connect while the UI is visible (background strategy comes in ch. 06):
override fun onStart() { super.onStart(); GateRepository.connect(settings.mqtt) }
override fun onStop()  { super.onStop();  GateRepository.disconnect() }
```

QML side — add to the root object:

```qml
property bool bridgeOnline: false
// visual state, e.g.:
//  - banner "Gate system unreachable" when !bridgeOnline
//  - disable buttons while !bridgeOnline
//  - color/icon per gateState: opened/opening/closing/closed
```

UX detail worth doing now: when the user taps *Open*, show a transient "sending…" state
until `gateState` actually changes — that state change (from the bridge, via Postgres)
is the *confirmation*, not the button press.

## Acceptance test — milestone M4

Phone on mobile data + VPN:

1. App shows the current gate state on launch (retained message).
2. Trigger the real gate (or `psql UPDATE` like in ch. 02) → the label updates within
   ~1–2 s without touching the app.
3. Tap *Open* in the app → gate REST API fires (watch `journalctl -u domofon-bridge -f`)
   → state label follows.
4. Stop the bridge service → app shows "unreachable" (LWT). Start it → recovers alone.
5. Toggle airplane mode for 30 s → app reconnects and shows correct state by itself.

✅ **M4 passes when all five hold.** This is the app's core loop — everything after
this chapter reuses `GateRepository` unchanged.
