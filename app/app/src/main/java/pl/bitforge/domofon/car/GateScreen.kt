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
import pl.bitforge.domofon.camera.CameraFrameGrabber
import pl.bitforge.domofon.config.ConfigStore
import pl.bitforge.domofon.gate.GateRepository
import pl.bitforge.domofon.gate.gateStatusLine
import pl.bitforge.domofon.geo.HomeDistanceTracker
import pl.bitforge.domofon.geo.formatHomeDistance

/**
 * The car screen. Note what is absent: no MQTT, no state parsing — the same
 * [GateRepository] the phone UI uses drives this too. Android Auto renders only Car App
 * Library templates, so this is a grid (or a pane, when a camera is configured), not QML.
 *
 * Two buttons: the state-dependent one — [GateRepository.primaryAction], Open or Close but
 * never both, the same call the heads-up notification makes so the two can never contradict
 * each other — and Stop, which is unconditional because a gate you want halted is a gate
 * you want halted whatever it thinks it is doing. The phone's third button is that same
 * pair plus the redundant half of Open/Close; the car cannot hold three anyway, as a Pane
 * takes at most two actions (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`). The arrival
 * notification stays at one button — a driver reaching for a heads-up needs one target.
 *
 * With a camera configured the template is a [PaneTemplate]: the pane image is the only
 * template slot that renders a large bitmap, and [CameraFrameGrabber] feeds it a fresh
 * still every few seconds. Which template we build depends only on *configuration* — never
 * on fetch health — so the type cannot flip mid-session; an unreachable camera degrades to
 * the last good frame or a placeholder icon inside the same pane.
 *
 * There is deliberately **no setup here**. Play's car app quality rules (IT-1) allow a
 * smart-home app to show device state and offer simple one-touch control while driving,
 * and explicitly disallow configuration — choosing brokers, entering credentials, picking
 * locations. Unconfigured, this screen says so and points at the phone; it does not offer
 * to fix it.
 */
class GateScreen(
    carContext: CarContext,
    private val grabber: CameraFrameGrabber,
    private val distanceTracker: HomeDistanceTracker,
) : Screen(carContext) {

    init {
        observeState()
    }

    /** Its own function only so the [FlowPreview] opt-in for `debounce` stays this narrow. */
    @OptIn(FlowPreview::class)
    private fun observeState() {
        // Redraw on state changes, on the service going away, on the user finishing setup
        // on the phone while the car session is already open — and on a new camera frame or
        // distance reading. Both arrive at most once every several seconds (the grabber's
        // snapshot interval; the tracker's ≥10 s cadence), so the invalidate rate stays well
        // inside host etiquette.
        //
        // debounce, because these seven are independent: a snapshot landing in the same
        // moment as a distance reading used to push two templates back to back. The host
        // counts non-refresh templates against a per-step quota and animates between them,
        // so a burst is both wasteful and visible; 150 ms folds one round of them together
        // without being noticeable on a button press.
        listOf(
            GateRepository.gateState,
            GateRepository.bridgeStatus,
            GateRepository.connection,
            GateRepository.lastError,
            ConfigStore.config,
            grabber.frame,
            distanceTracker.distance,
        )
            .merge()
            .debounce(INVALIDATE_DEBOUNCE_MS)
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        if (!ConfigStore.current.isComplete) {
            return MessageTemplate.Builder(carContext.getString(R.string.not_configured_car))
                .setTitle(carContext.getString(R.string.not_configured_title))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val state = GateRepository.gateState.value.state
        val primary = GateRepository.primaryAction(state)

        // The status line is derived once, in the backend, and rendered verbatim here and on
        // the phone — see [gateStatusLine] for why the wording lives in a single place.
        val statusLine = gateStatusLine(
            GateRepository.connection.value,
            GateRepository.bridgeStatus.value,
            state,
        )

        // Why the last command did not reach the gate, kept as its own string — identical to
        // the phone's error line — rather than folded into the status.
        val error = GateRepository.lastError.value

        // "" whenever the tracker has nothing (feature off, not granted, no fix yet).
        val distanceText = formatHomeDistance(distanceTracker.distance.value)

        // Same gate as the phone panel: no snapshot URL, no frame coming, and the camera
        // template would be a permanent empty placeholder on the head unit.
        return if (ConfigStore.current.camera.hasPicture) {
            cameraTemplate(statusLine, error, distanceText, primary.label, primary.action)
        } else {
            gridTemplate(statusLine, error, distanceText, primary.label, primary.action)
        }
    }

    /** The camera-less layout: the two gate buttons as grid cells. */
    private fun gridTemplate(
        statusLine: String,
        error: String,
        distanceText: String,
        label: String,
        action: String,
    ): Template {
        val primaryButton = GridItem.Builder()
            .setTitle(label)
            // IMAGE_TYPE_ICON rather than the default LARGE: only an icon is tinted, and the
            // tint is the whole point (see [themedIcon]).
            .setImage(themedIcon(primaryIconRes(action)), GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener { GateRepository.sendCommand(action) }
            .build()

        val stopButton = GridItem.Builder()
            .setTitle(STOP_LABEL)
            .setImage(themedIcon(R.drawable.ic_gate_stop), GridItem.IMAGE_TYPE_ICON)
            .setOnClickListener { GateRepository.sendCommand(STOP_ACTION) }
            .build()

        // A GridTemplate has no free text row: a refused command outranks the status — the
        // driver needs to know their tap did nothing — and distance folds into the header.
        val heading = error.ifEmpty { statusLine }
        return GridTemplate.Builder()
            .setTitle(if (distanceText.isEmpty()) heading else "$heading · $distanceText")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder().addItem(primaryButton).addItem(stopButton).build()
            )
            .build()
    }

    /** Camera configured: pane with the latest still and the gate buttons beneath it. */
    private fun cameraTemplate(
        statusLine: String,
        error: String,
        distanceText: String,
        label: String,
        action: String,
    ): Template {
        val frame = grabber.frame.value
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
            .setTitle(statusLine)
            .apply {
                // A refused command outranks distance: the driver needs to know their tap
                // did nothing more than they need to know how far away they are.
                if (error.isNotEmpty()) addText(error)
                if (distanceText.isNotEmpty()) addText(distanceText)
            }
            .build()

        val pane = Pane.Builder()
            .setImage(image)
            .addRow(row)
            .addAction(
                Action.Builder()
                    .setTitle(label)
                    .setIcon(themedIcon(primaryIconRes(action)))
                    .setOnClickListener { GateRepository.sendCommand(action) }
                    .build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(STOP_LABEL)
                    .setIcon(themedIcon(R.drawable.ic_gate_stop))
                    .setOnClickListener { GateRepository.sendCommand(STOP_ACTION) }
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
