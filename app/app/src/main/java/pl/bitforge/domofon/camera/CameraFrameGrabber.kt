package pl.bitforge.domofon.camera

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.bitforge.domofon.config.ConfigStore

/**
 * The camera picture, for every surface that wants one.
 *
 * Android Auto renders Car App Library templates and never QML, so live video is
 * structurally impossible on the head unit — but a `PaneTemplate` carries one large bitmap.
 * A still every few seconds is what fits, and for "is someone at the gate" it is enough. The
 * phone panel shows the same frame from the same flow.
 *
 * This class is only the contract and the routing. Two things produce frames:
 *
 * - [RtspFrameSource] — **the default**, and the only one a user has to configure. One URL,
 *   the one they already own, works on every camera and authenticates itself.
 * - [HttpFrameSource] — an optional override, for a JPEG endpoint (go2rtc, Frigate, or a
 *   camera's own snapshot path) when there is a reason to prefer it. Setting a snapshot URL
 *   is what selects it.
 *
 * Keeping the sources behind this one façade is what has made three rewrites of "where does
 * a frame come from" cost nothing above this line: [pl.bitforge.domofon.MainActivity] and
 * [pl.bitforge.domofon.car.GateScreen] have never had to change with it.
 *
 * What neither source may do is read decoded video frames on the CPU. That is not style —
 * `ImageReader` + `Image.getPlanes()` is a native abort on GPU-only decoder buffers, and it
 * killed this app on every launch on the test phone. See [OffscreenTextureReader] and
 * docs/10 → `nativeCreatePlanes`.
 */
class CameraFrameGrabber(private val context: Context) {

    enum class Status {
        /** Not started, or no camera configured. */
        IDLE,
        /** Started, no picture yet on this attempt. */
        CONNECTING,
        /** Pictures are arriving; [frame] holds a current one. */
        STREAMING,
        /** The source failed; retrying. [frame] keeps the last good picture, if any. */
        ERROR,
    }

    private val _frame = MutableStateFlow<Bitmap?>(null)

    /** Newest snapshot, downscaled for template use. Null until the first one arrives. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var rtsp: RtspFrameSource? = null
    private var http: HttpFrameSource? = null

    /** Begin producing frames. No-op when no camera is configured. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (rtsp != null || http != null) return
        val camera = ConfigStore.current.camera
        when {
            // The override wins when it is set: someone who filled in a snapshot URL has a
            // reason, and silently preferring the stream would make that field look broken.
            camera.hasSnapshot ->
                http = HttpFrameSource(::emit, ::emitStatus).also { it.start() }
            camera.hasStream ->
                rtsp = RtspFrameSource(context, ::emit, ::emitStatus).also { it.start() }
            else -> _status.value = Status.IDLE
        }
    }

    /** Stop producing. The last frame is kept, so redisplay on return is instant. */
    @Synchronized
    fun stop() {
        rtsp?.stop()
        rtsp = null
        http?.stop()
        http = null
    }

    private fun emit(bitmap: Bitmap) {
        _frame.value = bitmap
    }

    private fun emitStatus(status: Status) {
        _status.value = status
    }

    companion object {
        /**
         * Longest edge of an emitted bitmap.
         *
         * The bitmap crosses the binder inside the car template bundle, where a
         * full-resolution frame courts `TransactionTooLarge`. On the RTSP path this is also
         * the size the GPU scales to during the draw, so nothing larger is ever decoded to
         * CPU memory in the first place.
         *
         * 960 (up from 640) so the `PaneTemplate` image fills a wide head-unit slot crisply
         * rather than soft — the Car App Library gives no control over layout proportions, so
         * source sharpness is the only lever on how large the camera reads. This is a
         * trade-off against the ~1 MB binder limit, not a free dial: a 960-edge ARGB frame is
         * a few MB raw, so if the head unit ever reports `TransactionTooLarge` when the
         * template is pushed, step this back down (720 is still sharper than the old 640).
         */
        const val MAX_EDGE = 960
    }
}
