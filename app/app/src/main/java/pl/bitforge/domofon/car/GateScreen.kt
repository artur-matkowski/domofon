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
import pl.bitforge.domofon.gate.BridgeStatus
import pl.bitforge.domofon.gate.ConnectionStatus
import pl.bitforge.domofon.gate.GateRepository
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

    override fun onGetTemplate(): Template {
        if (!ConfigStore.current.isComplete) {
            return MessageTemplate.Builder(carContext.getString(R.string.not_configured_car))
                .setTitle(carContext.getString(R.string.not_configured_title))
                .setHeaderAction(Action.APP_ICON)
                .build()
        }

        val state = GateRepository.gateState.value.state
        val primary = GateRepository.primaryAction(state)

        // Our own connection is asked about first: "connecting…" was previously shown for a
        // hard failure too, so a rejected password looked like a slow VPN for the length of
        // the drive. A real failure now names itself on the head unit.
        val connection = GateRepository.connection.value
        val title = when (connection.status) {
            ConnectionStatus.FAILED -> "Gate — ${connection.reason}"
            ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTED ->
                if (state == GateRepository.STATE_UNKNOWN) "Gate — connecting…" else "Gate — $state"
            // OFFLINE is the bridge's own LWT — a fact worth alarming about. UNKNOWN is the
            // normal opening state of a fresh (VPN) connection and must not read as an
            // outage; once retained state or a live message arrives, the title carries real
            // content.
            ConnectionStatus.CONNECTED, ConnectionStatus.DEGRADED ->
                when (GateRepository.bridgeStatus.value) {
                    BridgeStatus.OFFLINE -> "Gate — unreachable"
                    // Silence from the gate service is its own answer, and a different one
                    // from "the service is here and the gate simply has not moved". Folding
                    // the two together is what let a bridge that was not on the broker at
                    // all read as a healthy connection on both screens.
                    BridgeStatus.UNKNOWN ->
                        if (state == GateRepository.STATE_UNKNOWN) "Gate — waiting for the service"
                        else "Gate — $state"
                    BridgeStatus.ONLINE ->
                        if (state == GateRepository.STATE_UNKNOWN) "Gate — no state reported"
                        else "Gate — $state"
                }
        }

        // A refused command is worth pre-empting the title for: on the head unit there is no
        // second line to put it on, and a driver who tapped Open needs to know it did not
        // happen more than they need the state they already had.
        val error = GateRepository.lastError.value
        val heading = if (error.isEmpty()) title else "Gate — $error"

        // "" whenever the tracker has nothing (feature off, not granted, no fix yet).
        val distanceText = formatHomeDistance(distanceTracker.distance.value)

        // Same gate as the phone panel: no snapshot URL, no frame coming, and the camera
        // template would be a permanent empty placeholder on the head unit.
        return if (ConfigStore.current.camera.hasPicture) {
            cameraTemplate(heading, distanceText, primary.label, primary.action)
        } else {
            gridTemplate(heading, distanceText, primary.label, primary.action)
        }
    }

    /** The original camera-less layout: one grid cell that is the gate button. */
    private fun gridTemplate(title: String, distanceText: String, label: String, action: String): Template {
        val icon = if (action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open

        val button = GridItem.Builder()
            .setTitle(label)
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { GateRepository.sendCommand(action) }
            .build()

        // A GridTemplate has no free text row, so distance folds into the header title.
        return GridTemplate.Builder()
            .setTitle(if (distanceText.isEmpty()) title else "$title · $distanceText")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(ItemList.Builder().addItem(button).build())
            .build()
    }

    /** Camera configured: pane with the latest still and the gate button beneath it. */
    private fun cameraTemplate(title: String, distanceText: String, label: String, action: String): Template {
        val frame = grabber.frame.value
        val image =
            if (frame != null) CarIcon.Builder(IconCompat.createWithBitmap(frame)).build()
            else CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_camera_off)).build()

        // A Pane allows up to two rows; the gate line uses the first. Distance takes the
        // second when there is one, so it reads as its own line under the still rather than
        // being crammed into the title.
        val pane = Pane.Builder()
            .setImage(image)
            .addRow(Row.Builder().setTitle(title).build())
            .apply {
                if (distanceText.isNotEmpty()) addRow(Row.Builder().setTitle(distanceText).build())
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
