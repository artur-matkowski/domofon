package pl.bitforge.domofon.gate

import android.os.Build
import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject
import pl.bitforge.domofon.BuildConfig
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class GateState(val state: String, val changedAt: String)

/** A button label paired with the action it sends, so every surface agrees on both. */
data class PrimaryAction(val label: String, val action: String)

/**
 * Single source of truth for gate state and commands, and the only class that speaks MQTT.
 *
 * The gate is AVR node 4 on a 433 MHz HC-12 radio. `hc12-web-service` on RPI-D bridges that
 * radio to MQTT, so this app is just another broker client — it never touches the radio,
 * Postgres or HTTP. See docs/02 for the topic contract.
 */
object GateRepository {

    const val STATE_UNKNOWN = "unknown"
    private const val STATE_OPENED = "opened"

    /** The gate controller's node id on the radio. */
    private const val GATE_NODE_ID = 4

    private const val RX_PREFIX = "hc12/rx/"
    private const val TX_PREFIX = "hc12/tx/"
    private const val T_AVAILABLE = "hc12/available"

    /** Radio signal -> UI label. Also the exact set of rx topics we subscribe to. */
    private val SIGNAL_TO_STATE = mapOf(
        "GateOpened" to STATE_OPENED,
        "GateClosed" to "closed",
        "GateOpening" to "opening",
        "GateClosing" to "closing",
        "GateStopped" to "stopped",
        "GateStuckOpening" to "stuck_opening",
        "GateStuckClosing" to "stuck_closing",
    )

    private val ACTION_TO_SIGNAL = mapOf(
        "open" to "OpenGate",
        "close" to "CloseGate",
        "stop" to "StopGate",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _gateState = MutableStateFlow(GateState(STATE_UNKNOWN, ""))
    val gateState: StateFlow<GateState> = _gateState.asStateFlow()

    /** `hc12/available`, the service's LWT. False means "no idea", not "gate closed". */
    private val _bridgeOnline = MutableStateFlow(false)
    val bridgeOnline: StateFlow<Boolean> = _bridgeOnline.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var client: Mqtt3AsyncClient? = null

    /**
     * Owner count, not a boolean: the activity, the Android Auto session and the geofence
     * receiver can each hold the connection. Disconnect only when the last one lets go,
     * otherwise backgrounding the phone UI would kill the car session's feed.
     */
    private var owners = 0

    private val tsLock = Any()

    /** Newest `ts` seen across all gate signals. Guarded by [tsLock]. */
    private var newestTs: Instant? = null

    // --- lifecycle ------------------------------------------------------------------

    @Synchronized
    fun connect() {
        owners++
        Log.i(TAG, "connect(): owners=$owners")
        if (owners > 1 || client != null) return

        if (BuildConfig.MQTT_HOST.isEmpty() || BuildConfig.MQTT_PASS.isEmpty()) {
            Log.e(TAG, "no broker credentials — set mqtt.* in local.properties and rebuild")
            return
        }

        val c = MqttClient.builder()
            .useMqttVersion3()
            .identifier(clientId())
            .serverHost(BuildConfig.MQTT_HOST)
            .serverPort(BuildConfig.MQTT_PORT)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                _connected.value = true
                // Re-subscribe on EVERY (re)connect. The session is clean, so the broker
                // holds no subscriptions for us; without this, a reconnect leaves the app
                // looking healthy while deaf to state changes.
                subscribeAll()
            }
            .addDisconnectedListener {
                _connected.value = false
                _bridgeOnline.value = false
            }
            .buildAsync()
        client = c

        c.connectWith()
            .simpleAuth()
            .username(BuildConfig.MQTT_USER)
            .password(BuildConfig.MQTT_PASS.toByteArray())
            .applySimpleAuth()
            // Clean session: every rx topic is retained, so a queued backlog would only
            // replay state the broker is about to hand us anyway.
            .cleanSession(true)
            .keepAlive(60)
            .send()
            .whenComplete { _, err ->
                if (err != null) Log.w(TAG, "MQTT connect to ${BuildConfig.MQTT_HOST} failed", err)
            }
    }

    @Synchronized
    fun disconnect() {
        owners = (owners - 1).coerceAtLeast(0)
        Log.i(TAG, "disconnect(): owners=$owners")
        if (owners > 0) return
        client?.disconnect()
        client = null
        _connected.value = false
        _bridgeOnline.value = false
        // newestTs deliberately survives: the next connect redelivers the same retained
        // topics, and keeping it stops the state flickering on every reconnect.
    }

    /** Unique per device: two clients sharing an id kick each other offline in a loop. */
    private fun clientId(): String =
        "domofon-" + Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "-").take(16)

    private fun subscribeAll() {
        val c = client ?: return
        (SIGNAL_TO_STATE.keys.map { RX_PREFIX + it } + T_AVAILABLE).forEach { topic ->
            c.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback(::onMessage)
                .send()
                .whenComplete { _, err ->
                    if (err != null) Log.w(TAG, "subscribe $topic failed", err)
                }
        }
    }

    // --- inbound --------------------------------------------------------------------

    private fun onMessage(publish: Mqtt3Publish) {
        val topic = publish.topic.toString()
        val payload = String(publish.payloadAsBytes)

        if (topic == T_AVAILABLE) {
            _bridgeOnline.value = payload == "online"
            Log.i(TAG, "hc12/available -> $payload")
            return
        }

        val state = SIGNAL_TO_STATE[topic.removePrefix(RX_PREFIX)] ?: return
        val rawTs = try {
            JSONObject(payload).optString("ts")
        } catch (e: JSONException) {
            Log.w(TAG, "rx payload is not JSON: $topic", e)
            return
        }
        val ts = parseTs(rawTs) ?: run {
            Log.w(TAG, "rx $topic has unparseable ts '$rawTs'")
            return
        }

        synchronized(tsLock) {
            if (publish.isRetain) {
                // Retained rx topics are last-value-per-signal and arrive in arbitrary
                // order (observed: GateClosed 11:40:10 landed before GateOpening 11:40:05),
                // so only a strictly newer stamp may move the state.
                val current = newestTs
                if (current != null && !ts.isAfter(current)) return
            }
            // A live message is chronological by definition, so it always wins. That also
            // sidesteps the service's one-second ts resolution.
            newestTs = ts
        }

        Log.i(TAG, "state -> $state (ts=$rawTs, retained=${publish.isRetain})")
        _gateState.value = GateState(state, rawTs)
    }

    private fun parseTs(raw: String): Instant? {
        if (raw.isEmpty()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(raw)
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    // --- outbound -------------------------------------------------------------------

    /**
     * The single home of the button rule, so the car screen and the notification can never
     * disagree: anything that is not open offers Open.
     */
    fun primaryAction(state: String): PrimaryAction =
        if (state == STATE_OPENED) PrimaryAction("Close gate", "close")
        else PrimaryAction("Open gate", "open")

    /** Fire-and-forget, for UI callers that already hold the connection. */
    fun sendCommand(action: String) {
        scope.launch { sendCommandAwait(action) }
    }

    /**
     * Connects if needed, publishes, and waits for the broker ack. The notification action
     * uses this: it fires with the app closed and must not return before the send lands.
     */
    suspend fun sendCommandAwait(action: String, timeoutMs: Long = 8_000): Boolean {
        val signal = ACTION_TO_SIGNAL[action] ?: run {
            Log.w(TAG, "unknown action: $action")
            return false
        }

        connect()
        try {
            val c = withTimeoutOrNull(timeoutMs) {
                connected.first { it }
                client
            }
            if (c == null) {
                Log.w(TAG, "sendCommand($action): not connected within ${timeoutMs}ms")
                return false
            }

            val payload = JSONObject().put("idTarget", GATE_NODE_ID).toString()
            val acked = CompletableDeferred<Boolean>()
            c.publishWith()
                .topic(TX_PREFIX + signal)
                .qos(MqttQos.AT_LEAST_ONCE)
                // NOT retained. hc12-web-service drops retained tx outright (its replay
                // guard), so a retained command would be silently ignored — and without
                // that guard it would re-key the transmitter on every service restart.
                .retain(false)
                .payload(payload.toByteArray())
                .send()
                .whenComplete { _, err ->
                    if (err != null) Log.w(TAG, "publish $signal failed", err)
                    else Log.i(TAG, "tx -> $signal idTarget=$GATE_NODE_ID")
                    acked.complete(err == null)
                }

            return withTimeoutOrNull(timeoutMs) { acked.await() } ?: false
        } finally {
            disconnect()
        }
    }

    /**
     * Suspends until a real state arrives, or gives up. Used by the geofence pop-up.
     *
     * The settle delay is not padding. Retained topics arrive as a burst in arbitrary
     * order, so the first one to land is not the newest — returning on it produced a
     * pop-up reading "opening" when the gate had been closed for two minutes. Waiting for
     * the burst to finish lets the max-ts rule in [onMessage] pick the real winner.
     */
    suspend fun awaitState(timeoutMs: Long, settleMs: Long = 750): GateState? =
        withTimeoutOrNull(timeoutMs) {
            gateState.first { it.state != STATE_UNKNOWN }
            delay(settleMs)
            gateState.value
        }

    private const val TAG = "Domofon"
}
