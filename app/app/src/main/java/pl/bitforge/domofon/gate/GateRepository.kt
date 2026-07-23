package pl.bitforge.domofon.gate

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
import pl.bitforge.domofon.config.ConfigStore
import pl.bitforge.domofon.config.DomofonConfig
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class GateState(val state: String, val changedAt: String)

/** A button label paired with the action it sends, so every surface agrees on both. */
data class PrimaryAction(val label: String, val action: String)

/**
 * Single source of truth for gate state and commands, and the only class that speaks MQTT.
 *
 * The reference deployment is an AVR node on a 433 MHz HC-12 radio, bridged to MQTT by
 * `hc12-web-service`, so this app is just another broker client — it never touches the
 * radio, Postgres or HTTP. Which broker, which topics and which node is entirely up to
 * [ConfigStore]; see docs/02 for the topic contract.
 */
object GateRepository {

    const val STATE_UNKNOWN = "unknown"
    private const val STATE_OPENED = "opened"

    /**
     * Signal name -> UI label. Deliberately *not* user-configurable: these are the
     * protocol's vocabulary rather than a property of one deployment, and exposing seven
     * more text fields would make the settings screen hostile for no practical gain. The
     * topic prefixes around them are configurable, which is what actually varies.
     */
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

    @Volatile
    private var client: Mqtt3AsyncClient? = null

    /**
     * Bumped on every [connect]/[disconnect]. A client whose handshake completes *after* we
     * gave up on it must not write to the shared flows — otherwise a slow VPN connect that
     * timed out at 6 s comes back at 7 s, sets `connected = true` against a null client, and
     * the app reports a healthy connection while silently dropping every gate command.
     */
    private var epoch = 0

    /**
     * The configuration the *current* connection was built from, pinned at [connect].
     *
     * Not read live from [ConfigStore]: the user can edit the topic prefixes while a
     * connection is open, and a message arriving mid-edit must be matched against the
     * prefixes we actually subscribed with, not the half-typed new ones.
     */
    @Volatile
    private var active: DomofonConfig = DomofonConfig.EMPTY

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

        val cfg = ConfigStore.current
        if (!cfg.isComplete) {
            // Nothing to connect to. Callers still hold an owner slot, so a later
            // disconnect() balances out and the next connect() re-reads the settings.
            Log.w(TAG, "not configured — no broker host set")
            return
        }
        active = cfg
        val myEpoch = epoch

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(cfg.broker.clientId)
            .serverHost(cfg.broker.host)
            .serverPort(cfg.broker.port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener {
                if (myEpoch != epoch) return@addConnectedListener
                _connected.value = true
                // Re-subscribe on EVERY (re)connect. The session is clean, so the broker
                // holds no subscriptions for us; without this, a reconnect leaves the app
                // looking healthy while deaf to state changes.
                subscribeAll(myEpoch)
            }
            .addDisconnectedListener {
                if (myEpoch != epoch) return@addDisconnectedListener
                _connected.value = false
                _bridgeOnline.value = false
            }

        // TLS is the user's choice because a broker reachable only inside their own VPN is
        // a legitimate setup, but it is the one they should be making deliberately — the
        // settings screen spells out what "off" costs.
        if (cfg.broker.tls) builder.sslWithDefaultConfig()

        val c = builder.buildAsync()
        client = c

        val connect = c.connectWith()
        val authenticated =
            if (cfg.broker.username.isNotEmpty() || cfg.broker.password.isNotEmpty()) {
                connect.simpleAuth()
                    .username(cfg.broker.username)
                    .password(cfg.broker.password.toByteArray())
                    .applySimpleAuth()
            } else {
                // Anonymous brokers are a normal local setup; sending an empty simpleAuth
                // block instead makes them reject the connection.
                connect
            }

        authenticated
            // Clean session: every rx topic is retained, so a queued backlog would only
            // replay state the broker is about to hand us anyway.
            .cleanSession(true)
            .keepAlive(60)
            .send()
            .whenComplete { _, err ->
                // No host in the message: logcat is readable by adb and by crash reporters,
                // and a user's broker address is theirs, not ours to scatter around.
                if (err != null) Log.w(TAG, "MQTT connect failed", err)
            }
    }

    @Synchronized
    fun disconnect() {
        owners = (owners - 1).coerceAtLeast(0)
        Log.i(TAG, "disconnect(): owners=$owners")
        if (owners > 0) return

        // Retire the epoch first: a client still mid-handshake will complete after this
        // returns, and its listeners must find themselves stale rather than write to flows
        // that now describe no connection at all.
        epoch++
        val old = client
        client = null
        _connected.value = false
        _bridgeOnline.value = false

        // The gate state is only meaningful while we are attached to the broker. Keeping
        // it would let awaitFreshState() return this value instantly on the next connect —
        // the arrival pop-up would confidently announce whatever the gate was doing hours
        // ago, without a single byte having crossed the network.
        synchronized(tsLock) { newestTs = null }
        _gateState.value = GateState(STATE_UNKNOWN, "")

        old?.disconnect()?.whenComplete { _, err ->
            if (err != null) Log.i(TAG, "disconnect completed with error", err)
        }
    }

    private fun subscribeAll(myEpoch: Int) {
        val c = client ?: return
        if (myEpoch != epoch) return
        val cfg = active
        (SIGNAL_TO_STATE.keys.map { cfg.topics.rxPrefix + it } + cfg.topics.availability).forEach { topic ->
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
        val cfg = active

        if (topic == cfg.topics.availability) {
            _bridgeOnline.value = payload == "online"
            Log.i(TAG, "availability -> $payload")
            return
        }

        val state = SIGNAL_TO_STATE[topic.removePrefix(cfg.topics.rxPrefix)] ?: return
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

        // A stamp from the future is either clock skew on the bridge or someone publishing
        // junk to a broker we may be reaching over plaintext. Accepting one would be
        // permanent: it becomes `newestTs`, and from then on every genuine message — live
        // or retained — looks older and gets dropped, so the app freezes on a stale state
        // for the rest of the process lifetime while looking perfectly healthy.
        if (ts.isAfter(Instant.now().plusSeconds(FUTURE_TS_TOLERANCE_S))) {
            Log.w(TAG, "rx $topic has a ts too far in the future — ignored")
            return
        }

        synchronized(tsLock) {
            val current = newestTs
            if (current != null) {
                // Retained rx topics are last-value-per-signal and arrive in arbitrary
                // order (observed: GateClosed 11:40:10 landed before GateOpening 11:40:05),
                // so only a strictly newer stamp may move the state.
                //
                // A live message wins ties instead of losing them — that is what lets it
                // override a retained value carrying the same one-second stamp. It still
                // may not move the state *backwards*, which the old code allowed and which
                // is how one skewed publish used to stick permanently.
                val stale = if (publish.isRetain) !ts.isAfter(current) else ts.isBefore(current)
                if (stale) return
            }
            newestTs = ts

            // Inside the lock: the guard and the write have to be one step, or two threads
            // can pass the check in order and then publish out of order. Harmless today
            // (one Netty event loop serialises this callback), a real bug the moment a
            // subscription is given its own executor.
            _gateState.value = GateState(state, rawTs)
        }

        Log.i(TAG, "state -> $state (retained=${publish.isRetain})")
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
            // One budget for the whole operation, not one per stage. Two sequential 8 s
            // waits could burn 16 s inside a BroadcastReceiver that has ~10 s of goAsync.
            val deadline = System.currentTimeMillis() + timeoutMs
            val c = withTimeoutOrNull(timeoutMs) {
                connected.first { it }
                client
            }
            if (c == null) {
                Log.w(TAG, "sendCommand($action): not connected within ${timeoutMs}ms")
                return false
            }

            val cfg = active
            val payload = JSONObject().put(cfg.topics.payloadKey, cfg.topics.nodeId).toString()
            val acked = CompletableDeferred<Boolean>()
            c.publishWith()
                .topic(cfg.topics.txPrefix + signal)
                .qos(MqttQos.AT_LEAST_ONCE)
                // NOT retained. hc12-web-service drops retained tx outright (its replay
                // guard), so a retained command would be silently ignored — and without
                // that guard it would re-key the transmitter on every service restart.
                .retain(false)
                .payload(payload.toByteArray())
                .send()
                .whenComplete { _, err ->
                    if (err != null) Log.w(TAG, "publish $signal failed", err)
                    else Log.i(TAG, "tx -> $signal")
                    acked.complete(err == null)
                }

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            return withTimeoutOrNull(remaining) { acked.await() } ?: false
        } finally {
            disconnect()
        }
    }

    /**
     * Suspends until state arrives *from the broker on this connection*, or gives up.
     * Used by the geofence pop-up.
     *
     * "Fresh" is the whole point, and it is why [disconnect] resets `_gateState`. When the
     * flow kept its last value across disconnects, this returned instantly from memory: the
     * arrival pop-up would announce "gate: closed" with total confidence, having reached
     * neither the VPN nor the broker, because that is what the gate had been doing when the
     * app was last open — possibly that morning. The timeout was never even consumed.
     *
     * The settle delay is not padding either. Retained topics arrive as a burst in
     * arbitrary order, so the first to land is not the newest — returning on it produced a
     * pop-up reading "opening" when the gate had been closed for two minutes. Waiting for
     * the burst to finish lets the max-ts rule in [onMessage] pick the real winner.
     */
    suspend fun awaitFreshState(timeoutMs: Long, settleMs: Long = 750): GateState? =
        withTimeoutOrNull(timeoutMs) {
            gateState.first { it.state != STATE_UNKNOWN }
            delay(settleMs)
            gateState.value
        }

    private const val TAG = "Domofon"

    /**
     * How far ahead of our own clock a bridge timestamp may sit before we disbelieve it.
     * Generous, because the phone and the bridge are both plausibly a little off.
     */
    private const val FUTURE_TS_TOLERANCE_S = 300L
}
