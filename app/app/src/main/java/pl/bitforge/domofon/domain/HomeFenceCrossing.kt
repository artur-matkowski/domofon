package pl.bitforge.domofon.domain

/**
 * Which side of the fence a fix places us on, or [FenceSide.UNKNOWN] when it cannot say.
 *
 * The margin is the fix's own accuracy, and it is the whole point of this function.
 * `HomeDistanceTracker` asks for `PRIORITY_BALANCED_POWER_ACCURACY` fixes, and a cold one in
 * a car — no sky, engine just started — can be cell-derived and kilometres out. Compared with
 * a bare `meters <= radius`, one such fix followed by a good one is an outside→inside
 * transition, indistinguishable from driving home: a false arrival announced from the
 * driveway (Artur, live testing 2026-07-28).
 *
 * So a side is only claimed when the fix is precise enough to claim it. The band in between
 * is not a third state to act on — it is the honest answer "this fix cannot place us", and
 * every caller ignores it rather than guessing.
 *
 * This catches a fix whose *reported* error bar spans the fence, which is what a coarse one
 * looks like at a 2 km radius. It cannot catch a fix that is confidently wrong — nothing in
 * the app can — which is one more reason the native fence keeps its own opinion.
 */
fun sideOf(meters: Float, accuracyMeters: Float, radiusMeters: Float): FenceSide = when {
    meters + accuracyMeters <= radiusMeters -> FenceSide.INSIDE
    meters - accuracyMeters > radiusMeters -> FenceSide.OUTSIDE
    else -> FenceSide.UNKNOWN
}

/**
 * "Did we just arrive?", decided from a stream of sides — the in-app half of the arrival
 * trigger, as a pure rule.
 *
 * The app has two independent ways to notice it is coming home and they share nothing.
 * The native one is a Play Services geofence: registered once, evaluated inside GMS on a
 * schedule the app cannot see, set or read back, and delivered to a receiver that may wake a
 * dead process. This is the other one — the app's own
 * [HomeDistanceTracker][pl.bitforge.domofon.data.location.HomeDistanceTracker] readings,
 * compared against the same radius, in this process, while a surface is alive. It exists
 * because a 10 km round trip produced no pop-up at all and nothing in the app could say why
 * (Artur, live testing 2026-07-27); it is opt-in, and it runs *in parallel* with the native
 * fence rather than replacing it.
 *
 * Deliberately mirrors the native fence's semantics so the two cannot disagree:
 *
 * - **Inward crossings only** — the same `GEOFENCE_TRANSITION_ENTER` the fence registers.
 * - **The first reading never fires.** It only establishes which side we are on, exactly as
 *   [GeofenceManager][pl.bitforge.domofon.data.location.GeofenceManager] omits
 *   `INITIAL_TRIGGER_ENTER`: at the default 2 km radius the house is *inside* the fence, so a
 *   trigger on the first reading would announce "approaching home" every time the car screen
 *   opened on the driveway.
 * - **A fix that cannot place us is not a reading.** [FenceSide.UNKNOWN] neither fires nor
 *   changes which side we think we are on — see [sideOf].
 *
 * De-duplication against the native fence is **not** here — it is in
 * [arrivalRefusal], which both triggers funnel through via `ArrivalFlow`, because only that
 * side survives the process death the native fence delivers into.
 */
class HomeFenceCrossing {

    /** Null until the first usable reading; then which side that reading was on. */
    private var wasInside: Boolean? = null

    /**
     * Feed one reading, already reduced to a side by [sideOf].
     *
     * @return true exactly on an outside → inside transition.
     */
    @Synchronized
    fun onReading(side: FenceSide): Boolean {
        // Not a reading at all: too coarse to place us. Keeping the previous side is what
        // stops a bad fix from manufacturing a crossing out of the good ones either side.
        if (side == FenceSide.UNKNOWN) return false

        val inside = side == FenceSide.INSIDE
        val previous = wasInside
        wasInside = inside
        return previous == false && inside
    }

    /**
     * Forget which side we were on, so the next reading only re-establishes it.
     *
     * Called when the readings stop being continuous — the tracker was stopped, or the home
     * position changed under it. Without this, a tracker restarted inside the fence after
     * being stopped outside it would report an arrival it never observed.
     */
    @Synchronized
    fun reset() {
        wasInside = null
    }
}
