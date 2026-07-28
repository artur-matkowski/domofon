package pl.bitforge.domofon.ui.notifications

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.data.mqtt.GateService
import pl.bitforge.domofon.domain.StateChangeAnnouncer
import pl.bitforge.domofon.ui.shared.SurfacePresence

/**
 * The single process-wide collector that turns gate-state changes into notifications.
 *
 * Exactly one, started from the Application. Its predecessor was a `GateNotifier.observe`
 * launched by *both* MainActivity and the car session — with the phone open during a car
 * session, two collectors posted the same notification for every state change.
 *
 * Being permanent is free: `gateState` only moves while some lease holds the connection
 * open, so this collector fires exactly when the per-surface ones used to.
 *
 * *Which* movements are worth a notification is [StateChangeAnnouncer]'s business, not this
 * class's. What used to be here was `.drop(1)`, and the comment claiming it "skips the state
 * a surface connects into" was simply false — it dropped one value in the life of the
 * process, so every later connection announced the state it merely *learned*. See
 * [StateChangeAnnouncer] for the full account.
 */
class GateEventNotifier(
    context: Context,
    private val gate: GateService,
    private val notifier: GateNotifier,
    private val surfaces: SurfacePresence,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    private val announcer = StateChangeAnnouncer()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        gate.gateState
            .onEach {
                val announce = announcer.shouldAnnounce(
                    state = it.state,
                    lastCommandAtMs = gate.lastCommandAtMs,
                    nowMs = System.currentTimeMillis(),
                    // Read here, not in the notifier: this class owns *when*, and the
                    // notifier stays a stateless renderer.
                    surfaceVisible = surfaces.anyVisible,
                )
                if (announce) notifier.notifyStateChange(appContext, it)
            }
            .launchIn(scope)
    }
}
