package pl.bitforge.domofon.data.mqtt

import android.util.Log
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
import pl.bitforge.domofon.domain.ReconnectPolicy

/**
 * Single source of truth for gate state and commands — the only class that decides when an
 * MQTT connection exists. The wire itself is behind [MqttTransport]; the vocabulary is
 * [GateProtocol]; the staleness rules are [GateStateReducer]; this class owns the
 * connection lifecycle, the shared state flows, and the lease accounting.
 *
 * The reference deployment is an AVR node on a 433 MHz HC-12 radio, bridged to MQTT by
 * `hc12-web-service`, so this app is just another broker client — it never touches the
 * radio, Postgres or HTTP. Which broker, which topics and which node is entirely up to the
 * injected config.
 *
 * **Ownership is a lease, not a count.** The phone UI, the settings screen, a car session
 * and the arrival pop-up each [acquire] a [ConnectionLease] and close it when done; the
 * connection opens with the first lease and tears down with the last. The previous integer
 * counter drifted twice in production (a double release tore down a connection in use; a
 * leaked slot kept a dead one "owned" forever) — a lease closes idempotently and names its
 * holder.
 *
 * **Handle identity is the staleness token.** Every transport callback carries the
 * [MqttTransport.Handle] it belongs to; anything not from [handle] is a retired
 * connection completing into silence. This is the old epoch counter with the counter
 * deleted: retiring the field *is* bumping the epoch.
 */
class GateService(
    private val transport: MqttTransport,
    /** Snapshot of the current settings; a seam so tests never touch [ConfigStore]. */
    private val currentConfig: () -> DomofonConfig,
    private val scope: CoroutineScope,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {

    private val _gateState = MutableStateFlow(GateState(GatePolicy.STATE_UNKNOWN, ""))
    val gateState: StateFlow<GateState> = _gateState.asStateFlow()

    /** The service's LWT topic — see [BridgeStatus] for why this is not a Boolean. */
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

    /** Everyone currently holding the connection open, by lease. Guarded by this monitor. */
    private val leases = mutableSetOf<ConnectionLease>()

    /** The current connection, or null while none exists. Retire it before replacing it. */
    @Volatile
    private var handle: MqttTransport.Handle? = null

    /**
     * The configuration the *current* connection was built from, pinned at open.
     *
     * Not read live: the user can edit the topic prefixes while a connection is open, and a
     * message arriving mid-edit must be matched against the prefixes we actually subscribed
     * with, not the half-typed new ones. [protocol] is built from this at the same moment,
     * for the same reason.
     */
    @Volatile
    private var active: DomofonConfig = DomofonConfig.EMPTY

    /** The wire vocabulary pinned with [active]. Null exactly while no connection exists. */
    @Volatile
    private var protocol: GateProtocol? = null

    /** Per-connection staleness rules; reset in [teardown] with the rest of the connection. */
    private val reducer = GateStateReducer()

    /** Runs every [WATCHDOG_PERIOD_MS] while anybody holds a lease. See [startWatchdog]. */
    private var watchdog: Job? = null

    // --- lifecycle ------------------------------------------------------------------

    /**
     * Claims the connection. Opens it on the first lease; a later lease with edited broker
     * settings rebuilds it, because a live client built from settings the user has since
     * corrected is worse than no client at all — it would go on retrying the *old*
     * credentials, so fixing a mistyped password in Settings would appear to change nothing
     * until the process happened to die.
     *
     * The connection opens whatever the lease count says whenever no handle exists. Its
     * predecessor gated this on `owners > 1` and that was a silent, permanent kill switch:
     * teardown paths that retire the client without touching the count left the app
     * foreground, owned, and socketless for over an hour — no retry, no error on screen.
     */
    @Synchronized
    fun acquire(tag: String): ConnectionLease {
        val lease = ConnectionLease(tag, ::release)
        leases += lease
        Log.i(TAG, "acquire($tag): leases=${leases.map { it.tag }}, handle=${handle != null}")

        if (handle != null && active.wire != currentConfig().wire) {
            Log.i(TAG, "broker settings changed — rebuilding the connection")
            teardown()
        }
        if (handle == null) open()
        startWatchdog()
        return lease
    }

    @Synchronized
    private fun release(lease: ConnectionLease) {
        leases -= lease
        Log.i(TAG, "release(${lease.tag}): leases=${leases.map { it.tag }}")
        if (leases.isEmpty()) teardown()
    }

    /**
     * Re-applies edited broker settings to a connection somebody is already holding.
     *
     * The settings screen holds a lease while it is on screen, so its status row is a live
     * test of what was just typed. Without this the row would go on reporting the result of
     * whatever credentials the screen was *opened* with, which is precisely the kind of
     * stale truth this whole change exists to remove.
     */
    @Synchronized
    fun refresh() {
        if (leases.isEmpty()) return
        if (handle != null && active.wire == currentConfig().wire) return
        teardown()
        open()
    }

    /**
     * The invariant this class's predecessor kept breaking: *somebody holds the connection,
     * therefore a handle exists*.
     *
     * Everything else here recovers from a connection that failed. Nothing recovered from a
     * connection that was never attempted — foreground, leases held, no socket, no retry,
     * no message, until the app was force-stopped. Leases fixed the accounting mistakes
     * that caused it; this stays because the next mistake would be just as silent.
     *
     * Deliberately narrow: it only acts when there is **no handle at all**. A connection
     * that exists and is failing belongs to [scheduleReconnect] and its backoff, and racing
     * that would turn a broker outage into a reconnect storm.
     */
    private fun startWatchdog() {
        // Called from acquire(), which holds the monitor — and [ensureOpen] clears this
        // field under that same monitor as it exits. Testing `watchdog?.isActive` instead
        // would leave a window where the loop has decided to stop but the job is not marked
        // complete yet: an acquire() landing there would skip starting a replacement and
        // the invariant would go unwatched for the rest of the session.
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
        if (leases.isEmpty()) {
            // Retire the field before the loop exits, so the next acquire() starts a fresh
            // watchdog rather than trusting this one.
            watchdog = null
            return false
        }
        if (handle != null) return true
        Log.w(TAG, "watchdog: ${leases.size} lease(s) but no connection — opening")
        open()
        return true
    }

    /**
     * Queues a rebuild of the whole client after a failure, with exponential backoff.
     *
     * A rebuild rather than a retry, because that is the distinction that decides whether
     * the broker accepts us at all — see [HiveMqTransport] on `automaticReconnect`.
     *
     * [failed] is what makes this safe to over-schedule: a burst of disconnect events
     * queues several of these, and the first one to run retires the handle, so the rest
     * find themselves stale and do nothing. A final release in the meantime does the same.
     */
    private fun scheduleReconnect(failed: MqttTransport.Handle) {
        val delayMs = reconnectPolicy.nextDelayMs()
        scope.launch {
            delay(delayMs)
            reconnectNow(failed)
        }
    }

    @Synchronized
    private fun reconnectNow(failed: MqttTransport.Handle) {
        // Nobody wants a connection any more, or this attempt has been superseded.
        if (leases.isEmpty() || failed !== handle) return
        Log.i(TAG, "reconnecting")
        teardown()
        open()
    }

    /** Opens a connection for the current settings. Called under the monitor. */
    private fun open() {
        val cfg = currentConfig()
        if (!cfg.isComplete) {
            // Nothing to connect to. Callers still hold their leases, so the last close
            // balances out — and the watchdog re-reads the settings every tick, which is
            // what turns "user finishes setup" into a connection without a relaunch.
            Log.w(TAG, "not configured — no broker host set")
            _connection.value = ConnectionState(ConnectionStatus.FAILED, "No broker configured")
            return
        }
        active = cfg
        protocol = GateProtocol(cfg.topics)
        _connection.value = ConnectionState(ConnectionStatus.CONNECTING)
        handle = transport.connect(cfg.wire, listener)
    }

    /**
     * Retires the current connection and everything derived from it, without touching the
     * leases — [acquire] and [refresh] use this to swap in corrected settings while their
     * callers keep holding the connection; the last [release] uses it to let go entirely.
     */
    private fun teardown() {
        // Retire the handle first: a client still mid-handshake will complete after this
        // returns, and its callbacks must find themselves stale rather than write to flows
        // that now describe no connection at all.
        val old = handle
        handle = null
        protocol = null
        _bridgeStatus.value = BridgeStatus.UNKNOWN

        // A retired connection must not leave CONNECTED behind. Every caller either opens a
        // fresh one immediately (which reports CONNECTING over this) or is letting go
        // entirely, so the only thing this can overwrite is a status that has outlived its
        // socket — and that lie is load-bearing: sendCommandAwait() waits on this very flow
        // before publishing, and the settings screen presents it as a live credential test.
        _connection.value = ConnectionState(ConnectionStatus.DISCONNECTED)

        // The gate state is only meaningful while we are attached to the broker. Keeping
        // it would let awaitFreshState() return this value instantly on the next connect —
        // the arrival pop-up would confidently announce whatever the gate was doing hours
        // ago, without a single byte having crossed the network. [lastError] deliberately
        // survives: a command sent with the app closed reports its failure and immediately
        // releases the connection, and tearing the message down with it would erase the
        // report before anything could render it.
        reducer.reset()
        _gateState.value = GateState(GatePolicy.STATE_UNKNOWN, "")

        old?.close()
    }

    // --- transport callbacks ----------------------------------------------------------

    private val listener = object : MqttTransport.Listener {

        override fun onConnected(handle: MqttTransport.Handle) =
            this@GateService.onConnected(handle)

        override fun onDisconnected(handle: MqttTransport.Handle, byUser: Boolean, cause: Throwable?) =
            this@GateService.onDisconnected(handle, byUser, cause)

        override fun onConnectFailed(handle: MqttTransport.Handle, cause: Throwable) =
            this@GateService.onConnectFailed(handle, cause)

        override fun onMessage(handle: MqttTransport.Handle, topic: String, payload: String, retained: Boolean) =
            this@GateService.onMessage(handle, topic, payload, retained)

        override fun onSubscribeFailed(handle: MqttTransport.Handle, topic: String) =
            this@GateService.onSubscribeFailed(handle, topic)
    }

    @Synchronized
    private fun onConnected(h: MqttTransport.Handle) {
        if (h !== handle) return
        reconnectPolicy.reset()
        _connection.value = ConnectionState(ConnectionStatus.CONNECTED)
        // Re-subscribe on EVERY (re)connect. The session is clean, so the broker holds no
        // subscriptions for us; without this, a reconnect leaves the app looking healthy
        // while deaf to state changes.
        val proto = protocol ?: return
        h.subscribe(proto.subscriptions(active.mqtt))
    }

    @Synchronized
    private fun onDisconnected(h: MqttTransport.Handle, byUser: Boolean, cause: Throwable?) {
        if (h !== handle) return
        // UNKNOWN, not OFFLINE: losing our own connection says nothing about the bridge,
        // and claiming "unreachable" here is how the VPN bug looked real.
        _bridgeStatus.value = BridgeStatus.UNKNOWN

        // byUser means our own close() — that path reports DISCONNECTED itself. Everything
        // else is a failure we have to recover from ourselves.
        if (!byUser) {
            Log.w(TAG, "MQTT disconnected", cause)
            _connection.value =
                ConnectionState(ConnectionStatus.FAILED, ConnectionErrorMessages.describe(cause))
            scheduleReconnect(h)
        }
    }

    @Synchronized
    private fun onConnectFailed(h: MqttTransport.Handle, cause: Throwable) {
        if (h !== handle) return
        _connection.value =
            ConnectionState(ConnectionStatus.FAILED, ConnectionErrorMessages.describe(cause))
    }

    @Synchronized
    private fun onMessage(h: MqttTransport.Handle, topic: String, payload: String, retained: Boolean) {
        if (h !== handle) return
        val proto = protocol ?: return
        when (val event = proto.decode(topic, payload, retained)) {
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

    @Synchronized
    private fun onSubscribeFailed(h: MqttTransport.Handle, topic: String) {
        if (h !== handle) return
        // A denied subscription is invisible otherwise: the client stays happily connected
        // and simply never hears anything, which reads on screen as a working app attached
        // to a silent gate.
        _connection.value = ConnectionState(
            ConnectionStatus.DEGRADED,
            "Connected, but the broker refused a subscription",
        )
    }

    // --- outbound -------------------------------------------------------------------

    /** The shared button rule — see [GatePolicy.primaryAction]. */
    fun primaryAction(state: String) = GatePolicy.primaryAction(state)

    /** Fire-and-forget, for UI callers that already hold a lease. */
    fun sendCommand(action: String) {
        scope.launch { sendCommandAwait(action) }
    }

    /**
     * Acquires its own lease, publishes, and waits for the broker ack. The notification
     * action uses this: it fires with the app closed and must not return before the send
     * lands.
     */
    suspend fun sendCommandAwait(action: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): Boolean {
        if (action !in GateProtocol.ACTION_TO_SIGNAL) {
            Log.w(TAG, "unknown action: $action")
            return false
        }

        acquire("command").use {
            // One budget for the whole operation, not one per stage. Two sequential 8 s
            // waits could burn 16 s inside a BroadcastReceiver that has ~10 s of goAsync.
            val deadline = System.currentTimeMillis() + timeoutMs
            val connected = withTimeoutOrNull(timeoutMs) {
                // DEGRADED counts: a refused *subscription* says nothing about whether we
                // may publish, and refusing to send the command would be a worse failure
                // than trying and reporting the broker's answer.
                connection.first {
                    it.status == ConnectionStatus.CONNECTED || it.status == ConnectionStatus.DEGRADED
                }
            } != null
            val h = if (connected) handle else null
            if (h == null) {
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
                Log.w(TAG, "no connection vocabulary for: $action")
                return false
            }

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            val sent = withTimeoutOrNull(remaining) {
                // retain=false always — see [GateProtocol.encodeCommand] for why a
                // retained command is at best silently ignored.
                h.publish(command.topic, command.payload, cfg.mqtt.qosCommand, retain = false)
            } ?: false
            if (!sent) reportError("Command not sent — the broker did not accept it")
            return sent
        }
    }

    /**
     * Suspends until state arrives *from the broker on this connection*, or gives up.
     * Used by the geofence pop-up.
     *
     * "Fresh" is the whole point, and it is why [teardown] resets the gate state. When the
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
            gateState.first { it.state != GatePolicy.STATE_UNKNOWN }
            delay(settleMs)
            gateState.value
        }

    /**
     * Publishes a reason the user can act on, and starts its expiry.
     *
     * Time-limited on purpose: an error is about the command just attempted, and one left
     * on screen indefinitely would be read as the state of the *next* one.
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

    companion object {
        private const val TAG = "Domofon"

        /** How long a command error stays on screen. Survives teardown — see [teardown]. */
        private const val ERROR_TTL_MS = 20_000L

        /**
         * How often the watchdog re-checks the leases-imply-a-connection invariant. Long
         * enough to be free, short enough that a user who taps Open twice has a connection
         * by the second tap.
         */
        private const val WATCHDOG_PERIOD_MS = 15_000L

        /** Whole-operation budget for [sendCommandAwait] — see the deadline note inside. */
        private const val COMMAND_TIMEOUT_MS = 8_000L

        /**
         * Interim composition until AppContainer (next step) owns the instance. Everything
         * that used to reach the `GateRepository` object reaches this instead.
         */
        val instance: GateService by lazy {
            GateService(
                transport = HiveMqTransport(),
                currentConfig = { ConfigStore.current },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
        }
    }
}
