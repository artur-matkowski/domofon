package pl.bitforge.domofon.gate

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class GateState(val state: String, val changedAt: String)

/** A button label paired with the action it sends, so every surface agrees on both. */
data class PrimaryAction(val label: String, val action: String)

/**
 * What we know about the bridge, which is genuinely three-valued. A boolean here was the
 * VPN bug: it defaulted to "offline", and a bridge whose birth message is not retained
 * never corrects a freshly connected client — so every away-from-home session opened with
 * "unreachable" as a *default*, presented as a fact.
 */
enum class BridgeStatus {
    /** No availability message seen on this connection — includes "not connected at all". */
    UNKNOWN,

    /** The availability topic said "online", or a live state message proved it. */
    ONLINE,

    /** The availability topic said "offline" — the bridge's LWT fired. */
    OFFLINE,
}

/**
 * What the app is doing about the broker connection.
 *
 * Distinct from [BridgeStatus], which describes the *gate service*. This describes our own
 * socket, and it exists because without it every one of these renders identically on the
 * phone — "Gate: unknown", no error. A rejected password, a broker with nothing retained
 * on it, and a denied subscription ACL were one indistinguishable screen.
 */
enum class ConnectionStatus {
    /** Nothing holds the connection; we are not trying. */
    DISCONNECTED,

    /** A handshake is in flight. */
    CONNECTING,

    /** CONNACK accepted and the subscriptions were granted. */
    CONNECTED,

    /** Connected, but the broker refused a subscription — we are deaf to some or all state. */
    DEGRADED,

    /** The attempt failed; [ConnectionState.reason] says how. */
    FAILED,
}

/**
 * [ConnectionStatus] plus a line the user can act on.
 *
 * The reason is deliberately free of the host, username and password. It is rendered on the
 * phone, on the car screen and in Settings — the same reasoning that keeps the broker
 * address out of the log lines in this file applies with more force to something on screen.
 */
data class ConnectionState(val status: ConnectionStatus, val reason: String = "")

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

    /** `hc12/available`, the service's LWT — see [BridgeStatus] for why this is not a Boolean. */
    private val _bridgeStatus = MutableStateFlow(BridgeStatus.UNKNOWN)
    val bridgeStatus: StateFlow<BridgeStatus> = _bridgeStatus.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState(ConnectionStatus.DISCONNECTED))

    /**
     * Our own connection, as opposed to [bridgeStatus]'s view of the gate service. Every UI
     * surface binds this; a failure that only reaches logcat is a failure the user is left
     * to guess at, and in a release build `-assumenosideeffects` deletes most of logcat too.
     */
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

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

    /** Current backoff for [scheduleReconnect]. Reset to the floor on every successful connect. */
    private var reconnectDelayMs = INITIAL_RECONNECT_MS

    private val tsLock = Any()

    /** Newest `ts` seen across all gate signals. Guarded by [tsLock]. */
    private var newestTs: Instant? = null

    // --- lifecycle ------------------------------------------------------------------

    @Synchronized
    fun connect() {
        owners++
        Log.i(TAG, "connect(): owners=$owners")

        // A live client built from settings the user has since corrected is worse than no
        // client at all: automaticReconnect keeps retrying the *old* credentials forever,
        // so fixing a mistyped password in Settings would appear to change nothing until
        // the process happened to die. Rebuild instead of returning.
        if (client != null && active.broker != ConfigStore.current.broker) {
            Log.i(TAG, "broker settings changed — rebuilding the connection")
            teardown()
        } else if (owners > 1 || client != null) {
            return
        }

        open()
    }

    /**
     * Re-applies edited broker settings to a connection somebody is already holding.
     *
     * The settings screen holds the connection open while it is on screen, so its status row
     * is a live test of what was just typed. Without this the row would go on reporting the
     * result of whatever credentials the screen was *opened* with, which is precisely the
     * kind of stale truth this whole change exists to remove.
     */
    @Synchronized
    fun refresh() {
        if (owners == 0) return
        if (client != null && active.broker == ConfigStore.current.broker) return
        teardown()
        open()
    }

    /**
     * Queues a rebuild of the whole client after a failure, with exponential backoff.
     *
     * A rebuild rather than a retry, because that is the distinction that decides whether
     * the broker accepts us at all — see the note in [open] about `automaticReconnect`.
     *
     * [failedEpoch] is what makes this safe to over-schedule: a burst of disconnect events
     * queues several of these, and the first one to run retires the epoch, so the rest find
     * themselves stale and do nothing. A [disconnect] in the meantime does the same.
     */
    private fun scheduleReconnect(failedEpoch: Int) {
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_MS)
        scope.launch {
            delay(delayMs)
            reconnectNow(failedEpoch)
        }
    }

    @Synchronized
    private fun reconnectNow(failedEpoch: Int) {
        // Nobody wants a connection any more, or this attempt has been superseded.
        if (owners == 0 || failedEpoch != epoch) return
        Log.i(TAG, "reconnecting")
        teardown()
        open()
    }

    /** Builds and connects a client for the current settings. Callers own the [owners] count. */
    private fun open() {
        val cfg = ConfigStore.current
        if (!cfg.isComplete) {
            // Nothing to connect to. Callers still hold an owner slot, so a later
            // disconnect() balances out and the next connect() re-reads the settings.
            Log.w(TAG, "not configured — no broker host set")
            _connection.value = ConnectionState(ConnectionStatus.FAILED, "No broker configured")
            return
        }
        active = cfg
        val myEpoch = epoch
        _connection.value = ConnectionState(ConnectionStatus.CONNECTING)

        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(cfg.broker.clientId)
            .serverHost(cfg.broker.host)
            .serverPort(cfg.broker.port)
            // HiveMQ's defaults assume a wire that is either up or down. A VPN link is
            // neither: SYNs can hang for the platform default (minutes), so cap the
            // handshake.
            .transportConfig()
            .socketConnectTimeout(10, TimeUnit.SECONDS)
            .mqttConnectTimeout(10, TimeUnit.SECONDS)
            .applyTransportConfig()
            // Deliberately NOT .automaticReconnect(). Measured on device: after the network
            // is yanked out from under a live connection, every one of HiveMQ's own
            // reconnect attempts is refused by the broker with CONNACK NOT_AUTHORIZED, for
            // as long as it keeps trying — while a cold start of the app, with the *same*
            // credentials and the *same* client id, connects instantly at that same moment.
            // Whatever the reconnector re-sends is not what we handed the builder, so the
            // only trustworthy reconnect is a client built from scratch. [scheduleReconnect]
            // does that, with the backoff the built-in one was configured for.
            //
            // This was the original "the app does not connect to my broker" report: one
            // network transition and the app could never reach the broker again, silently,
            // until it was force-stopped.
            .addConnectedListener {
                if (myEpoch != epoch) return@addConnectedListener
                reconnectDelayMs = INITIAL_RECONNECT_MS
                _connection.value = ConnectionState(ConnectionStatus.CONNECTED)
                // Re-subscribe on EVERY (re)connect. The session is clean, so the broker
                // holds no subscriptions for us; without this, a reconnect leaves the app
                // looking healthy while deaf to state changes.
                subscribeAll(myEpoch)
            }
            .addDisconnectedListener { context ->
                if (myEpoch != epoch) return@addDisconnectedListener
                // UNKNOWN, not OFFLINE: losing our own connection says nothing about the
                // bridge, and claiming "unreachable" here is how the VPN bug looked real.
                _bridgeStatus.value = BridgeStatus.UNKNOWN

                // USER means our own disconnect() — that path reports DISCONNECTED itself.
                // Everything else is a failure we have to recover from ourselves now that
                // the built-in reconnector is gone.
                if (context.source != MqttDisconnectSource.USER) {
                    Log.w(TAG, "MQTT disconnected (${context.source})", context.cause)
                    _connection.value =
                        ConnectionState(ConnectionStatus.FAILED, describe(context.cause))
                    scheduleReconnect(myEpoch)
                }
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
            .keepAlive(cfg.mqtt.keepAliveSeconds)
            .send()
            .whenComplete { _, err ->
                // No host in the message: logcat is readable by adb and by crash reporters,
                // and a user's broker address is theirs, not ours to scatter around.
                if (err != null) {
                    Log.w(TAG, "MQTT connect failed", err)
                    if (myEpoch == epoch) {
                        _connection.value = ConnectionState(ConnectionStatus.FAILED, describe(err))
                    }
                }
            }
    }

    @Synchronized
    fun disconnect() {
        owners = (owners - 1).coerceAtLeast(0)
        Log.i(TAG, "disconnect(): owners=$owners")
        if (owners > 0) return

        teardown()
        _connection.value = ConnectionState(ConnectionStatus.DISCONNECTED)
    }

    /**
     * Retires the current client and everything derived from it, without touching [owners]
     * — [connect] uses this to swap in corrected settings while its callers keep holding
     * the connection, and [disconnect] uses it to let go entirely.
     */
    private fun teardown() {
        // Retire the epoch first: a client still mid-handshake will complete after this
        // returns, and its listeners must find themselves stale rather than write to flows
        // that now describe no connection at all.
        epoch++
        val old = client
        client = null
        _bridgeStatus.value = BridgeStatus.UNKNOWN

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

    /**
     * A clamped config value to the client's enum. The fallback can only trigger if a
     * caller bypasses [ConfigStore]'s clamping, but `fromCode` returns null and a crash in
     * the subscribe path would take the whole connection down with it.
     */
    private fun qos(code: Int): MqttQos = MqttQos.fromCode(code) ?: MqttQos.AT_LEAST_ONCE

    private fun subscribeAll(myEpoch: Int) {
        val c = client ?: return
        if (myEpoch != epoch) return
        val cfg = active
        val subscriptions =
            SIGNAL_TO_STATE.keys.map { cfg.topics.rxPrefix + it to qos(cfg.mqtt.qosState) } +
                (cfg.topics.availability to qos(cfg.mqtt.qosAvailability))
        subscriptions.forEach { (topic, topicQos) ->
            c.subscribeWith()
                .topicFilter(topic)
                .qos(topicQos)
                .callback(::onMessage)
                .send()
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.w(TAG, "subscribe $topic failed", err)
                        // A denied subscription is invisible otherwise: the client stays
                        // happily connected and simply never hears anything, which reads on
                        // screen as a working app attached to a silent gate.
                        if (myEpoch == epoch) {
                            _connection.value = ConnectionState(
                                ConnectionStatus.DEGRADED,
                                "Connected, but the broker refused a subscription",
                            )
                        }
                    }
                }
        }
    }

    /**
     * A failure cause turned into one line worth showing a user.
     *
     * The chain is walked because the interesting exception is usually wrapped — HiveMQ
     * reports a `ConnectionFailedException` whose cause is the socket or TLS error, and the
     * CONNACK rejection that names a bad password sits a level down too. Bounded, because a
     * self-referential cause would otherwise spin here forever.
     */
    private fun describe(cause: Throwable?): String {
        var e: Throwable = cause ?: return "Connection lost"
        repeat(8) {
            when (val current = e) {
                is Mqtt3ConnAckException -> return when (current.mqttMessage.returnCode) {
                    Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD ->
                        "Broker rejected the username or password"
                    Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> "Broker refused authorization"
                    Mqtt3ConnAckReturnCode.IDENTIFIER_REJECTED -> "Broker rejected the client id"
                    Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE -> "Broker is unavailable"
                    Mqtt3ConnAckReturnCode.UNSUPPORTED_PROTOCOL_VERSION ->
                        "Broker does not speak MQTT 3.1.1"
                    else -> "Broker refused the connection"
                }
                // The classic misconfiguration: TLS on, pointed at the plaintext port.
                is SSLException -> return "TLS handshake failed — check the TLS setting and port"
                is UnknownHostException -> return "Cannot resolve the broker address"
                is NoRouteToHostException -> return "No route to the broker"
                is ConnectException -> return "Broker refused the connection or is unreachable"
                is SocketTimeoutException -> return "Timed out reaching the broker"
            }
            // Netty's ConnectTimeoutException lives under the shaded package, and naming a
            // relocated internal class here would be a rule that silently stops matching on
            // the next HiveMQ bump. The name is the stable part.
            if (e.javaClass.simpleName.contains("Timeout")) return "Timed out reaching the broker"
            e = e.cause ?: return e.javaClass.simpleName
        }
        return cause.javaClass.simpleName
    }

    // --- inbound --------------------------------------------------------------------

    private fun onMessage(publish: Mqtt3Publish) {
        val topic = publish.topic.toString()
        val payload = String(publish.payloadAsBytes)
        val cfg = active

        if (topic == cfg.topics.availability) {
            _bridgeStatus.value = when (payload) {
                "online" -> BridgeStatus.ONLINE
                "offline" -> BridgeStatus.OFFLINE
                // Junk on the availability topic is not evidence in either direction.
                else -> BridgeStatus.UNKNOWN
            }
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

        // A live (non-retained) state message can only have been published by a running
        // bridge, so it is proof of life even when the availability topic never spoke —
        // the case where the bridge's birth message is not retained and we connected after
        // it. Retained messages prove nothing: the broker replays them for years.
        if (!publish.isRetain) _bridgeStatus.value = BridgeStatus.ONLINE

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
                // DEGRADED counts: a refused *subscription* says nothing about whether we
                // may publish, and refusing to send the command would be a worse failure
                // than trying and reporting the broker's answer.
                connection.first {
                    it.status == ConnectionStatus.CONNECTED || it.status == ConnectionStatus.DEGRADED
                }
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
                .qos(qos(cfg.mqtt.qosCommand))
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

    /** Reconnect backoff bounds — the same 1 s → 30 s the built-in reconnector used. */
    private const val INITIAL_RECONNECT_MS = 1_000L
    private const val MAX_RECONNECT_MS = 30_000L
}
