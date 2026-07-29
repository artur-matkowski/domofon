package pl.bitforge.domofon.data.camera

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.util.Log

/**
 * Audio focus for the gate's sound: **duck other media, never stop it, and never pause
 * ourselves.**
 *
 * ## Why this exists rather than `handleAudioFocus = true`
 *
 * Both players used to hand focus to ExoPlayer with `USAGE_MEDIA` and
 * `handleAudioFocus = true`, which requests `AUDIOFOCUS_GAIN` — *permanent* focus. That is the
 * request an app makes when it is the thing the user chose to listen to, and the system
 * answers it by telling everything else to stop. So opening Domofon paused Spotify, and
 * closing it brought nothing back: a permanent loss is not something the loser retries
 * (Artur, live testing 2026-07-29). For a doorbell that talks for a few seconds it is the
 * wrong request entirely.
 *
 * The one-line version of the fix does not exist. media3's `AudioFocusManager` ends its
 * `setAudioAttributes` with
 *
 * ```
 * checkArgument(focusGain == AUDIOFOCUS_GAIN || focusGain == 0,
 *     "Automatic handling of audio focus is only available for USAGE_MEDIA and USAGE_GAME.")
 * ```
 *
 * and that method is only reached when `handleAudioFocus` is true. So switching the usage to
 * one that maps to a ducking request — `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and friends —
 * while leaving automatic handling on is an `IllegalArgumentException` at the first frame, not
 * a duck. Asking for it ourselves is the only way, and it means we own what happens when we
 * *lose* focus too.
 *
 * ## Losing focus lowers the volume; it never pauses
 *
 * The stream is live. A paused live stream does not hold its place — it accumulates a backlog
 * it can only shed by seeking, so pausing for a navigation prompt would mean coming back a
 * prompt's worth of time behind the gate. Every loss here is therefore a volume change, which
 * is also why the whole class is one setter and no player API: it works for both
 * [RtspFrameSource] and [RtspAudioSource] without either exposing its `ExoPlayer`.
 *
 * The attributes stay `USAGE_MEDIA` / `CONTENT_TYPE_SPEECH` — routing is unchanged, only the
 * *gain type* moves. Not thread-safe, and does not need to be: each instance belongs to one
 * player's Handler thread.
 */
class GateAudioFocus(
    context: Context,
    /**
     * The player's own Handler. **Not optional.** Without it the system delivers focus
     * changes on the main looper, and an `ExoPlayer` built with `setLooper(…)` throws
     * `IllegalStateException: Player is accessed on the wrong thread` the first time a
     * navigation prompt ducks us — i.e. in the car, in exactly the situation this exists for,
     * and never on a desk.
     */
    handler: Handler,
    /** Applied to the player, 0f..1f. Always called on [handler]. */
    private val setVolume: (Float) -> Unit,
) {

    private val manager = context.getSystemService(AudioManager::class.java)

    private val request: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // Ours to handle, above. Left to the system it would lower the *stream*, which is
            // the same speaker the music is coming out of.
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    // Something wants the stage for a moment — a navigation prompt, an
                    // assistant. Audible but out of the way, then straight back up.
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setVolume(DUCKED)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> setVolume(SILENT)
                    // Permanent: someone else is the media app now. Silent and out of the
                    // system's books, or we would sit in its focus stack saying nothing.
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        setVolume(SILENT)
                        abandon()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> setVolume(FULL)
                }
            }, handler)
            .build()

    private var held = false

    /**
     * Ask to be heard *over* whatever is playing. Idempotent.
     *
     * A refusal is not a failure worth propagating: the picture is the point of this app and
     * the sound is an extra, so a phone call in progress means a quiet gate, not a broken
     * camera. It stays silent rather than talking over something that outranks it.
     */
    fun request() {
        val am = manager ?: return
        if (held) return
        val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        held = granted
        setVolume(if (granted) FULL else SILENT)
        if (!granted) Log.i(TAG, "camera: gate audio refused focus; staying silent")
    }

    /** Give the stage back. Idempotent, and safe to call from a teardown that never asked. */
    fun abandon() {
        val am = manager ?: return
        if (!held) return
        held = false
        am.abandonAudioFocusRequest(request)
    }

    private companion object {
        const val TAG = "Domofon"

        const val FULL = 1f

        /** Quiet enough to sit under a nav prompt, loud enough to still tell you the gate moved. */
        const val DUCKED = 0.2f

        const val SILENT = 0f
    }
}
