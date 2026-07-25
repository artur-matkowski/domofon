package pl.bitforge.domofon.ui.shared

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pl.bitforge.domofon.data.camera.CameraFrameGrabber
import pl.bitforge.domofon.domain.config.DomofonConfig
import pl.bitforge.domofon.data.mqtt.GateService
import pl.bitforge.domofon.domain.BridgeStatus
import pl.bitforge.domofon.domain.ConnectionState
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.domain.GateState
import pl.bitforge.domofon.domain.formatHomeDistance
import pl.bitforge.domofon.data.location.HomeDistanceTracker

/**
 * Derives [GateUiState] from the shared backend flows — the one place the seven source
 * flows meet. One instance per surface (the phone activity, a car session), each with the
 * surface's own lifecycle scope; the hot state itself lives in the process-wide
 * [GateService]/ConfigStore flows, so nothing is lost when a surface goes away.
 *
 * A plain class, not `androidx.lifecycle.ViewModel`, deliberately: the dominant restart
 * mode here is a *process* restart (Qt loads once per process), which no ViewModelStore
 * survives; a car `Screen` is not a ViewModelStoreOwner at all; and manual DI makes a
 * plain constructor the simpler, testable shape.
 *
 * Inputs are flows rather than the owning objects, so a JVM test drives this with plain
 * MutableStateFlows — no Android, no fakes beyond the transport.
 */
class GateViewModel(
    private val gate: GateService,
    config: StateFlow<DomofonConfig>,
    cameraStatus: StateFlow<CameraFrameGrabber.Status>,
    /** The newest camera frame, delivered beside [uiState] — see [GateUiState] for why. */
    val frame: StateFlow<Bitmap?>,
    distance: StateFlow<HomeDistanceTracker.Reading?>,
    scope: CoroutineScope,
) {

    val uiState: StateFlow<GateUiState> =
        combine(gate.gateState, gate.bridgeStatus, gate.connection, gate.lastError, config) {
                gateState, bridge, connection, lastError, cfg ->
            Backend(gateState, bridge, connection, lastError, cfg)
        }
            .combine(cameraStatus) { backend, cam -> backend to cam }
            .combine(distance) { (backend, cam), reading -> derive(backend, cam, reading) }
            .stateIn(
                scope,
                // Eagerly: pull-based consumers (the car screen's onGetTemplate) read
                // .value between emissions and must never see a stale initial.
                SharingStarted.Eagerly,
                derive(
                    Backend(
                        gate.gateState.value,
                        gate.bridgeStatus.value,
                        gate.connection.value,
                        gate.lastError.value,
                        config.value,
                    ),
                    cameraStatus.value,
                    distance.value,
                ),
            )

    fun send(action: String) = gate.sendCommand(action)

    private data class Backend(
        val gateState: GateState,
        val bridge: BridgeStatus,
        val connection: ConnectionState,
        val lastError: String,
        val config: DomofonConfig,
    )

    private fun derive(
        backend: Backend,
        cameraStatus: CameraFrameGrabber.Status,
        reading: HomeDistanceTracker.Reading?,
    ): GateUiState {
        val state = backend.gateState.state
        return GateUiState(
            configComplete = backend.config.isComplete,
            statusText = GatePolicy.gateStatusLine(backend.connection, backend.bridge, state),
            lastError = backend.lastError,
            primaryAction = GatePolicy.primaryAction(state),
            gateState = state,
            homeDistance = formatHomeDistance(reading?.meters, backend.config.home.radiusMeters),
            cameraConfigured = backend.config.camera.hasPicture,
            cameraStatus = cameraStatus,
        )
    }
}
