package pl.bitforge.domofon.data.mqtt

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the connection alive for a while after a command sent from a notification, so the app
 * is still listening when the gate answers.
 *
 * [GateService.sendCommandAwait] holds its own lease only as far as the broker's publish ack,
 * which is the right lifetime for *sending*. It is the wrong one for *hearing*: with no
 * surface open — the whole case the notification button exists for — the app disconnected a
 * few hundred milliseconds after the publish, and the gate does not report `opening` for
 * another second or two. So tapping *Open gate* on the head unit produced no feedback at all
 * for the twenty seconds the gate takes to move, and it was not the notification rules that
 * swallowed it: there was never a state change to swallow (Artur, live testing 2026-07-29).
 *
 * Holding the connection is the whole job. Announcing is already
 * [GateEventNotifier][pl.bitforge.domofon.ui.notifications.GateEventNotifier]'s — it is the
 * single process-wide `gateState` collector and sees the cycle for free.
 *
 * **This does not contradict D5 (no 24/7 connection).** It is a bounded, user-initiated hold
 * that expires on its own, in the one situation where a lease would otherwise be dropped
 * between asking a question and being answered.
 *
 * ## The bit that is only best-effort
 *
 * `goAsync()` keeps [GateCommandReceiver][pl.bitforge.domofon.receivers.GateCommandReceiver]'s
 * process at receiver priority for roughly ten seconds, which comfortably covers the first
 * announcement. The tail that catches `opened` some twenty seconds later runs in a *cached*
 * process and can be reclaimed. Losing it costs the second notification and nothing else —
 * no lease leaks, because the scope dies with the process. Guaranteeing it would take a
 * foreground service, which is not worth its permanent notification for a 45-second window.
 */
class CommandFollowThrough(
    private val gate: GateService,
    private val scope: CoroutineScope,
    private val holdMs: Long = HOLD_MS,
) {

    /**
     * The claim itself, held here rather than in the coroutine's `finally`.
     *
     * That was the first shape of this and it leaked: a coroutine cancelled before it has
     * started running never enters its body, so the `finally` releasing the lease never ran
     * either — and `arm()` immediately followed by `disarm()` is exactly the ordering the
     * failure path produces. Releasing is a plain synchronized field write instead, and the
     * timer only ever *asks* for it.
     */
    private var lease: ConnectionLease? = null

    private var job: Job? = null

    /**
     * Claim the connection until [holdMs] from now.
     *
     * **The lease is taken here, synchronously, and only the waiting is launched.** Acquiring
     * it inside the coroutine would let it land *after* `sendCommandAwait` released its own,
     * and the teardown in between resets `gateState` and costs a VPN handshake to undo — during
     * which the live `GateOpening` is missed. It would then only ever arrive again as a
     * *retained* value, which [StateChangeAnnouncer][pl.bitforge.domofon.domain.StateChangeAnnouncer]
     * refuses to announce. The window would be open and empty.
     *
     * Re-arming takes the new lease before releasing the old one, so two taps in a row extend
     * the window rather than dropping the connection between them.
     */
    @Synchronized
    fun arm() {
        val previous = lease
        val mine = gate.acquire(LEASE_TAG)
        lease = mine
        job?.cancel()
        job = scope.launch {
            delay(holdMs)
            expire(mine)
        }
        // After the new one is held: the lease count must never touch zero in between, or the
        // teardown this whole class exists to prevent happens on the way to preventing it.
        previous?.close()
        Log.i(TAG, "follow-through armed for ${holdMs}ms")
    }

    /** The command never left the phone, so there is nothing coming to listen for. */
    @Synchronized
    fun disarm() {
        job?.cancel()
        job = null
        lease?.close()
        lease = null
    }

    /** The timer ran out. Ignored if a re-arm has since replaced us — that hold is longer. */
    @Synchronized
    private fun expire(mine: ConnectionLease) {
        if (lease !== mine) return
        lease = null
        mine.close()
    }

    companion object {
        /**
         * One gate cycle plus margin. `opening` lands in a second or two and `opened` fifteen
         * to twenty-five after that ([GateNotifier][pl.bitforge.domofon.ui.notifications.GateNotifier]
         * — the two ids exist for exactly this pair), so anything much under this would hold
         * the connection through the announcement nobody needed and drop it before the one
         * they were waiting for.
         */
        const val HOLD_MS = 45_000L

        /** A sixth lease holder beside `phone-ui`, `settings`, `car-session`, … (D5). */
        private const val LEASE_TAG = "command-follow"

        private const val TAG = "Domofon"
    }
}
