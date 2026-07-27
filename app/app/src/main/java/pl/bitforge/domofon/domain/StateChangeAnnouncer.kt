package pl.bitforge.domofon.domain

/**
 * Which gate-state movements are worth a heads-up notification — the rule on its own, with
 * no Android in it, so the whole table is a JVM test.
 *
 * Its predecessor was a bare `drop(1)` on `gateState` in `GateEventNotifier`, which dropped
 * exactly one value in the life of the *process*: the initial `unknown`. That is not what
 * the connection does. Every teardown resets `gateState` to `unknown`
 * ([GateService.teardown][pl.bitforge.domofon.data.mqtt.GateService], invariant 4 — the
 * arrival pop-up depends on it), and the next connection then *learns* the real state from
 * the retained `rx` topics. `unknown → closed` is a new value, so it read as a change, so
 * opening the car app — or the phone app, or Settings, or any reconnect — posted a
 * notification about a gate that had not moved (Artur, live testing 2026-07-27). Worst of
 * all inside the arrival flow itself, whose own `acquire("arrival")` fired a state-change
 * pop-up ~750 ms before the arrival pop-up it was there to deliver.
 *
 * Two rules, therefore:
 *
 * 1. **Learning is not news.** A transition out of `unknown` is this connection finding out
 *    where the gate already was. Only a move between two *known* states is a change.
 * 2. **Your own tap is not news either.** A command silences the next state change, and only
 *    the next one — tapping Open hides the `opening` you caused and still announces the
 *    `opened` you were waiting for. Artur's rule, and the reason the silence is *consumed*
 *    by a change rather than simply expiring on a clock.
 *
 * Not a `StateFlow` operator and not a collector: [shouldAnnounce] takes every input it
 * needs, including the clock, so there is no scheduling and nothing to race.
 */
class StateChangeAnnouncer(private val silenceMs: Long = COMMAND_SILENCE_MS) {

    /** The last *known* state announced or learned; null means "this connection has none". */
    private var lastSeen: String? = null

    /**
     * The command timestamp whose silence has already been spent.
     *
     * Comparing timestamps rather than counting down a deadline is what makes one command
     * worth exactly one silenced change: a second change carries the same
     * `lastCommandAtMs`, sees it already consumed, and announces.
     */
    private var consumedCommandAt = 0L

    /**
     * @param state the new gate state.
     * @param lastCommandAtMs when this app last sent a command, from
     *   [GateService.lastCommandAtMs][pl.bitforge.domofon.data.mqtt.GateService.lastCommandAtMs];
     *   0 means "never".
     * @param nowMs the same clock [lastCommandAtMs] is measured on.
     */
    @Synchronized
    fun shouldAnnounce(state: String, lastCommandAtMs: Long, nowMs: Long): Boolean {
        // A disconnect reset. Forget the state so the next connection's first real value is
        // treated as learning rather than as a move away from whatever we saw last time.
        if (state == GatePolicy.STATE_UNKNOWN) {
            lastSeen = null
            return false
        }

        val previous = lastSeen
        lastSeen = state

        if (previous == null) return false      // rule 1: learned on connect
        if (previous == state) return false      // StateFlow conflates, but belt and braces

        // rule 2: the change we caused ourselves, once.
        if (lastCommandAtMs != consumedCommandAt && nowMs - lastCommandAtMs < silenceMs) {
            consumedCommandAt = lastCommandAtMs
            return false
        }
        return true
    }

    companion object {
        /**
         * How long after a command a state change still counts as "the one I just asked
         * for". Generous because the gate is slow and the broker round trip crosses a VPN;
         * it costs nothing to be wide, because the silence is consumed by the first change
         * either way.
         */
        const val COMMAND_SILENCE_MS = 20_000L
    }
}
