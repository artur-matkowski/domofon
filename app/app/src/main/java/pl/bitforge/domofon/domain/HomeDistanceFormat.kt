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
 */
fun formatHomeDistance(meters: Float?, radiusMeters: Float): String {
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
    return "$range · $zone"
}
