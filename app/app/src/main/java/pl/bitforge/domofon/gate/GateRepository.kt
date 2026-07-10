package pl.bitforge.domofon.gate

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

data class GateState(val state: String, val changedAt: String)

/**
 * Single source of truth for gate state and commands.
 *
 * Chapter 05 replaces the bodies of [connect], [disconnect] and [sendCommand] with a
 * HiveMQ MQTT client (subscribe `domofon/gate/state`, publish `domofon/gate/command`).
 * The public surface below must not change: MainActivity, GateScreen and GateNotifier
 * are already written against it. The one architectural rule of this project is that
 * nothing except this object ever touches MQTT.
 *
 * Until then it simulates the gate locally so every UI pipe can be exercised.
 */
object GateRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _gateState = MutableStateFlow(GateState("closed", now()))
    val gateState: StateFlow<GateState> = _gateState.asStateFlow()

    /**
     * Owner count, not a boolean: the activity, the Android Auto session and (from ch. 06)
     * the foreground service can each hold the connection. Disconnect only when the last
     * one lets go, otherwise backgrounding the phone UI would kill the car session's feed.
     */
    private var owners = 0

    @Synchronized
    fun connect() {
        owners++
        Log.i(TAG, "connect(): owners=$owners")
        if (owners == 1) {
            // ch. 05: open the MQTT connection here.
        }
    }

    @Synchronized
    fun disconnect() {
        owners = (owners - 1).coerceAtLeast(0)
        Log.i(TAG, "disconnect(): owners=$owners")
        if (owners == 0) {
            // ch. 05: close the MQTT connection here.
        }
    }

    fun sendCommand(action: String) {
        require(action in ALLOWED_ACTIONS) { "unknown action: $action" }
        Log.i(TAG, "sendCommand($action)")
        // ch. 05: publish {"action":..,"request_id":..} to domofon/gate/command.
        simulate(action)
    }

    // --- scaffolding: delete once MQTT lands (ch. 05) --------------------------------
    private var simulation: Job? = null

    private fun simulate(action: String) {
        val transitions = when (action) {
            "open" -> "opening" to "opened"
            "close" -> "closing" to "closed"
            else -> {
                publish("stopped")
                return
            }
        }
        simulation?.cancel()
        simulation = scope.launch {
            publish(transitions.first)
            delay(2_000)
            publish(transitions.second)
        }
    }

    private fun publish(state: String) {
        _gateState.value = GateState(state, now())
    }
    // ---------------------------------------------------------------------------------

    private fun now(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private val ALLOWED_ACTIONS = setOf("open", "close", "stop")
    private const val TAG = "Domofon"
}
