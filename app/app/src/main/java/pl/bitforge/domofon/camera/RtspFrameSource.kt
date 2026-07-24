package pl.bitforge.domofon.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import pl.bitforge.domofon.config.ConfigStore

/**
 * Stills pulled straight out of the RTSP stream — the only camera address a user should
 * ever have to know.
 *
 * This is a deliberate return to the source the app started with, after an HTTP snapshot
 * detour. The detour worked, but it made the app *deployment-shaped*: the gate camera here
 * answers snapshots only at `/ISAPI/Streaming/channels/101/picture` (Hikvision) and only
 * with Digest auth, so a published app would have to either ship a table of vendor paths or
 * require the user to run a restreamer. Neither is something a stranger installing this from
 * Play can do. RTSP is universal, the user already has that URL for their own player, and it
 * authenticates itself.
 *
 * The pixels come back through [OffscreenTextureReader] rather than an `ImageReader`, which
 * is the entire reason this is safe now — see that class, and docs/10 → `nativeCreatePlanes`.
 *
 * **The stream stays open while this is started.** Reconnecting per snapshot sounds tidier
 * but costs 1–3 s of RTSP handshake and keyframe wait over a VPN, every time; at a ten
 * second interval that is most of the duty cycle anyway, for worse latency and more churn.
 * What bounds the cost instead is *when* this runs: only while the phone UI is in the
 * foreground or a car session is live, exactly like the MQTT connection. If bandwidth
 * matters over the tunnel, point the setting at the camera's **substream** (on this
 * Hikvision, channel 102) — a 640 px still needs nothing more.
 *
 * Threading: everything below runs on [thread]'s looper, which is also the player's looper
 * and the EGL context's thread. [start] and [stop] merely post onto it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RtspFrameSource(
    private val context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (CameraFrameGrabber.Status) -> Unit,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: ExoPlayer? = null
    private var reader: OffscreenTextureReader? = null
    private var started = false
    private var lastSnapshotAt = 0L
    private var streaming = false

    private val retryRunnable = Runnable { startAttempt() }

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
    fun start() {
        if (thread != null) return
        val t = HandlerThread("camera-rtsp").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h ->
            h.post {
                started = true
                startAttempt()
            }
        }
    }

    @Synchronized
    fun stop() {
        val t = thread ?: return
        val h = handler ?: return
        thread = null
        handler = null
        h.post {
            started = false
            h.removeCallbacks(retryRunnable)
            h.removeCallbacks(watchdogRunnable)
            releaseAttempt()
            onStatus(CameraFrameGrabber.Status.IDLE)
            t.quitSafely()
        }
    }

    // --- player-thread internals --------------------------------------------------------

    private fun startAttempt() {
        val h = handler ?: return
        if (!started || player != null) return
        val url = ConfigStore.current.camera.rtspUrl
        if (url.isBlank()) {
            onStatus(CameraFrameGrabber.Status.IDLE)
            return
        }
        onStatus(CameraFrameGrabber.Status.CONNECTING)
        streaming = false
        lastSnapshotAt = 0L

        val textureReader = OffscreenTextureReader()
        if (!textureReader.setup()) {
            // No EGL context means no frames on this device, ever — retrying would spin
            // against the same wall. Say so once and stop.
            Log.e(TAG, "camera: could not create the offscreen GL context")
            onStatus(CameraFrameGrabber.Status.ERROR)
            return
        }
        reader = textureReader
        textureReader.setOnFrameAvailableListener({ onFrameAvailable() }, h)

        val p = ExoPlayer.Builder(context).setLooper(h.looper).build()
        player = p
        // Audio is decoded for nothing here. When live audio arrives (ch. 04 §2) it will be
        // a second, separately-owned player: this one starts and stops with a *screen*, and
        // an intercom's audio should not.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.addListener(object : Player.Listener {
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
            .createMediaSource(MediaItem.fromUri(url))
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
        val snapshotMs = ConfigStore.current.camera.snapshotSecs * 1000L
        val capture = !streaming || now - lastSnapshotAt >= snapshotMs

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
        onFrame(bitmap)
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
        reader?.release()
        reader = null
    }

    private companion object {
        const val TAG = "Domofon"

        /** How long a stream may connect without producing a frame before it counts as dead. */
        const val WATCHDOG_MS = 15_000L

        /** Backoff between reconnect attempts, independent of the snapshot interval. */
        const val RETRY_MS = 30_000L
    }
}
