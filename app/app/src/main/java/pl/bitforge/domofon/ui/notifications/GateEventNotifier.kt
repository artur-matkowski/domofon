package pl.bitforge.domofon.ui.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.data.mqtt.GateService

/**
 * The single process-wide collector that turns gate-state changes into notifications.
 *
 * Exactly one, started from the Application. Its predecessor was a `GateNotifier.observe`
 * launched by *both* MainActivity and the car session — with the phone open during a car
 * session, two collectors posted the same notification for every state change.
 *
 * Being permanent is free: `gateState` only moves while some lease holds the connection
 * open, so this collector fires exactly when the per-surface ones used to — and `drop(1)`
 * still skips the current value, because the state a surface connects *into* is not news.
 */
class GateEventNotifier(
    context: Context,
    private val gate: GateService,
    private val notifier: GateNotifier,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        gate.gateState
            .drop(1)
            .onEach { notifier.notifyStateChange(appContext, it) }
            .launchIn(scope)
    }
}
