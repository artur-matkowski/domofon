package pl.bitforge.domofon.car

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.R
import pl.bitforge.domofon.ui.shared.GateUiState
import pl.bitforge.domofon.ui.shared.GateViewModel

/**
 * The car screen. Note what is absent: no MQTT, no state parsing, no policy — the same
 * [GateViewModel] shape the phone UI renders drives this too. Android Auto renders only
 * Car App Library templates, so this is a grid (or a pane, when a camera is configured),
 * not QML.
 *
 * Two buttons: the state-dependent one — [GateUiState.primaryAction], Open or Close but
 * never both, the same value the heads-up notification derives so the two can never
 * contradict each other — and Stop, which is unconditional because a gate you want halted
 * is a gate you want halted whatever it thinks it is doing. The phone's third button is
 * that same pair plus the redundant half of Open/Close; the car cannot hold three anyway,
 * as a Pane takes at most two actions (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`).
 * The arrival notification stays at one button — a driver reaching for a heads-up needs
 * one target.
 *
 * With a camera configured the template is a [PaneTemplate]: the pane image is the only
 * template slot that renders a large bitmap, and the session's camera grabber feeds it a
 * fresh still every few seconds. Which template we build depends only on *configuration* —
 * never on fetch health — so the type cannot flip mid-session; an unreachable camera
 * degrades to the last good frame or a placeholder icon inside the same pane.
 *
 * There is deliberately **no setup here**. Play's car app quality rules (IT-1) allow a
 * smart-home app to show device state and offer simple one-touch control while driving,
 * and explicitly disallow configuration — choosing brokers, entering credentials, picking
 * locations. Unconfigured, this screen says so and points at the phone; it does not offer
 * to fix it.
 */
class GateScreen(
    carContext: CarContext,
    private val viewModel: GateViewModel,
) : Screen(carContext) {

    init {
        observeState()
    }

    /** Its own function only so the [FlowPreview] opt-in for `debounce` stays this narrow. */
    @OptIn(FlowPreview::class)
    private fun observeState() {
        // Redraw whenever the derived state or the camera frame moves — the ViewModel has
        // already folded the seven backend flows into these two. Both are naturally slow
        // (state changes; the grabber's snapshot cadence), so the invalidate rate stays
        // well inside host etiquette.
        //
        // debounce, because the two are independent: a snapshot landing in the same moment
        // as a state change used to push two templates back to back. The host counts
        // non-refresh templates against a per-step quota and animates between them, so a
        // burst is both wasteful and visible; 150 ms folds one round of them together
        // without being noticeable on a button press.
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

        // Same gate as the phone panel: no camera configured, no frame coming, and the
        // camera template would be a permanent empty placeholder on the head unit.
        return if (state.cameraConfigured) {
            cameraTemplate(state)
        } else {
            gridTemplate(state)
        }
    }

    /** The camera-less layout: the two gate buttons as grid cells. */
    private fun gridTemplate(state: GateUiState): Template {
        val primaryButton = GridItem.Builder()
            .setTitle(state.primaryAction.label)
            // IMAGE_TYPE_ICON rather than the default LARGE: only an icon is tinted, and the
            // tint is the whole point (see [themedIcon]).
            .setImage(themedIcon(primaryIconRes(state.primaryAction.action)), GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener { viewModel.send(state.primaryAction.action) }
            .build()

        val stopButton = GridItem.Builder()
            .setTitle(STOP_LABEL)
            .setImage(themedIcon(R.drawable.ic_gate_stop), GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener { viewModel.send(STOP_ACTION) }
            .build()

        // A GridTemplate has no free text row: a refused command outranks the status — the
        // driver needs to know their tap did nothing — and distance folds into the header.
        val heading = state.lastError.ifEmpty { state.statusText }
        return GridTemplate.Builder()
            .setTitle(if (state.homeDistance.isEmpty()) heading else "$heading · ${state.homeDistance}")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder().addItem(primaryButton).addItem(stopButton).build()
            )
            .build()
    }

    /** Camera configured: pane with the latest still and the gate buttons beneath it. */
    private fun cameraTemplate(state: GateUiState): Template {
        val frame = viewModel.frame.value
        val image =
            if (frame != null) CarIcon.Builder(IconCompat.createWithBitmap(frame)).build()
            else themedIcon(R.drawable.ic_camera_off)

        // Exactly one row, always — the shape of this template must not change between
        // snapshots. The host only updates a pane *in place* when the new template counts as
        // a refresh of the last one, which turns on the rows: change their number or their
        // strings and you get a screen transition instead, and the whole head unit dims and
        // comes back. That is what the old rotating spinner in this row's title bought, once
        // every snapshot interval. So the row carries the status as its title and hangs the
        // error and the distance off it as text lines (a pane row allows two), leaving the
        // fresh bitmap as the only difference between one snapshot and the next.
        val row = Row.Builder()
            .setTitle(state.statusText)
            .apply {
                // A refused command outranks distance: the driver needs to know their tap
                // did nothing more than they need to know how far away they are.
                if (state.lastError.isNotEmpty()) addText(state.lastError)
                if (state.homeDistance.isNotEmpty()) addText(state.homeDistance)
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
                Action.Builder()
                    .setTitle(STOP_LABEL)
                    .setIcon(themedIcon(R.drawable.ic_gate_stop))
                    .setOnClickListener { viewModel.send(STOP_ACTION) }
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

    /**
     * A drawable as a [CarIcon] the head unit is allowed to recolour. Every icon in this app
     * is a white silhouette — right for a notification, invisible on a light head-unit
     * theme. [CarColor.DEFAULT] hands the choice to the host, which knows whether it is
     * currently drawing dark-on-light or light-on-dark. Deliberately not applied to the
     * camera still: tinting a photograph would flatten it to a monochrome smear.
     */
    private fun themedIcon(@DrawableRes res: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, res))
            .setTint(CarColor.DEFAULT)
            .build()

    private companion object {
        const val STOP_LABEL = "Stop"
        const val STOP_ACTION = "stop"
        const val INVALIDATE_DEBOUNCE_MS = 150L
    }
}
