package pl.bitforge.domofon.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.R
import pl.bitforge.domofon.config.ConfigStore
import pl.bitforge.domofon.gate.GateRepository

/**
 * The car screen. Note what is absent: no MQTT, no state parsing — the same
 * [GateRepository] the phone UI uses drives this too. Android Auto renders only Car App
 * Library templates, so this is a grid, not QML.
 *
 * One button, never two: at a gate you either want it open or you want it shut, and the
 * decision of which to offer belongs to [GateRepository.primaryAction] so this screen and
 * the heads-up notification can never contradict each other.
 *
 * There is deliberately **no setup here**. Play's car app quality rules (IT-1) allow a
 * smart-home app to show device state and offer simple one-touch control while driving,
 * and explicitly disallow configuration — choosing brokers, entering credentials, picking
 * locations. Unconfigured, this screen says so and points at the phone; it does not offer
 * to fix it.
 */
class GateScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Redraw on state changes, on the service going away, and on the user finishing
        // setup on the phone while the car session is already open.
        listOf(GateRepository.gateState, GateRepository.bridgeOnline, ConfigStore.config)
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
        val icon = if (primary.action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open

        val button = GridItem.Builder()
            .setTitle(primary.label)
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { GateRepository.sendCommand(primary.action) }
            .build()

        val title = if (GateRepository.bridgeOnline.value) "Gate — $state" else "Gate — unreachable"

        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(ItemList.Builder().addItem(button).build())
            .build()
    }
}
