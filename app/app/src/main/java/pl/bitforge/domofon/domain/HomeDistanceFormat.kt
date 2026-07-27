package pl.bitforge.domofon.domain

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The distance-from-home line, identical on the phone and the car. Empty string when there
 * is nothing to show, which both surfaces treat as "hide the element".
 *
 * Always shows the actual range, followed by a zone word rather than collapsing to "At
 * home" — a driver watching a still wants to know how far out they are, not just that they
 * are somewhere inside the fence. The zone is banded on the geofence radius R: nearer than
 * R/2 is "at home", the R/2‥3R/2 band around the fence is "approaching home", beyond that
 * is "away from home". Range rounded (10 m, then 0.1 km) so it does not flicker between
 * fixes.
 *
 * @param meters distance from the home geofence centre, or null when unavailable.
 * @param radiusMeters the geofence radius the zone bands derive from.
 * @param nextFixInMs when the app will look again, or null to say nothing about it. Only
 *   supplied when the in-app fence is switched on, because only then is the cadence a fact
 *   about the *arrival trigger* rather than about a readout nobody asked to be live. It is
 *   the app's own polling interval and says nothing about the Play Services fence, which
 *   evaluates on a schedule the app cannot observe — see
 *   [GeofenceStatus].
 */
fun formatHomeDistance(
    meters: Float?,
    radiusMeters: Float,
    nextFixInMs: Long? = null,
): String {
    if (meters == null) return ""
    val zone = when {
        meters < radiusMeters * 0.5f -> "at home"
        meters <= radiusMeters * 1.5f -> "approaching home"
        else -> "away from home"
    }
    val range = if (meters < 1000f) {
        "${(meters / 10f).roundToInt() * 10} m"
    } else {
        String.format(Locale.US, "%.1f km", meters / 1000f)
    }
    val cadence = nextFixInMs?.let { " · next ≤${formatCadence(it)}" }.orEmpty()
    return "$range · $zone$cadence"
}

/**
 * The poll interval, short enough to sit on a car row. "≤" because it is an upper bound: the
 * loop may be woken sooner and a fix may take a moment longer.
 */
private fun formatCadence(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds < 60) "${seconds}s" else "${(seconds + 30) / 60}min"
}
