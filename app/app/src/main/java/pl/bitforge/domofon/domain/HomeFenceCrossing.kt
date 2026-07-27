package pl.bitforge.domofon.domain

/**
 * "Did we just arrive?", decided from a stream of distances — the in-app half of the arrival
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
 *
 * De-duplication against the native fence is **not** here — it is a cooldown in
 * `ArrivalFlow`, which both triggers funnel through, because only that side survives the
 * process death the native fence delivers into.
 */
class HomeFenceCrossing(private val radiusMeters: Float) {

    /** Null until the first reading; then which side of the fence that reading was on. */
    private var wasInside: Boolean? = null

    /**
     * Feed one distance reading.
     *
     * @return true exactly on an outside → inside transition.
     */
    @Synchronized
    fun onReading(meters: Float): Boolean {
        val inside = meters <= radiusMeters
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
