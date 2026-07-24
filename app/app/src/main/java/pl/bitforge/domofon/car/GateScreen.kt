package pl.bitforge.domofon.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
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
 * One button, never two: at a gate you either want it open or you want it shut, and the
 * decision of which to offer belongs to [GateRepository.primaryAction] so this screen and
 * the heads-up notification can never contradict each other.
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
        // Redraw on state changes, on the service going away, on the user finishing setup
        // on the phone while the car session is already open — and on a new camera frame or
        // distance reading. Both arrive at most once every several seconds (the grabber's
        // snapshot interval; the tracker's ≥10 s cadence), so the invalidate rate stays well
        // inside host etiquette.
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
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    // Rotating tick appended to the pane's first row so its text changes every snapshot. The
    // Android Auto host only repaints the pane image when a row's text differs; a fresh
    // bitmap on an otherwise-identical template is dropped as a no-op refresh, which is what
    // froze the still. This is the car-side analogue of the phone's changing ?v= image URL.
    private val spinner = charArrayOf('-', '/', '|', '\\')
    private var spinnerTick = 0

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

    /** The original camera-less layout: one grid cell that is the gate button. */
    private fun gridTemplate(
        statusLine: String,
        error: String,
        distanceText: String,
        label: String,
        action: String,
    ): Template {
        val icon = if (action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open

        val button = GridItem.Builder()
            .setTitle(label)
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { GateRepository.sendCommand(action) }
            .build()

        // A GridTemplate has no free text row: a refused command outranks the status — the
        // driver needs to know their tap did nothing — and distance folds into the header.
        val heading = error.ifEmpty { statusLine }
        return GridTemplate.Builder()
            .setTitle(if (distanceText.isEmpty()) heading else "$heading · $distanceText")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(ItemList.Builder().addItem(button).build())
            .build()
    }

    /** Camera configured: pane with the latest still and the gate button beneath it. */
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
            else CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_camera_off)).build()

        val spin = spinner[spinnerTick++ % spinner.size]

        // A Pane allows up to two rows. The first always carries the status line — and the
        // spinner, so its text changes every snapshot and the host actually repaints the
        // still (see [spinner]). The second takes the error when there is one — a refused
        // command outranks distance — otherwise the distance line.
        val second = error.ifEmpty { distanceText }
        val pane = Pane.Builder()
            .setImage(image)
            .addRow(Row.Builder().setTitle("$statusLine $spin").build())
            .apply {
                if (second.isNotEmpty()) addRow(Row.Builder().setTitle(second).build())
            }
            .addAction(
                Action.Builder()
                    .setTitle(label)
                    .setOnClickListener { GateRepository.sendCommand(action) }
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle(carContext.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }
}
