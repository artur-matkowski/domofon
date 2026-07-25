package pl.bitforge.domofon.domain.config

/**
 * Which camera rows the settings screen shows for a given [CameraSource].
 *
 * The rule lives here rather than in the fragment for the same reason every other rule does:
 * this is the only half of it a JVM test can reach. `SettingsFragment` keeps one loop that
 * assigns `isVisible` and holds no knowledge of which field belongs to which path, so adding
 * a third source later means editing this object and nothing in `ui/`.
 *
 * Note this *hides* rather than greys out. The `home.*` rows use `app:dependency`, which
 * disables — right for "this depends on that being on", wrong here: an RTSP URL is not
 * disabled while the HTTP path is selected, it is irrelevant, and a greyed-out row that
 * still shows a value reads as a bug.
 */
object CameraSettingsRows {

    /** Rows shown whichever path is selected. */
    private val COMMON = setOf(
        ConfigKeys.SOURCE,
        // Both paths use it: the poll gap on HTTP, the still-extraction cadence on RTSP.
        ConfigKeys.SNAPSHOT_SECS,
        // Both paths have sound — in-band on RTSP, a separate stream on HTTP.
        ConfigKeys.AUDIO_ENABLED,
    )

    private val RTSP_ONLY = setOf(ConfigKeys.RTSP_URL)

    private val HTTP_ONLY = setOf(ConfigKeys.SNAPSHOT_URL, ConfigKeys.AUDIO_URL)

    /** Every camera row the screen owns — the set the caller iterates when applying a rule. */
    val ALL: Set<String> = COMMON + RTSP_ONLY + HTTP_ONLY

    /** The subset of [ALL] that belongs on screen for [source]. */
    fun visible(source: CameraSource): Set<String> = when (source) {
        CameraSource.RTSP -> COMMON + RTSP_ONLY
        CameraSource.HTTP -> COMMON + HTTP_ONLY
    }
}
