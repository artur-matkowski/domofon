package pl.bitforge.domofon.domain

/**
 * What the app knows about its own arrival trigger, and the line Settings renders from it.
 *
 * This exists because of a specific failure: a 10 km round trip produced no arrival pop-up,
 * and nothing anywhere could say whether the fence had ever been registered, whether Play
 * Services had delivered anything, or whether a pop-up had been suppressed. `sync()` logged
 * its outcome and that was all — which is diagnosable only with the phone on a cable, and
 * nobody drives with the phone on a cable.
 *
 * The fields are chosen to split the three failures apart, because they need different fixes:
 *
 * | Reads | Means |
 * |---|---|
 * | not registered | the fence was never armed — permission or position |
 * | registered, no ENTER | armed, and Play Services never delivered — OEM battery management |
 * | ENTER seen, no pop-up | delivered, and the flow dropped it — cooldown, or the broker |
 *
 * **Timestamps and status codes only — never coordinates** ([geo invariant 5]). The home
 * position is the user's address, and a settings row is as readable as logcat.
 */
data class GeofenceStatus(
    val sync: FenceSync = FenceSync.NEVER,
    val syncAtMs: Long = 0L,
    /** The Play Services status code when [sync] is [FenceSync.FAILED]; empty otherwise. */
    val syncDetail: String = "",
    /** Last delivery from Play Services — the *native* fence actually firing. */
    val lastNativeEnterAtMs: Long = 0L,
    /** Last inward crossing the app's own readings saw, when the in-app fence is on. */
    val lastInAppCrossingAtMs: Long = 0L,
    /** Last arrival pop-up actually posted. */
    val lastAnnouncedAtMs: Long = 0L,
    /** Which trigger won that pop-up — [SOURCE_NATIVE] or [SOURCE_IN_APP]. */
    val lastAnnouncedBy: String = "",
    /** Why the most recent delivery was thrown away, if one was. */
    val lastRejection: String = "",
    val lastRejectionAtMs: Long = 0L,
    /** Which side of the fence the app last had evidence for. See [FenceSide]. */
    val side: FenceSide = FenceSide.UNKNOWN,
    val sideAtMs: Long = 0L,
) {
    companion object {
        const val SOURCE_NATIVE = "Play Services"
        const val SOURCE_IN_APP = "in-app"

        /**
         * How long after an arrival pop-up the next one is refused.
         *
         * The two triggers are independent by design and will often both notice the same
         * approach, seconds apart. One announcement per arrival is the point; ten minutes is
         * comfortably longer than the two can disagree by and far shorter than a round trip.
         */
        const val ARRIVAL_COOLDOWN_MS = 10L * 60 * 1000

        /**
         * How long a recorded [FenceSide] is believed.
         *
         * The "you must have been outside" rule is only as good as the evidence behind it,
         * and the evidence can go stale: Play Services can drop an EXIT, and the distance
         * tracker only runs while a surface is alive. Past this age the app admits it does
         * not know where it is and lets the arrival through — because a stuck `INSIDE` would
         * silence the feature permanently, which is the exact failure a 10 km round trip
         * already cost once, and a redundant pop-up is the cheaper mistake.
         */
        const val SIDE_TRUST_MS = 12L * 60 * 60 * 1000
    }
}

/**
 * Which side of the home fence the app last had *evidence* for — not a guess.
 *
 * Persisted, because it exists to answer a question the native fence asks from a process
 * that is usually dead: "was I outside before this ENTER?". Artur's rule, from live testing
 * 2026-07-28: if the previous position was outside the fence and this one is inside, that is
 * an arrival; anything else is Play Services re-evaluating a fence around a parked car.
 */
enum class FenceSide {
    /** No evidence, or the last fix was too coarse to place us. Never blocks an arrival. */
    UNKNOWN,
    INSIDE,
    OUTSIDE,
}

/** How the last attempt to register the fence with Play Services went. */
enum class FenceSync {
    /** No attempt has been recorded — a fresh install, or a build from before this existed. */
    NEVER,
    REGISTERED,

    /** The feature is on but there is no usable home position, so the fence was removed. */
    NO_POSITION,

    /** Background location is missing. The fence is refused loudly rather than registered dead. */
    NO_PERMISSION,
    FAILED,
}

/**
 * Why this arrival must not be announced, or null to announce it — the shared guard every
 * trigger passes through.
 *
 * Returns the *reason* rather than a boolean so the refusal can be recorded and read back in
 * Settings. "Nothing popped up" and "something popped it down" are the two outcomes this
 * whole status model exists to tell apart.
 *
 * Lives here rather than in either trigger because only *persisted* state can de-duplicate
 * them: the native fence delivers into a process that is usually dead, so an in-memory latch
 * on one side would never see the other's announcement.
 *
 * @param requireDeparture whether the caller needs the fence-side check. True only for the
 *   native fence, which carries no memory of where it was. The in-app fence observed both
 *   sides itself before it fired, so applying the rule to it would be asking the same
 *   question twice — and the debug trigger stands in for the native fence from a desk that
 *   is inside the fence, where the rule would refuse every test.
 */
fun arrivalRefusal(
    status: GeofenceStatus,
    nowMs: Long,
    requireDeparture: Boolean,
    cooldownMs: Long = GeofenceStatus.ARRIVAL_COOLDOWN_MS,
    sideTrustMs: Long = GeofenceStatus.SIDE_TRUST_MS,
): String? {
    val last = status.lastAnnouncedAtMs
    if (last > 0L && nowMs - last < cooldownMs) return "another pop-up was posted minutes ago"

    if (requireDeparture &&
        status.side == FenceSide.INSIDE &&
        nowMs - status.sideAtMs < sideTrustMs
    ) {
        return "already inside the fence"
    }
    return null
}

/**
 * The Settings summary, as prose the driver can act on.
 *
 * @param inAppFenceOn whether to report the in-app trigger at all — with it off there is
 *   nothing to say about it, and a permanent "no crossing seen" reads as a fault.
 * @param stamp renders a timestamp; supplied by the caller so this stays pure and testable
 *   (the Android side hands in a locale-aware [java.text.DateFormat]).
 */
fun formatGeofenceStatus(
    status: GeofenceStatus,
    inAppFenceOn: Boolean,
    stamp: (Long) -> String,
): String {
    val lines = mutableListOf<String>()

    lines += when (status.sync) {
        FenceSync.NEVER -> "Not registered yet"
        FenceSync.NO_POSITION -> "Not registered — no home position set"
        // Named exactly as the system dialog names it, because that is the screen the user
        // has to go to. This is the single most common reason a geofence never fires.
        FenceSync.NO_PERMISSION -> "NOT registered — needs \"Allow all the time\""
        FenceSync.FAILED -> "Registration failed: ${status.syncDetail}"
        FenceSync.REGISTERED -> "Registered ${stamp(status.syncAtMs)}"
    }

    lines += if (status.lastNativeEnterAtMs > 0L) {
        "Play Services: last arrival ${stamp(status.lastNativeEnterAtMs)}"
    } else {
        "Play Services: no arrival seen yet"
    }

    if (inAppFenceOn) {
        lines += if (status.lastInAppCrossingAtMs > 0L) {
            "In-app: last crossing ${stamp(status.lastInAppCrossingAtMs)}"
        } else {
            "In-app: no crossing seen yet"
        }
    }

    // The input to the "you must have been outside" rule, so a refused arrival can be read
    // back as a consequence rather than as a mystery.
    if (status.side != FenceSide.UNKNOWN) {
        val where = if (status.side == FenceSide.INSIDE) "inside" else "outside"
        lines += "Last seen: $where the fence ${stamp(status.sideAtMs)}"
    }

    if (status.lastAnnouncedAtMs > 0L) {
        lines += "Last pop-up ${stamp(status.lastAnnouncedAtMs)} (${status.lastAnnouncedBy})"
    }

    // Only when it is the most recent thing that happened — an old rejection sitting under a
    // healthy delivery would read as a current fault.
    if (status.lastRejection.isNotEmpty() &&
        status.lastRejectionAtMs > status.lastNativeEnterAtMs
    ) {
        lines += "Last event ignored: ${status.lastRejection} ${stamp(status.lastRejectionAtMs)}"
    }

    return lines.joinToString("\n")
}
