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
import pl.bitforge.domofon.gate.GateRepository

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
 * RTSP still every few seconds. Which template we build depends only on *configuration* —
 * never on stream health — so the type cannot flip mid-session; a dead stream degrades to
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
) : Screen(carContext) {

    init {
        // Redraw on state changes, on the service going away, on the user finishing setup
        // on the phone while the car session is already open — and on a new camera frame,
        // which arrives at most once per grabber snapshot interval, so the invalidate rate
        // stays well inside host etiquette.
        listOf(GateRepository.gateState, GateRepository.bridgeStatus, ConfigStore.config, grabber.frame)
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

        // OFFLINE is the bridge's own LWT — a fact worth alarming about. UNKNOWN is the
        // normal opening state of a fresh (VPN) connection and must not read as an outage;
        // once retained state or a live message arrives, the title carries real content.
        val title = when (GateRepository.bridgeStatus.value) {
            BridgeStatus.OFFLINE -> "Gate — unreachable"
            BridgeStatus.UNKNOWN ->
                if (state == GateRepository.STATE_UNKNOWN) "Gate — connecting…" else "Gate — $state"
            BridgeStatus.ONLINE -> "Gate — $state"
        }

        return if (ConfigStore.current.camera.isConfigured) cameraTemplate(title, primary.label, primary.action)
        else gridTemplate(title, primary.label, primary.action)
    }

    /** The original camera-less layout: one grid cell that is the gate button. */
    private fun gridTemplate(title: String, label: String, action: String): Template {
        val icon = if (action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open

        val button = GridItem.Builder()
            .setTitle(label)
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { GateRepository.sendCommand(action) }
            .build()

        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(ItemList.Builder().addItem(button).build())
            .build()
    }

    /** Camera configured: pane with the latest RTSP still and the gate button beneath it. */
    private fun cameraTemplate(title: String, label: String, action: String): Template {
        val frame = grabber.frame.value
        val image =
            if (frame != null) CarIcon.Builder(IconCompat.createWithBitmap(frame)).build()
            else CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_camera_off)).build()

        val pane = Pane.Builder()
            .setImage(image)
            .addRow(Row.Builder().setTitle(title).build())
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
