package pl.bitforge.domofon.domain

import java.time.Instant

/**
 * Decides whether a decoded [GateEvent.Signal] may move the gate state, per connection.
 *
 * One instance per connection, reset on teardown — the newest-timestamp memory is only
 * meaningful against messages from the same broker session. The rules, each of which was a
 * shipped bug before it became a rule:
 *
 * 1. **Future timestamps are disbelieved** ([futureToleranceS]). A stamp from the future is
 *    either clock skew on the bridge or someone publishing junk to a broker we may be
 *    reaching over plaintext. Accepting one would be permanent: it becomes the newest seen,
 *    and from then on every genuine message looks older and gets dropped — the app freezes
 *    on a stale state for the rest of the process lifetime while looking perfectly healthy.
 *    Rejecting it does *not* poison the memory: the bad stamp is never stored.
 * 2. **Retained messages need a strictly newer stamp.** Retained rx topics are
 *    last-value-per-signal and arrive in arbitrary order (observed: GateClosed 11:40:10
 *    landed before GateOpening 11:40:05), so only strictly newer may move the state.
 * 3. **A live message wins ties instead of losing them** — that is what lets it override a
 *    retained value carrying the same one-second stamp. It still may not move the state
 *    *backwards*, which the old code allowed and which is how one skewed publish used to
 *    stick permanently.
 *
 * Thread-safety: callers serialise access (one Netty event loop today); the class itself
 * synchronises anyway because the guard and the decision must be one step — two threads
 * passing the check in order and then applying out of order is the same bug rule 2 fixes.
 */
class GateStateReducer(
    private val now: () -> Instant = Instant::now,
    private val futureToleranceS: Long = FUTURE_TS_TOLERANCE_S,
) {

    private var newestTs: Instant? = null

    /**
     * The new [GateState] to publish, or null when the signal is stale or disbelieved.
     * A non-null return has already been recorded as the newest.
     */
    @Synchronized
    fun reduce(signal: GateEvent.Signal): GateState? {
        if (signal.ts.isAfter(now().plusSeconds(futureToleranceS))) return null

        val current = newestTs
        if (current != null) {
            val stale =
                if (signal.retained) !signal.ts.isAfter(current)
                else signal.ts.isBefore(current)
            if (stale) return null
        }
        newestTs = signal.ts
        return GateState(signal.state, signal.rawTs, live = !signal.retained)
    }

    /** Forget the connection's timestamp memory — call from teardown, before the next open. */
    @Synchronized
    fun reset() {
        newestTs = null
    }

    companion object {
        /**
         * How far ahead of our own clock a bridge timestamp may sit before we disbelieve
         * it. Generous, because the phone and the bridge are both plausibly a little off.
         */
        const val FUTURE_TS_TOLERANCE_S = 300L
    }
}
