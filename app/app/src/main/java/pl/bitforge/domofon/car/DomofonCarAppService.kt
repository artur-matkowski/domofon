package pl.bitforge.domofon.car

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import pl.bitforge.domofon.gate.GateNotifier
import pl.bitforge.domofon.gate.GateRepository

class DomofonCarAppService : CarAppService() {

    /**
     * A personal, sideloaded app: both the Desktop Head Unit and a real car are "unknown"
     * hosts, and the default validator rejects them — the car app would open and instantly
     * close. Never ship this to Play with ALLOW_ALL.
     */
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // An active Android Auto session is one of the three MQTT owners (ch. 06).
            GateRepository.connect()
            GateNotifier.observe(carContext, lifecycleScope)

            lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) GateRepository.disconnect()
                }
            )
            return GateScreen(carContext)
        }
    }
}
