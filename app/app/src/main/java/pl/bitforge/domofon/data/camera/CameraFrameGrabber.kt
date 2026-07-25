package pl.bitforge.domofon.data.camera

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bitforge.domofon.domain.camera.AudioStatus
import pl.bitforge.domofon.domain.config.CameraFeed
import pl.bitforge.domofon.domain.config.DomofonConfig

/**
 * The camera picture, for every surface that wants one.
 *
 * Android Auto renders Car App Library templates and never QML, so live video is
 * structurally impossible on the head unit — but a `PaneTemplate` carries one large bitmap.
 * A still every few seconds is what fits, and for "is someone at the gate" it is enough. The
 * phone panel shows the same frame from the same flow.
 *
 * This class is only the contract and the routing. Two paths produce frames, and **the user
 * picks between them** — see [CameraFeed] and [androidSources]:
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
 * It takes flows and a factory rather than a `ConfigStore` and a `Context` for the reason
 * [pl.bitforge.domofon.ui.shared.GateViewModel] does: that is what makes the part of this
 * package that has no business touching Android — *when* a session exists and which one —
 * testable on the JVM. The Android half is [androidSources].
 *
 * What no source may do is read decoded video frames on the CPU. That is not style —
 * `ImageReader` + `Image.getPlanes()` is a native abort on GPU-only decoder buffers, and it
 * killed this app on every launch on the test phone. See [OffscreenTextureReader] and
 * docs/troubleshooting.md → `nativeCreatePlanes`.
 */
class CameraFrameGrabber(
    private val config: StateFlow<DomofonConfig>,
    private val sources: SourceFactory,
    /**
     * Where every open and close runs — never the caller's thread, and never cancelled by
     * [stop]: a teardown that is abandoned half-way leaves a session open at the camera,
     * which is the whole problem this class had. Outlives the surface on purpose.
     */
    private val sessions: CoroutineScope,
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

    /**
     * Where a source reports to. Handed out per session by [sink] and quietly retired with
     * it, so nothing a closed source still says can reach the flows below.
     */
    class Sink(
        val frame: (CameraFrame) -> Unit,
        val status: (Status) -> Unit,
        val audioStatus: (AudioStatus) -> Unit,
    )

    /**
     * Builds the source a [CameraFeed] asks for. The production one is [androidSources];
     * a test supplies a fake and gets the whole lifecycle without a device.
     *
     * Implementations never throw: a bad URL or an unreachable host is a [Status.ERROR] on
     * the sink, because the caller holds a lifecycle that has to stay consistent whatever
     * the configuration says.
     */
    fun interface SourceFactory {
        fun open(feed: CameraFeed, intervalMs: () -> Long, sink: Sink): FrameSource
    }

    private val _frame = MutableStateFlow<CameraFrame?>(null)

    /** Newest snapshot, downscaled for template use. Null until the first one arrives. */
    val frame: StateFlow<CameraFrame?> = _frame.asStateFlow()

    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health.asStateFlow()

    /**
     * The active source, whichever kind it is — one owned resource, one close path. Which
     * concrete source backs it is decided from [CameraFeed], and re-decided whenever it
     * changes. Only ever touched from [sessions].
     */
    private var session: FrameSource? = null

    /**
     * Bumped by every teardown; captured into each [Sink]. A source that has been closed
     * may still have a status or a frame in flight on a thread of its own — a late IDLE
     * used to overwrite the *replacement's* CONNECTING and strand the panel, and a late
     * frame would show the old camera's picture after a switch. Both are simply dropped now.
     */
    @Volatile
    private var generation = 0

    /**
     * Owned by [start]/[stop]; watches the configuration so a source change lands on a live
     * view instead of waiting for the next lifecycle bounce.
     */
    private var scope: CoroutineScope? = null

    /**
     * The tail of the session work queue. Every open and close joins the one queued before
     * it, which is what makes "close, then open" mean anything: [FrameSource.close] blocks
     * until the session is really gone, but the *caller* of [stop] is the main thread and
     * must not. Chaining here buys the ordering without the blocking.
     */
    private var pending: Job? = null

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
        // The collector runs on [sessions]' dispatcher with a job of its own: it does nothing
        // but map a flow and queue work, so it has no business picking a second dispatcher,
        // and sharing one is what makes the whole lifecycle drivable from a test.
        val started = CoroutineScope(SupervisorJob() + sessions.coroutineContext.minusKey(Job))
        scope = started
        started.launch {
            config
                .map { it.camera.feed }
                // The feed deliberately excludes the snapshot interval, so retuning cadence
                // does not land here — on RTSP that would cost a 1–3 s handshake per edit.
                .distinctUntilChanged()
                .collect { feed -> enqueue { swap(started, feed) }.join() }
        }
    }

    /**
     * Stop producing. The last frame is kept, so redisplay on return is instant.
     *
     * The teardown itself is queued rather than awaited — this runs on the main thread from
     * `onStop` — but the next [start] queues behind it, so a restart can never race the
     * session it is replacing.
     */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        // IDLE inside the queued work, not here: the source is still live until [close]
        // retires it, and a STREAMING arriving from it a moment later would overwrite an
        // IDLE set on this thread.
        enqueue {
            close()
            _health.value = Health()
        }
    }

    /** Appends to the session queue. Synchronized because [start]/[stop] call it from the UI. */
    @Synchronized
    private fun enqueue(work: suspend () -> Unit): Job {
        val previous = pending
        // Launched in [sessions], not in the collector's scope, so cancelling the collector
        // cannot abandon a teardown half-way — the `join()` at the call site only stops the
        // *collector* waiting, never the work.
        val job = sessions.launch {
            previous?.join()
            work()
        }
        pending = job
        return job
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
     * handle: a swap queued by a collector that [stop] has since cancelled must not open a
     * source nobody owns.
     */
    private fun swap(owner: CoroutineScope, feed: CameraFeed?) {
        if (!owns(owner)) return
        close()
        // A separate audio session cannot outlive the source that owned it, and the new one
        // will report its own state; leaving the old verdict up would strand "audio
        // unavailable" on screen after switching to a path that has no separate audio at all.
        _health.value = Health(
            frames = if (feed == null) Status.IDLE else Status.CONNECTING,
            audio = AudioStatus.NONE,
        )
        if (feed == null) {
            Log.i(TAG, "camera: no source configured")
            return
        }
        Log.i(TAG, "camera: opening the ${feed.kind} source")
        session = sources.open(feed, ::intervalMs, sink()).also { it.start() }
    }

    @Synchronized
    private fun owns(owner: CoroutineScope) = scope === owner

    /**
     * Closes the running source and waits for it to be gone.
     *
     * The waiting is the point. This camera allows exactly one RTSP session, so an open that
     * overlaps the close it replaces is refused — and the replacement then sits out a 30 s
     * backoff, which is indistinguishable from "changing the setting did nothing". Sources
     * whose `close` blocks say so in [FrameSource]; this runs on [sessions], which is why
     * that is affordable — the UI thread never gets here.
     */
    private fun close() {
        val closing = session ?: return
        session = null
        // Retire the sink first: whatever the source says on its way out is no longer ours.
        generation++
        closing.close()
        Log.i(TAG, "camera: source closed")
    }

    /**
     * The cadence, read at the moment a source asks rather than captured when it was built —
     * which is what keeps the interval out of [CameraFeed] and out of the restart path.
     */
    private fun intervalMs(): Long = config.value.camera.snapshotSecs * 1000L

    /**
     * A sink pinned to the current [generation]. The three writes are read-modify-writes on
     * shared state from whatever thread the source runs on — the poll coroutine, a player's
     * looper — so `update` does them atomically; `value = value.copy(…)` would let one
     * thread's status overwrite the other's, stranding a stale ERROR or losing a STREAMING.
     */
    private fun sink(): Sink {
        val mine = generation
        return Sink(
            frame = { if (mine == generation) _frame.value = it },
            status = { status -> if (mine == generation) _health.update { it.copy(frames = status) } },
            audioStatus = { status -> if (mine == generation) _health.update { it.copy(audio = status) } },
        )
    }

    companion object {
        private const val TAG = "Domofon"

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

        /**
         * The whole of "which source backs which configuration", and the only part of this
         * file that needs Android.
         *
         * Adding a path is this `when` plus a class: nothing above the façade, and nothing in
         * the lifecycle above, has to know a third one exists.
         */
        fun androidSources(context: Context) = SourceFactory { feed, intervalMs, sink ->
            when (feed) {
                is CameraFeed.Rtsp -> RtspFrameSource(
                    context = context,
                    feed = feed,
                    intervalMs = intervalMs,
                    onFrame = sink.frame,
                    onStatus = sink.status,
                )

                is CameraFeed.Http -> HttpCameraSource(
                    image = HttpFrameSource(
                        feed = feed,
                        intervalMs = intervalMs,
                        onFrame = sink.frame,
                        onStatus = sink.status,
                    ),
                    audio = if (!feed.hasAudio) null else RtspAudioSource(
                        context = context,
                        url = feed.audioUrl,
                        onStatus = sink.audioStatus,
                    ),
                )
            }
        }

        /** For the log line: which path, never which address. */
        private val CameraFeed.kind: String
            get() = when (this) {
                is CameraFeed.Rtsp -> "RTSP"
                is CameraFeed.Http -> "HTTP"
            }
    }
}
