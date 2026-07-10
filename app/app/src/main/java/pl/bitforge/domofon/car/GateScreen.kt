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
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.R
import pl.bitforge.domofon.gate.GateRepository

/**
 * The car screen. Note what is absent: no MQTT, no REST, no state parsing — the same
 * [GateRepository] the phone UI uses drives this too. Android Auto renders only Car App
 * Library templates, so this is a grid, not QML.
 */
class GateScreen(carContext: CarContext) : Screen(carContext) {

    init {
        // Redraw whenever the gate state changes.
        GateRepository.gateState
            .onEach { invalidate() }
            .launchIn(lifecycleScope)
    }

    override fun onGetTemplate(): Template {
        val state = GateRepository.gateState.value.state

        fun item(title: String, icon: Int, action: String) = GridItem.Builder()
            .setTitle(title)
            .setImage(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build())
            .setOnClickListener { GateRepository.sendCommand(action) }
            .build()

        return GridTemplate.Builder()
            .setTitle("Gate — $state")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(
                ItemList.Builder()
                    .addItem(item("Open", R.drawable.ic_gate_open, "open"))
                    .addItem(item("Close", R.drawable.ic_gate_close, "close"))
                    .addItem(item("Stop", R.drawable.ic_gate_stop, "stop"))
                    .build()
            )
            .build()
    }
}
