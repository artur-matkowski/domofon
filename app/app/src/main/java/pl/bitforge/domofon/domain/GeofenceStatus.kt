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
    /** Which side of the fence the app last observed. Reported, never acted on — see [FenceSide]. */
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
    }
}

/**
 * Which side of the home fence the app last observed — **a readout, not a gate.**
 *
 * It briefly was a gate: an ENTER was refused unless the app had separately seen us leave.
 * That rule is wrong, and Artur said so the same day (2026-07-28). *Crossing direction is
 * universal*; requiring corroboration of the departure adds a second, weaker source of truth
 * that the first one does not need. Turn the phone off at home, drive away with the app dead,
 * turn it on again already on the way back: nothing observed a departure, the crossing is
 * still unambiguously inward, and the rule would have refused a real arrival — the exact
 * failure D13 exists to avoid. `GEOFENCE_TRANSITION_ENTER` *is* the direction; it is trusted.
 *
 * What the side is still worth is diagnosis. After a drive, "did Play Services see me leave?"
 * is a question the Settings row can now answer, and the history of this feature is one of
 * silent failures that looked identical to each other.
 */
enum class FenceSide {
    /** Nothing observed, or the last fix was too coarse to place us. See `sideOf`. */
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
 * **The cooldown is the only rule here, deliberately.** Both triggers arrive already carrying
 * a direction — the native fence's ENTER, the in-app fence's observed outward-then-inward
 * pair — and second-guessing that direction against the recorded [FenceSide] would refuse
 * real arrivals whenever the app happened not to be running for the departure. See
 * [FenceSide].
 */
fun arrivalRefusal(
    status: GeofenceStatus,
    nowMs: Long,
    cooldownMs: Long = GeofenceStatus.ARRIVAL_COOLDOWN_MS,
): String? {
    val last = status.lastAnnouncedAtMs
    if (last > 0L && nowMs - last < cooldownMs) return "another pop-up was posted minutes ago"
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

    // Diagnosis only: "did Play Services see me leave?" is the question a drive that produced
    // no pop-up leaves behind, and nothing else in the app could answer it.
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
