package pl.bitforge.domofon.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.R
import pl.bitforge.domofon.gate.GateRepository

/**
 * The car screen. Note what is absent: no MQTT, no state parsing — the same
 * [GateRepository] the phone UI uses drives this too. Android Auto renders only Car App
 * Library templates, so this is a grid, not QML.
 *
 * One button, never two: at a gate you either want it open or you want it shut, and the
 * decision of which to offer belongs to [GateRepository.primaryAction] so this screen and
 * the heads-up notification can never contradict each other.
 */
class GateScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Redraw on state changes and on the service going away — the title shows both.
        listOf(GateRepository.gateState, GateRepository.bridgeOnline)
            .merge()
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
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
