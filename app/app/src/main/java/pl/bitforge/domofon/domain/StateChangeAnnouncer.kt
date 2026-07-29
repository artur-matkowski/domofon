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
 * notification about a gate that had not moved (Artur, live testing 2026-07-27).
 *
 * Two rules, therefore:
 *
 * 1. **Retained is never news.** A retained message is the broker replaying what it was
 *    already holding: this connection finding out where the gate already was. This is the
 *    strong form of the rule that used to read "a transition out of `unknown` is learning",
 *    which only covered the *first* move of the retained burst — and the burst is
 *    last-value-per-signal in arbitrary order, so it can easily move the state twice. The
 *    second move was indistinguishable from a real one. The same rule is what makes the
 *    arrival flow's own `acquire("arrival")` incapable of announcing anything.
 * 2. **Nothing is news to someone already reading it.** While a Domofon surface is in front
 *    of the user — the car screen on the head unit, or the phone app — that surface is
 *    already showing this state and the button that acts on it. A heads-up drawn over the
 *    app it duplicates is noise, and on the head unit it covers the very screen the driver
 *    chose (Artur, live testing 2026-07-28).
 *
 * ## What used to be here: the own-tap silence
 *
 * A third rule silenced the first state change after this app sent a command, so tapping
 * Open hid the `opening` you caused and still announced the `opened` you were waiting for.
 * It is gone, and with it `GateService.lastCommandAtMs`.
 *
 * It was right for the surface it was written against and wrong everywhere else. Every
 * command path either has a screen in front of the user — the QML button, the car pane —
 * in which case rule 2 already silences the echo, or it is the button *inside a
 * notification*, which dismisses itself on the tap. In that second case the echo is the only
 * feedback there is, and swallowing it meant tapping *Open gate* on the head unit produced
 * nothing at all for the twenty seconds the gate takes to move (Artur, live testing
 * 2026-07-29). A rule whose only remaining condition was `surfaceVisible` is rule 2 wearing
 * a clock.
 *
 * Not a `StateFlow` operator and not a collector: [shouldAnnounce] takes every input it
 * needs, so there is no scheduling and nothing to race.
 */
class StateChangeAnnouncer {

    /** The last *known* state announced or learned; null means "this connection has none". */
    private var lastSeen: String? = null

    /**
     * @param state the new gate state.
     * @param live whether a message the bridge published while we were attached carried it,
     *   from [GateState.live] — as opposed to a retained one the broker replayed.
     * @param surfaceVisible whether a Domofon surface is currently in front of the user, from
     *   [SurfacePresence][pl.bitforge.domofon.ui.shared.SurfacePresence]. Checked **last**, on
     *   purpose: a change suppressed by rule 2 still happened, so it must still update
     *   [lastSeen]. Checking it first would leave the next change to be judged against a state
     *   this connection has moved on from.
     */
    @Synchronized
    fun shouldAnnounce(state: String, live: Boolean, surfaceVisible: Boolean): Boolean {
        // A disconnect reset. Forget the state so the next connection's first real value is
        // treated as learning rather than as a move away from whatever we saw last time.
        if (state == GatePolicy.STATE_UNKNOWN) {
            lastSeen = null
            return false
        }

        val previous = lastSeen
        // Before every other verdict below: what a retained message taught is silent but not
        // ignored, or the first live change would be judged against nothing.
        lastSeen = state

        if (!live) return false                  // rule 1: the broker's memory, not a movement
        if (previous == null) return false       // ...nor is the first thing we ever hear
        if (previous == state) return false      // StateFlow conflates, but belt and braces

        // rule 2: the user is looking at a screen that already says this.
        if (surfaceVisible) return false

        return true
    }
}
