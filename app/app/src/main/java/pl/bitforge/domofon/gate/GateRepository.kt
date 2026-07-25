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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.bitforge.domofon.config.ConfigStore
import pl.bitforge.domofon.config.DomofonConfig
import pl.bitforge.domofon.domain.BridgeStatus
import pl.bitforge.domofon.domain.ConnectionState
import pl.bitforge.domofon.domain.ConnectionStatus
import pl.bitforge.domofon.domain.GateEvent
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.domain.GateProtocol
import pl.bitforge.domofon.domain.GateState
import pl.bitforge.domofon.domain.GateStateReducer
import pl.bitforge.domofon.domain.PrimaryAction
import pl.bitforge.domofon.domain.ReconnectPolicy
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Single source of truth for gate state and commands, and the only class that speaks MQTT.
 *
 * The reference deployment is an AVR node on a 433 MHz HC-12 radio, bridged to MQTT by
 * `hc12-web-service`, so this app is just another broker client — it never touches the
 * radio, Postgres or HTTP. Which broker, which topics and which node is entirely up to
 * [ConfigStore]; see docs/02 for the topic contract.
 *
 * The wire vocabulary lives in [GateProtocol], the staleness rules in [GateStateReducer],
 * the shared button/wording policy in [GatePolicy] — this object owns the client, the
 * connection state machine and the owner counting.
 */
object GateRepository {

    const val STATE_UNKNOWN = GatePolicy.STATE_UNKNOWN

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

    private val _lastError = MutableStateFlow("")

    /**
     * The most recent reason a command did not reach the gate, or "" for none.
     *
     * Two sources, one line on screen: the bridge's own rejection from the error topic, and
     * our failure to publish at all. Neither used to be visible anywhere — a command the
     * broker acked and the bridge then threw away looked exactly like one that opened the
     * gate, which is most of why "the buttons do nothing" took a session to pin down.
     */
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    /** Retires [_lastError] after [ERROR_TTL_MS]; replaced whenever a newer error lands. */
    private var errorExpiry: Job? = null

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
     * prefixes we actually subscribed with, not the half-typed new ones. [protocol] is
     * built from this at the same moment, for the same reason.
     */
    @Volatile
    private var active: DomofonConfig = DomofonConfig.EMPTY

    /** The wire vocabulary pinned with [active]. Null exactly while no client exists. */
    @Volatile
    private var protocol: GateProtocol? = null

    /** Per-connection staleness rules; reset in [teardown] with the rest of the connection. */
    private val reducer = GateStateReducer()

    /**
     * Owner count, not a boolean: the activity, the Android Auto session and the geofence
     * receiver can each hold the connection. Disconnect only when the last one lets go,
     * otherwise backgrounding the phone UI would kill the car session's feed.
     */
    private var owners = 0

    /** Backoff schedule for [scheduleReconnect]. Reset to the floor on every successful connect. */
    private val reconnect = ReconnectPolicy()

    /** Runs [WATCHDOG_PERIOD_MS] while anybody holds the connection. See [startWatchdog]. */
    private var watchdog: Job? = null

    // --- lifecycle ------------------------------------------------------------------

    @Synchronized
    fun connect() {
        owners++
        Log.i(TAG, "connect(): owners=$owners, client=${client != null}")

        if (client != null) {
            // A live client built from settings the user has since corrected is worse than
            // no client at all: it would go on retrying the *old* credentials, so fixing a
            // mistyped password in Settings would appear to change nothing until the
            // process happened to die. Rebuild instead of returning.
            if (active.wire != ConfigStore.current.wire) {
                Log.i(TAG, "broker settings changed — rebuilding the connection")
                teardown()
            } else {
                return
            }
        }

        // No client, so open one — whatever the owner count says.
        //
        // This used to read `else if (owners > 1 || client != null) return`, and the
        // `owners > 1` half of that was a silent, permanent kill switch. It was harmless
        // only while `client` could not be null with owners still held, which stopped being
        // true when [teardown] gained callers that retire the client *without* touching the
        // count ([refresh], the rebuild above, [reconnectNow]). Reach that state once — a
        // settings edit that lands on an incomplete config, or an owner slot leaked by a
        // car session the host tore down — and every later connect() returned right here:
        // no socket, no retry, no error on screen, until the process was force-stopped.
        // Measured on device: the app sat in the foreground for over an hour holding zero
        // sockets to port 1883 while the broker had fresh retained state waiting for it.
        open()
        startWatchdog()
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
        if (client != null && active.wire == ConfigStore.current.wire) return
        teardown()
        open()
    }

    /**
     * The invariant this class kept breaking: *somebody holds the connection, therefore a
     * client exists*.
     *
     * Everything else here recovers from a connection that failed. Nothing recovered from a
     * connection that was never attempted, and that is the state the app actually shipped
     * in — foreground, owners held, no socket, no retry, no message, until it was
     * force-stopped. It arose from the owner count being wrong in either direction, and the
     * call sites that could get it wrong have both been fixed; this is here because the
     * count is reachable from four components and the next mistake would be just as silent.
     *
     * Deliberately narrow: it only acts when there is **no client at all**. A client that
     * exists and is failing belongs to [scheduleReconnect] and its backoff, and racing that
     * would turn a broker outage into a reconnect storm.
     */
    private fun startWatchdog() {
        // Called from connect(), which holds the monitor — and [ensureOpen] clears this
        // field under that same monitor as it exits. Testing `watchdog?.isActive` instead
        // would leave a window where the loop has decided to stop but the job is not marked
        // complete yet: a connect() landing there would skip starting a replacement and the
        // invariant would go unwatched for the rest of the session.
        if (watchdog != null) return
        watchdog = scope.launch {
            while (true) {
                delay(WATCHDOG_PERIOD_MS)
                if (!ensureOpen()) return@launch
            }
        }
    }

    /** One watchdog tick. Returns false when nobody wants a connection any more. */
    @Synchronized
    private fun ensureOpen(): Boolean {
        if (owners == 0) {
            // Retire the field before the loop exits, so the next connect() starts a fresh
            // watchdog rather than trusting this one.
            watchdog = null
            return false
        }
        if (client != null) return true
        Log.w(TAG, "watchdog: $owners owner(s) but no client — opening")
        open()
        return true
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
        val delayMs = reconnect.nextDelayMs()
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
        protocol = GateProtocol(cfg.topics)
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
                reconnect.reset()
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
        protocol = null
        _bridgeStatus.value = BridgeStatus.UNKNOWN

        // A retired client must not leave CONNECTED behind. Every caller either opens a
        // fresh one immediately (which reports CONNECTING over this) or is letting go
        // entirely, so the only thing this can overwrite is a status that has outlived its
        // socket — and that lie is load-bearing: sendCommandAwait() waits on this very flow
        // before publishing, and the settings screen presents it as a live credential test.
        _connection.value = ConnectionState(ConnectionStatus.DISCONNECTED)

        // The gate state is only meaningful while we are attached to the broker. Keeping
        // it would let awaitFreshState() return this value instantly on the next connect —
        // the arrival pop-up would confidently announce whatever the gate was doing hours
        // ago, without a single byte having crossed the network.
        reducer.reset()
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

    /**
     * Publishes a reason the user can act on, and starts its expiry.
     *
     * Time-limited on purpose: an error is about the command just attempted, and one left on
     * screen indefinitely would be read as the state of the *next* one.
     */
    private fun reportError(reason: String) {
        Log.w(TAG, "command error: $reason")
        _lastError.value = reason
        errorExpiry?.cancel()
        errorExpiry = scope.launch {
            delay(ERROR_TTL_MS)
            _lastError.compareAndSet(reason, "")
        }
    }

    private fun subscribeAll(myEpoch: Int) {
        val c = client ?: return
        if (myEpoch != epoch) return
        val proto = protocol ?: return
        proto.subscriptions(active.mqtt).forEach { sub ->
            c.subscribeWith()
                .topicFilter(sub.topic)
                .qos(qos(sub.qos))
                .callback(::onMessage)
                .send()
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.w(TAG, "subscribe ${sub.topic} failed", err)
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
        val proto = protocol ?: return
        val event = proto.decode(
            topic = publish.topic.toString(),
            payload = String(publish.payloadAsBytes),
            retained = publish.isRetain,
        )
        when (event) {
            is GateEvent.Availability -> {
                _bridgeStatus.value = event.status
                Log.i(TAG, "availability -> ${event.status}")
            }

            is GateEvent.Signal -> {
                // A live (non-retained) state message can only have been published by a
                // running bridge, so it is proof of life even when the availability topic
                // never spoke — the case where the bridge's birth message is not retained
                // and we connected after it. Retained messages prove nothing: the broker
                // replays them for years.
                if (!event.retained) _bridgeStatus.value = BridgeStatus.ONLINE

                val next = reducer.reduce(event) ?: return
                _gateState.value = next
                Log.i(TAG, "state -> ${next.state} (retained=${event.retained})")
            }

            is GateEvent.BridgeError ->
                reportError("Gate service refused the command: ${event.reason}")

            is GateEvent.Ignored -> Log.w(TAG, "rx ignored: ${event.why}")
        }
    }

    // --- outbound -------------------------------------------------------------------

    /** The shared button rule — see [GatePolicy.primaryAction]. */
    fun primaryAction(state: String): PrimaryAction = GatePolicy.primaryAction(state)

    /** Fire-and-forget, for UI callers that already hold the connection. */
    fun sendCommand(action: String) {
        scope.launch { sendCommandAwait(action) }
    }

    /**
     * Connects if needed, publishes, and waits for the broker ack. The notification action
     * uses this: it fires with the app closed and must not return before the send lands.
     */
    suspend fun sendCommandAwait(action: String, timeoutMs: Long = 8_000): Boolean {
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
                // The button's whole feedback channel. Without this a command that never
                // left the phone is silent, and silence is what the user reads as "the app
                // sent it and the gate ignored it" — the wrong half of the system to go
                // looking in, as this bug's history shows.
                reportError("Command not sent — no connection to the broker")
                return false
            }

            val cfg = active
            val command = protocol?.encodeCommand(action, cfg.topics.nodeId, cfg.topics.payloadKey)
            if (command == null) {
                Log.w(TAG, "unknown action: $action")
                return false
            }
            val acked = CompletableDeferred<Boolean>()
            c.publishWith()
                .topic(command.topic)
                .qos(qos(cfg.mqtt.qosCommand))
                // NOT retained — see [GateProtocol.encodeCommand] for why a retained
                // command is at best silently ignored.
                .retain(false)
                .payload(command.payload.toByteArray())
                .send()
                .whenComplete { _, err ->
                    // The resolved topic, not just the signal name: a wrong prefix is the
                    // one failure this line can prove or rule out at a glance, and it
                    // carries no host and no credentials.
                    if (err != null) Log.w(TAG, "publish ${command.topic} failed", err)
                    else Log.i(TAG, "tx -> ${command.topic}")
                    acked.complete(err == null)
                }

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            val sent = withTimeoutOrNull(remaining) { acked.await() } ?: false
            if (!sent) reportError("Command not sent — the broker did not accept it")
            return sent
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
     * the burst to finish lets the max-ts rule in [GateStateReducer] pick the real winner.
     */
    suspend fun awaitFreshState(timeoutMs: Long, settleMs: Long = 750): GateState? =
        withTimeoutOrNull(timeoutMs) {
            gateState.first { it.state != STATE_UNKNOWN }
            delay(settleMs)
            gateState.value
        }

    private const val TAG = "Domofon"

    /**
     * How long a command error stays on screen.
     *
     * Deliberately not cleared by [teardown]: a command sent with the app closed reports its
     * failure and then immediately releases the connection, so tearing the message down with
     * the client would erase it before anything could render it.
     */
    private const val ERROR_TTL_MS = 20_000L

    /**
     * How often [startWatchdog] re-checks the owners-imply-a-client invariant. Long enough
     * to be free, short enough that a user who taps Open twice has a connection by the
     * second tap.
     */
    private const val WATCHDOG_PERIOD_MS = 15_000L
}
