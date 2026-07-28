package pl.bitforge.domofon.ui.shared

/**
 * Whether a Domofon surface is in front of the user right now — the one fact the notification
 * path had no way to ask.
 *
 * `GateNotifier` was context-blind, so moving the gate from the wall button while the driver
 * had Domofon open on the head unit drew a heads-up over the very screen that was already
 * showing that state and the button to act on it (Artur, live testing 2026-07-28). Same on
 * the phone.
 *
 * **Two surfaces, not three.** The car screen and the phone app; Settings is deliberately not
 * one of them — it shows configuration, not gate state, so a notification there is still the
 * only thing telling the user the gate moved.
 *
 * Plain volatile booleans rather than a `StateFlow`: every writer is a lifecycle callback and
 * every reader is a one-shot check on the notification path, all in one process. A flow would
 * add scheduling to a question that is answered by a single load, and scheduling is exactly
 * what a race between "the host stopped us" and "post this now" does not need.
 */
class SurfacePresence {

    @Volatile
    private var car = false

    @Volatile
    private var phone = false

    /** The Android Auto session is started, i.e. Domofon is the visible app on the head unit. */
    fun carScreen(visible: Boolean) {
        car = visible
    }

    /** `MainActivity` is between `onStart` and `onStop`. */
    fun phoneScreen(visible: Boolean) {
        phone = visible
    }

    /** True while either surface is in front. The notification path's whole question. */
    val anyVisible: Boolean
        get() = car || phone
}
