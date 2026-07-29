package pl.bitforge.domofon.ui.car

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.R
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.ui.shared.GateUiState
import pl.bitforge.domofon.ui.shared.GateViewModel

/**
 * The car screen. Note what is absent: no MQTT, no state parsing, no policy — the same
 * [GateViewModel] shape the phone UI renders drives this too. Android Auto renders only Car
 * App Library templates, so this is a pane, not QML.
 *
 * Two buttons, which is the hard maximum — a Pane takes at most two actions
 * (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`). The state-dependent one is
 * [GateUiState.primaryAction], Open or Close but never both, the same value the heads-up
 * notification derives so the two can never contradict each other. The second is the audio
 * toggle.
 *
 * **Stop used to have that second slot and no longer does.** Gate audio takes the car stereo
 * while Domofon is open, and with no way to silence it from the head unit the choice was
 * between the gate and the music for the whole drive (Artur, live testing 2026-07-29). Stop
 * is still one of the phone's three buttons, and it was always the least reachable of the
 * three anyway: it is the answer to a gate misbehaving, which is a stop-the-car problem
 * rather than a glance-and-tap one. Muting is the opposite — wanted while moving, wanted at
 * once, and worthless anywhere but here.
 *
 * The toggle writes the global `camera.audioEnabled`, the same value the phone's Settings
 * switch writes; the car does not get a mute of its own. Play's IT-1 bars *configuration*
 * from the car — brokers, credentials, locations — and a mute button is a playback control,
 * which sits beside the one-touch device control IT-1 explicitly allows.
 *
 * The arrival notification stays at one button — a driver reaching for a heads-up needs one
 * target.
 *
 * ## Why everything volatile lives in a text line or the image
 *
 * The host allows **five template pushes per task** and then, in its own words, "displays an
 * error message to the user before closing the app" — which on the head unit reads as *this
 * action is not allowed while driving*, followed by the app vanishing. A push is free only
 * when it counts as a **refresh**, and for [PaneTemplate] the comparison is exactly:
 *
 * > the template title has not changed, and the number of rows and **the title of each row**
 * > have not changed.
 *
 * Row *texts*, the pane image and the action titles are not compared at all. So the rule here
 * is: the template title and the row title are constants, and every value that moves goes in
 * a text line, the image, or an action label. That way the screen can update forever without
 * advancing the quota.
 *
 * This is a correction, not a new constraint. The previous version put [GateUiState.statusText]
 * in the row *title* on the strength of a comment claiming the whole row had to keep its
 * "strings" stable — an over-generalisation of the row-*count* fix in `f3547cf`, which put the
 * one genuinely volatile value in the one genuinely compared slot. Five status changes into a
 * drive, the host closed the app; switching to navigation and back cleared it, because
 * re-entering from the launcher resets the quota (Artur, live testing 2026-07-27).
 *
 * There is deliberately **no setup here**. Play's car app quality rules (IT-1) allow a
 * smart-home app to show device state and offer simple one-touch control while driving, and
 * explicitly disallow configuration — choosing brokers, entering credentials, picking
 * locations. Unconfigured, this screen says so and points at the phone; it does not offer to
 * fix it.
 */
class GateScreen(
    carContext: CarContext,
    private val viewModel: GateViewModel,
) : Screen(carContext) {

    init {
        observeState()
        redrawOnReturn()
    }

    /**
     * Ask for a fresh template every time this screen comes back to the front.
     *
     * `Screen.invalidate()` opens with `if (getLifecycle().getCurrentState().isAtLeast(STARTED))`
     * and does nothing otherwise — a hard rule, and invisible from the API docs. So every
     * update that landed while the host had Domofon backgrounded was dropped on the floor, and
     * nothing asked again on the way back: `onAppStart` only dispatches the lifecycle event,
     * and [uiState] is a `StateFlow` that will not re-emit a value it has already delivered.
     * The head unit therefore redrew its cached template — so opening Domofon after tapping
     * *Open gate* on a heads-up showed the gate still closed while it was visibly moving
     * (Artur, live testing 2026-07-29).
     *
     * The Screen's own lifecycle, not the Session's: it is the Screen's state that
     * `invalidate()` guards on. Free against the five-push quota — title, row count and row
     * title are constants, so this is a refresh (see the refresh rule above).
     */
    private fun redrawOnReturn() {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = invalidate()
            }
        )
    }

    /** Its own function only so the [FlowPreview] opt-in for `debounce` stays this narrow. */
    @OptIn(FlowPreview::class)
    private fun observeState() {
        // Redraw whenever the derived state or the camera frame moves — the ViewModel has
        // already folded the seven backend flows into these two.
        //
        // debounce, because the two are independent: a snapshot landing in the same moment as
        // a state change used to push two templates back to back. Now that every push is a
        // refresh this is about repaint economy rather than quota, but the host still animates
        // between them and 150 ms folds one round together unnoticeably.
        merge(viewModel.uiState, viewModel.frame)
            .debounce(INVALIDATE_DEBOUNCE_MS)
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        val state = viewModel.uiState.value

        if (!state.configComplete) {
            return MessageTemplate.Builder(carContext.getString(R.string.not_configured_car))
                .setTitle(carContext.getString(R.string.not_configured_title))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        return gatePane(state)
    }

    /**
     * The one configured layout, camera or not.
     *
     * A [androidx.car.app.model.GridTemplate] used to serve the camera-less case, with the two
     * buttons as grid cells. It is gone for two independent reasons. Its refresh rule compares
     * the template title *and each grid item's title*, and this app's were
     * `"<status> · <distance>"` and `"Open gate"`/`"Close gate"` — both moving, so every single
     * push was a step. And `GridTemplate` is not among the types the host accepts as the last
     * template of a task, so the camera-less path had no safe landing either.
     *
     * Collapsing to one template also makes the "type cannot flip mid-session" invariant true
     * for the first time: it used to switch Grid⇄Pane when the camera source changed on the
     * phone under a live session. Now only *configured at all* selects a type, and
     * `cameraConfigured` merely decides what goes in the image slot.
     */
    private fun gatePane(state: GateUiState): Template {
        val frame = viewModel.frame.value
        val image = when {
            frame != null -> CarIcon.Builder(IconCompat.createWithBitmap(frame.bitmap)).build()
            // Camera configured but nothing arriving yet — an unreachable camera degrades
            // inside the same pane rather than changing its shape.
            state.cameraConfigured -> themedIcon(R.drawable.ic_camera_off)
            // No camera at all. The image is free to change on a refresh, so the largest slot
            // on the screen carries the gate state as a picture instead of a dead placeholder.
            else -> themedIcon(gateStateIconRes(state.gateState))
        }

        // Exactly one row, always, and its title is a constant — those are the two things the
        // host compares. Everything that moves hangs off it as text (a pane row allows two
        // lines, ROW_CONSTRAINTS_PANE) and is invisible to the refresh check.
        val row = Row.Builder()
            .setTitle(ROW_TITLE)
            .addText(state.statusText)
            .apply {
                // A refused command outranks distance: the driver needs to know their tap did
                // nothing more than they need to know how far away they are. Both fit only
                // because there are two lines and the status no longer occupies one of them.
                val second = state.lastError.ifEmpty { state.homeDistance }
                if (second.isNotEmpty()) addText(second)
            }
            .build()

        val pane = Pane.Builder()
            .setImage(image)
            .addRow(row)
            .addAction(
                Action.Builder()
                    .setTitle(state.primaryAction.label)
                    .setIcon(themedIcon(primaryIconRes(state.primaryAction.action)))
                    .setOnClickListener { viewModel.send(state.primaryAction.action) }
                    .build()
            )
            .addAction(
                // Both the label and the icon say what the tap *will do*, exactly as the
                // button beside it does ("Open gate" carries the opening arrows). A speaker
                // showing the current state next to a gate button showing the next one would
                // be two conventions on one row.
                Action.Builder()
                    .setTitle(carContext.getString(audioLabelRes(state.audioEnabled)))
                    .setIcon(themedIcon(audioIconRes(state.audioEnabled)))
                    .setOnClickListener { viewModel.setAudioEnabled(!state.audioEnabled) }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    /** Which arrow belongs to the state-dependent button — the direction it will move. */
    @DrawableRes
    private fun primaryIconRes(action: String): Int =
        if (action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open

    @StringRes
    private fun audioLabelRes(audioEnabled: Boolean): Int =
        if (audioEnabled) R.string.car_audio_mute else R.string.car_audio_unmute

    @DrawableRes
    private fun audioIconRes(audioEnabled: Boolean): Int =
        if (audioEnabled) R.drawable.ic_audio_off else R.drawable.ic_audio_on

    /**
     * The gate itself as a picture, for the camera-less pane.
     *
     * Deliberately a different visual family from [primaryIconRes]: those are *action* arrows
     * on a button ("this will close it"), these are a barred panel between two posts ("this is
     * where it is"). They sit side by side on the same screen, and an arrow in both places
     * would read as the picture agreeing or arguing with the button.
     */
    @DrawableRes
    private fun gateStateIconRes(state: String): Int = when (state) {
        GatePolicy.STATE_OPENED -> R.drawable.ic_gate_state_open
        GatePolicy.STATE_CLOSED -> R.drawable.ic_gate_state_closed
        GatePolicy.STATE_OPENING, GatePolicy.STATE_CLOSING -> R.drawable.ic_gate_state_moving
        GatePolicy.STATE_STOPPED,
        GatePolicy.STATE_STUCK_OPENING,
        GatePolicy.STATE_STUCK_CLOSING,
        -> R.drawable.ic_gate_state_fault
        else -> R.drawable.ic_gate_state_unknown
    }

    /**
     * A drawable as a [CarIcon] the head unit is allowed to recolour. Every icon in this app
     * is a white silhouette — right for a notification, invisible on a light head-unit theme.
     * [CarColor.DEFAULT] hands the choice to the host, which knows whether it is currently
     * drawing dark-on-light or light-on-dark. Deliberately not applied to the camera still:
     * tinting a photograph would flatten it to a monochrome smear.
     */
    private fun themedIcon(@DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res))
            .setTint(CarColor.DEFAULT)
            .build()

    private companion object {
        /**
         * The row's title. **Must stay constant** — it is one of the three things the host
         * compares when deciding whether a push is a free refresh. See the class doc.
         */
        const val ROW_TITLE = "Gate"

        const val INVALIDATE_DEBOUNCE_MS = 150L
    }
}
