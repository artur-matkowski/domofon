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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import pl.bitforge.domofon.domain.camera.AudioStatus

/**
 * Gate audio from a stream of its own, for the HTTP camera path.
 *
 * **This is the one thing the deleted `RtspAudioPlayer` did wrong, done right.** That class
 * opened a second RTSP session *to the camera*, and the camera allows exactly one: the stills
 * stream IO-errored every cycle while audio played (docs/troubleshooting.md). The rule that
 * came out of it is not "never two sessions" — it is **never two sessions to the camera**.
 * Here the pictures come from Frigate over HTTP and the sound from a go2rtc restream, which
 * is one ingest fanned out to many clients, and is the answer that entry recommended.
 *
 * There is no surface and no [OffscreenTextureReader]: video is disabled outright, so this
 * decodes audio and nothing else. That also puts it nowhere near the `nativeCreatePlanes`
 * abort, which was always about reading decoded *video* on the CPU — playback was never
 * implicated.
 *
 * Failures are deliberately quiet in the picture's terms. A dead audio stream reports itself
 * through [onStatus] and retries on its own backoff; it never touches the frame status,
 * because stills that keep arriving are not an error and the car pane must not change shape
 * over it. The phone surface renders the difference as one line of text.
 *
 * Threading: everything runs on [thread]'s looper, which is also the player's looper. [start]
 * and [close] merely post onto it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class RtspAudioSource(
    private val context: Context,
    private val url: String,
    private val onStatus: (AudioStatus) -> Unit,
) : AutoCloseable {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: ExoPlayer? = null
    private var started = false
    private var playing = false

    private val retryRunnable = Runnable { startAttempt() }

    private val watchdogRunnable = Runnable {
        // A restreamer that accepts the RTSP session but sends no audio looks exactly like
        // success from the player's side. Prolonged silence goes through the retry path
        // rather than sitting on "connecting" for the rest of the drive.
        if (started && !playing) {
            Log.w(TAG, "camera: no gate audio within ${WATCHDOG_MS / 1000}s")
            failAttempt()
        }
    }

    @Synchronized
    fun start() {
        if (thread != null) return
        val t = HandlerThread("camera-audio").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h ->
            h.post {
                started = true
                startAttempt()
            }
        }
    }

    /** Idempotent. Posts the teardown onto the player thread, which then quits itself. */
    @Synchronized
    override fun close() {
        val t = thread ?: return
        val h = handler ?: return
        thread = null
        handler = null
        h.post {
            started = false
            h.removeCallbacks(retryRunnable)
            h.removeCallbacks(watchdogRunnable)
            releaseAttempt()
            onStatus(AudioStatus.NONE)
            t.quitSafely()
        }
    }

    // --- player-thread internals --------------------------------------------------------

    private fun startAttempt() {
        val h = handler ?: return
        if (!started || player != null) return
        onStatus(AudioStatus.CONNECTING)
        playing = false

        val p = ExoPlayer.Builder(context).setLooper(h.looper).build()
        player = p
        // No surface is ever set, so a stream that carries video anyway would decode it to
        // nowhere. Disabling the track means it is not decoded at all — the point of pointing
        // at an audio-only restream in the first place.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        // handleAudioFocus = true, exactly as the RTSP path does it: a navigation prompt
        // ducks the gate audio for its duration rather than being silenced by it.
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            /* handleAudioFocus = */ true,
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying || playing) return
                playing = true
                handler?.removeCallbacks(watchdogRunnable)
                onStatus(AudioStatus.PLAYING)
            }

            override fun onPlayerError(error: PlaybackException) {
                // Never the URL, never the exception message (it can embed the URL —
                // credentials inline): the error *code* is all diagnosis needs. Log.w, not
                // Log.d: release strips the lower levels and this is a field failure.
                Log.w(TAG, "camera: gate audio error ${error.errorCodeName}")
                failAttempt()
            }
        })
        // Interleaved TCP for the same reason as the picture: loose RTP datagrams rarely
        // survive the VPN, and choppy audio is exactly what that looks like.
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()

        h.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun failAttempt() {
        val h = handler ?: return
        releaseAttempt()
        if (!started) return
        onStatus(AudioStatus.ERROR)
        h.removeCallbacks(retryRunnable)
        h.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun releaseAttempt() {
        handler?.removeCallbacks(watchdogRunnable)
        playing = false
        player?.release()
        player = null
    }

    private companion object {
        const val TAG = "Domofon"

        /** How long a stream may connect without producing sound before it counts as dead. */
        const val WATCHDOG_MS = 15_000L

        /** Backoff between reconnect attempts, matching the picture path's. */
        const val RETRY_MS = 30_000L
    }
}
