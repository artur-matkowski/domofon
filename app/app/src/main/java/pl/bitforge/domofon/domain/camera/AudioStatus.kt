package pl.bitforge.domofon.domain.camera

/**
 * The health of gate audio that arrives on a stream of its own.
 *
 * Lives in `domain/` rather than beside the grabber that produces it because [GatePolicy]
 * words the user-facing line from it, and policy may not reach up into `data/`. Its sibling
 * `CameraFrameGrabber.Status` predates that need and stays where it is.
 *
 * The RTSP camera path is always [NONE], even while it is happily playing sound, and that is
 * the point rather than an omission: its audio is a track of the very session that produces
 * the picture, so its health *is* the frame status and a second opinion could only
 * contradict it. This vocabulary exists for the HTTP path, whose audio can die on its own
 * while stills keep arriving — otherwise indistinguishable from a quiet gate.
 */
enum class AudioStatus {
    /** Nothing separate to report: muted, unconfigured, or riding the picture's session. */
    NONE,

    /** A separate audio session is starting. */
    CONNECTING,

    /** A separate audio session is playing. */
    PLAYING,

    /** A separate audio session failed; retrying. Stills are unaffected. */
    ERROR,
}
