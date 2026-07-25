package pl.bitforge.domofon.ui.shared

import pl.bitforge.domofon.data.camera.CameraFrameGrabber
import pl.bitforge.domofon.domain.PrimaryAction

/**
 * Everything the presentation surfaces render, derived once.
 *
 * The phone's QML binder, the car screen and (indirectly) the notifications all consume
 * this shape, which is what keeps their wording and button logic from drifting — the
 * derivation ([GateViewModel]) runs GatePolicy exactly once per change.
 *
 * The camera frame is deliberately *not* here: data-class equality over a mutable Bitmap
 * is meaningless, and delivery differs per surface (a data URI crosses the QML bridge, a
 * CarIcon crosses the binder). It travels next to this as [GateViewModel.frame].
 */
data class GateUiState(
    /** False until a broker host is set — surfaces render their "not configured" state. */
    val configComplete: Boolean,

    /** The single status line, worded once by GatePolicy and rendered verbatim. */
    val statusText: String,

    /** Why the last command did not reach the gate; "" hides the line (20 s TTL upstream). */
    val lastError: String,

    /** The state-dependent button — Open or Close, never both. */
    val primaryAction: PrimaryAction,

    /** Raw state vocabulary ("opened", …), for consumers that key off it. */
    val gateState: String,

    /** Pre-formatted distance line; "" hides it. */
    val homeDistance: String,

    /** Whether a camera is configured at all — gates the whole camera panel/pane. */
    val cameraConfigured: Boolean,

    /** The frame pipeline's health, for placeholder wording while no frame exists. */
    val cameraStatus: CameraFrameGrabber.Status,

    /**
     * Pre-worded reason the gate is silent; "" hides the line.
     *
     * Only ever non-empty on the HTTP camera path, whose audio is a stream of its own — see
     * [pl.bitforge.domofon.domain.GatePolicy.cameraAudioNotice]. The car surface ignores it
     * deliberately: its pane must keep exactly one row between frames or the head unit dims.
     */
    val audioNotice: String,
)
