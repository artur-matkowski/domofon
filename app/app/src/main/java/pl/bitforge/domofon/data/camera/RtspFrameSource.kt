package pl.bitforge.domofon.data.camera

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import pl.bitforge.domofon.domain.config.CameraFeed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stills — and, when the user wants it, audio — pulled straight out of the RTSP stream, the
 * only camera address a user should ever have to know.
 *
 * **One session carries both.** Audio was first tried as a second, separately-owned player;
 * this camera refuses the second RTSP connection — the stills stream IO-errors every cycle
 * while the audio one plays (verified on device 2026-07-24, docs/troubleshooting.md). So the audio rides this
 * same player: video decodes to the GL surface, audio to the speaker, over a single
 * connection — which is also the least this pulls over the VPN. It is gated by the
 * `camera.audioEnabled` setting and, being Kotlin, plays on the phone and in a car session
 * alike. Playing audio does not touch the CPU-frame path the `nativeCreatePlanes` abort was
 * about (docs/modules/camera.md), so it is safe here.
 *
 * ([RtspAudioSource] does open a second session, and that is not a contradiction: it points
 * at a *restreamer*, not at the camera. One ingest, many clients. Never aim both at the same
 * device.)
 *
 * This is the source the app is built around, and the default a fresh install gets. The
 * alternative HTTP path needs a restreamer someone deliberately set up; RTSP is universal,
 * the user already has that URL for their own player, and it authenticates itself. That is a
 * product decision, recorded as D3 in docs/architecture/decisions.md.
 *
 * The pixels come back through [OffscreenTextureReader] rather than an `ImageReader`, which
 * is the entire reason this is safe now — see that class, and docs/troubleshooting.md → `nativeCreatePlanes`.
 *
 * **The stream stays open while this is started.** Reconnecting per snapshot sounds tidier
 * but costs 1–3 s of RTSP handshake and keyframe wait over a VPN, every time; at a ten
 * second interval that is most of the duty cycle anyway, for worse latency and more churn.
 * What bounds the cost instead is *when* this runs: only while the phone UI is in the
 * foreground or a car session is live, exactly like the MQTT connection. If bandwidth
 * matters over the tunnel, point the setting at the camera's **substream** — a 960 px still
 * needs nothing more.
 *
 * Threading: everything below runs on [thread]'s looper, which is also the player's looper
 * and the EGL context's thread. [start] posts onto it; [close] posts and then **waits**, because
 * the caller's next act is to open a session to the same camera and this one allows exactly
 * one (docs/architecture/decisions.md → D3a).
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RtspFrameSource(
    private val context: Context,
    private val feed: CameraFeed.Rtsp,
    /**
     * Seconds-to-millis between captured stills, read per frame rather than captured: the
     * decoder produces frames continuously and this only decides which become bitmaps, so a
     * settings change can land on the very next frame without touching the session.
     */
    private val intervalMs: () -> Long,
    private val onFrame: (CameraFrame) -> Unit,
    private val onStatus: (CameraFrameGrabber.Status) -> Unit,
) : FrameSource {

    // Volatile throughout: these are written by whichever thread calls start()/close() and
    // read by the player thread, and the MessageQueue's own lock only orders what happened
    // *before* a post — which is precisely not the field writes that follow one.
    @Volatile private var thread: HandlerThread? = null
    @Volatile private var handler: Handler? = null
    @Volatile private var player: ExoPlayer? = null
    @Volatile private var reader: OffscreenTextureReader? = null
    @Volatile private var started = false

    // Player-thread only; no cross-thread reads, so plain fields.
    private var lastSnapshotAt = 0L
    private var streaming = false
    private var loggedAudioCodec = false

    private val retryRunnable = Runnable { handler?.let { startAttempt(it) } }

    private val watchdogRunnable = Runnable {
        // A camera that answers RTSP but sends no video, or a decoder that quietly refused
        // our surface, looks identical to success from the player's side. Treat prolonged
        // silence as failure so it goes through the retry path instead of hanging on
        // "connecting" for the rest of the drive.
        if (started && !streaming) {
            Log.w(TAG, "camera: no frame within ${WATCHDOG_MS / 1000}s")
            failAttempt()
        }
    }

    @Synchronized
    override fun start() {
        if (thread != null) return
        val t = HandlerThread("camera-rtsp").also { it.start() }
        thread = t
        // Publish the handler *before* posting anything that uses it. `t.looper` has already
        // blocked until the thread's loop is running, so a message posted first can be
        // dispatched before the field write lands — and the attempt would then find a null
        // handler and give up silently: no player, no watchdog, no retry, "Connecting…"
        // until the process is killed.
        val h = Handler(t.looper)
        handler = h
        h.post {
            started = true
            startAttempt(h)
        }
    }

    /**
     * Idempotent, and **blocks until the session is really gone**.
     *
     * The caller's next act is usually to open a session to the same camera, which allows
     * exactly one; returning while `player.release()` is still queued is what turned a
     * settings change into a 30 s backoff that looked like nothing happening. Bounded, so a
     * wedged player thread costs a surface teardown a moment rather than the whole app.
     */
    override fun close() {
        val t: HandlerThread
        val h: Handler
        synchronized(this) {
            t = thread ?: return
            h = handler ?: return
            thread = null
            handler = null
        }
        val released = CountDownLatch(1)
        h.post {
            started = false
            h.removeCallbacks(retryRunnable)
            h.removeCallbacks(watchdogRunnable)
            releaseAttempt()
            // No status on the way out: by the time this runs the grabber has already
            // retired this source and may have started its replacement, and a late IDLE
            // landing on top of the new session's CONNECTING strands the panel for good.
            t.quitSafely()
            released.countDown()
        }
        if (!released.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "camera: the player thread did not release within ${CLOSE_TIMEOUT_MS}ms")
        }
    }

    // --- player-thread internals --------------------------------------------------------

    private fun startAttempt(h: Handler) {
        if (!started || player != null) return
        onStatus(CameraFrameGrabber.Status.CONNECTING)
        streaming = false
        loggedAudioCodec = false
        lastSnapshotAt = 0L

        val textureReader = OffscreenTextureReader()
        if (!textureReader.setup()) {
            // This used to give up for good, on the theory that a device without an EGL
            // context has none to offer. But this is rarely the *first* context in the
            // process — Qt holds one for the whole UI — and the interesting case is the
            // second one, built moments after another was torn down. That fails
            // transiently, and "for good" meant the camera was dead until a force-stop.
            // So: an ordinary failure, on the ordinary backoff.
            Log.e(TAG, "camera: could not create the offscreen GL context")
            failAttempt()
            return
        }
        reader = textureReader
        textureReader.setOnFrameAvailableListener({ onFrameAvailable() }, h)

        val p = ExoPlayer.Builder(context).setLooper(h.looper).build()
        player = p
        // Audio rides this same session when the user wants it (a second RTSP connection is
        // what this camera refuses — see the class note). The track is disabled outright when
        // off, so a muted stream decodes no audio at all. The switch is part of the feed
        // identity, so flipping it reopens the session through the grabber.
        val audio = feed.audioEnabled
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, !audio)
            .build()
        if (audio) {
            // handleAudioFocus = true: ExoPlayer requests ordinary media focus and honours
            // transient ducking, so a navigation prompt lowers the gate audio for its
            // duration rather than being silenced by it — duck, not fight.
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        }
        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                // The camera's audio codec, logged once — the answer to "will this camera's
                // audio play" is empirical, and this records it. Format only; never the URL.
                if (loggedAudioCodec) return
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_AUDIO || !group.isSelected) continue
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            Log.i(TAG, "camera: audio codec ${group.getTrackFormat(i).sampleMimeType}")
                            loggedAudioCodec = true
                            return
                        }
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Never the URL, never the exception message (it can embed the URL —
                // credentials inline): the error *code* is all diagnosis needs.
                Log.w(TAG, "camera: playback error ${error.errorCodeName}")
                failAttempt()
            }
        })
        // Interleaved TCP: RTP-over-UDP has to cross the VPN as loose datagrams and rarely
        // survives it; over TCP the video rides the session RTSP itself already proved works.
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(feed.url))
        p.setMediaSource(source)
        p.setVideoSurface(textureReader.surface)
        p.playWhenReady = true
        p.prepare()

        h.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun onFrameAvailable() {
        val textureReader = reader ?: return
        val p = player ?: return
        val now = System.currentTimeMillis()

        // Read the interval fresh so a settings change lands on the next frame. Note the
        // decoder produces every frame regardless; `capture` only decides whether this one
        // becomes a Bitmap. Skipping the call entirely would stall the decoder — the buffer
        // has to be returned either way.
        val capture = !streaming || now - lastSnapshotAt >= intervalMs()

        val size = p.videoSize
        val bitmap = try {
            textureReader.consumeFrame(capture, size.width, size.height, CameraFrameGrabber.MAX_EDGE)
        } catch (e: Exception) {
            // One bad buffer is not a broken stream. A broken *context* is, and that shows
            // up as this failing every time — which the watchdog will eventually catch.
            Log.w(TAG, "camera: frame readback failed (${e.javaClass.simpleName})")
            null
        } ?: return

        lastSnapshotAt = now
        if (!streaming) {
            streaming = true
            handler?.removeCallbacks(watchdogRunnable)
        }
        // No source JPEG to pass on: these pixels came back from glReadPixels as ARGB, so
        // the bridge's copy is encoded on demand.
        onFrame(CameraFrame(bitmap))
        onStatus(CameraFrameGrabber.Status.STREAMING)
    }

    private fun failAttempt() {
        val h = handler ?: return
        releaseAttempt()
        if (!started) return
        onStatus(CameraFrameGrabber.Status.ERROR)
        h.removeCallbacks(retryRunnable)
        h.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun releaseAttempt() {
        handler?.removeCallbacks(watchdogRunnable)
        streaming = false
        // Player first: it must stop writing into the surface before the surface goes away.
        player?.release()
        player = null
        reader?.close()
        reader = null
    }

    private companion object {
        const val TAG = "Domofon"

        /** How long a stream may connect without producing a frame before it counts as dead. */
        const val WATCHDOG_MS = 15_000L

        /** Backoff between reconnect attempts, independent of the snapshot interval. */
        const val RETRY_MS = 30_000L

        /**
         * How long [close] waits for the player thread to let go of the session.
         *
         * `ExoPlayer.release()` on a healthy session is milliseconds; the cap is only there
         * so a wedged decoder cannot hold up a surface teardown indefinitely. Overrunning it
         * is worth a log line — it means the next open may still meet the old session.
         */
        const val CLOSE_TIMEOUT_MS = 2_000L
    }
}
