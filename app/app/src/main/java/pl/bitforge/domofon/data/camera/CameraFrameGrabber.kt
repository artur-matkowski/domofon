package pl.bitforge.domofon.data.camera

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bitforge.domofon.data.config.ConfigStore
import pl.bitforge.domofon.domain.camera.AudioStatus
import pl.bitforge.domofon.domain.config.CameraFeed

/**
 * The camera picture, for every surface that wants one.
 *
 * Android Auto renders Car App Library templates and never QML, so live video is
 * structurally impossible on the head unit — but a `PaneTemplate` carries one large bitmap.
 * A still every few seconds is what fits, and for "is someone at the gate" it is enough. The
 * phone panel shows the same frame from the same flow.
 *
 * This class is only the contract and the routing. Two paths produce frames, and **the user
 * picks between them** — see [CameraFeed]:
 *
 * - [RtspFrameSource] — the default, and the only one that works with nothing but the camera.
 *   One URL, the one they already own, carrying stills and audio in a single session.
 * - [HttpCameraSource] — polled JPEGs from a restreamer, plus a separate audio stream. Needs
 *   something (Frigate, go2rtc) deliberately set up in front of the camera, which is why it
 *   is never what a fresh install assumes.
 *
 * Selection used to be *inferred* — a non-blank snapshot URL silently outranked the stream —
 * which meant a filled-in field was the only way to express a preference and the two settings
 * could never both hold a value. It is now an explicit setting, and the resolved [CameraFeed]
 * is both what selects the source and what decides when to reopen it.
 *
 * Keeping the sources behind this one façade is what has made four rewrites of "where does a
 * frame come from" cost nothing above this line: [pl.bitforge.domofon.MainActivity] and
 * [pl.bitforge.domofon.ui.car.GateScreen] have never had to change with it.
 *
 * What no source may do is read decoded video frames on the CPU. That is not style —
 * `ImageReader` + `Image.getPlanes()` is a native abort on GPU-only decoder buffers, and it
 * killed this app on every launch on the test phone. See [OffscreenTextureReader] and
 * docs/troubleshooting.md → `nativeCreatePlanes`.
 */
class CameraFrameGrabber(
    private val context: Context,
    private val configStore: ConfigStore,
) {

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

    /** Both halves' health as one value, so consumers combine one flow rather than two. */
    data class Health(
        val frames: Status = Status.IDLE,
        val audio: AudioStatus = AudioStatus.NONE,
    )

    private val _frame = MutableStateFlow<CameraFrame?>(null)

    /** Newest snapshot, downscaled for template use. Null until the first one arrives. */
    val frame: StateFlow<CameraFrame?> = _frame.asStateFlow()

    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health.asStateFlow()

    /**
     * The active source, whichever kind it is — one owned resource, one close path. Which
     * concrete source backs it is decided from [CameraFeed], and re-decided whenever it
     * changes.
     */
    private var session: FrameSource? = null

    /**
     * Owned by [start]/[stop]; watches the configuration so a source change lands on a live
     * view instead of waiting for the next lifecycle bounce.
     */
    private var scope: CoroutineScope? = null

    /**
     * Begin producing frames, and keep the source in step with the configuration.
     *
     * Safe to call repeatedly. The collector opens the first source itself: a `StateFlow`
     * replays its current value, so the initial open and every later swap are one code path
     * rather than two that can drift.
     */
    @Synchronized
    fun start() {
        if (scope != null) return
        val started = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = started
        started.launch {
            configStore.config
                .map { it.camera.feed }
                // The feed deliberately excludes the snapshot interval, so retuning cadence
                // does not land here — on RTSP that would cost a 1–3 s handshake per edit.
                .distinctUntilChanged()
                .collect { swap(started, it) }
        }
    }

    /** Stop producing. The last frame is kept, so redisplay on return is instant. */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        session?.close()
        session = null
    }

    /**
     * Tears down the running source and brings up the one [feed] asks for.
     *
     * **The last frame survives this.** Two reasons, both learned the hard way: the panel
     * would otherwise blink to its placeholder every time the setting changed, and a null
     * bitmap changes the *shape* of the car pane mid-session, which is what makes a head unit
     * dim the whole screen (docs/troubleshooting.md → Android Auto).
     *
     * [owner] is the staleness token, the same trick the MQTT transport plays with its
     * handle. Cancelling a scope cannot interrupt a collector that is already inside this
     * method and merely waiting on the monitor, so without the identity check a [stop] could
     * complete and *then* have a stale swap open a source nobody owns.
     */
    @Synchronized
    private fun swap(owner: CoroutineScope, feed: CameraFeed?) {
        if (scope !== owner) return
        session?.close()
        session = null
        // A separate audio session cannot outlive the source that owned it, and the new one
        // will report its own state; leaving the old verdict up would strand "audio
        // unavailable" on screen after switching to a path that has no separate audio at all.
        _health.value = Health(
            frames = if (feed == null) Status.IDLE else Status.CONNECTING,
            audio = AudioStatus.NONE,
        )
        if (feed == null) return
        session = open(feed).also { it.start() }
    }

    /**
     * The whole of "which source backs which configuration".
     *
     * Adding a path is this `when` plus a class: nothing above the façade, and nothing in the
     * lifecycle above, has to know a third one exists.
     */
    private fun open(feed: CameraFeed): FrameSource = when (feed) {
        is CameraFeed.Rtsp -> RtspFrameSource(
            context = context,
            feed = feed,
            intervalMs = ::intervalMs,
            onFrame = ::emit,
            onStatus = ::emitStatus,
        )

        is CameraFeed.Http -> HttpCameraSource(
            image = HttpFrameSource(
                feed = feed,
                intervalMs = ::intervalMs,
                onFrame = ::emit,
                onStatus = ::emitStatus,
            ),
            audio = if (!feed.hasAudio) null else RtspAudioSource(
                context = context,
                url = feed.audioUrl,
                onStatus = ::emitAudioStatus,
            ),
        )
    }

    /**
     * The cadence, read at the moment a source asks rather than captured when it was built —
     * which is what keeps the interval out of [CameraFeed] and out of the restart path.
     */
    private fun intervalMs(): Long = configStore.current.camera.snapshotSecs * 1000L

    private fun emit(frame: CameraFrame) {
        _frame.value = frame
    }

    // Both halves of the HTTP path report from their own thread — the poll coroutine and the
    // audio player's looper — so these are read-modify-writes on shared state. `update` does
    // it atomically; `value = value.copy(…)` would let one thread's status overwrite the
    // other's, stranding a stale ERROR or losing a STREAMING.
    private fun emitStatus(status: Status) {
        _health.update { it.copy(frames = status) }
    }

    private fun emitAudioStatus(status: AudioStatus) {
        _health.update { it.copy(audio = status) }
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
