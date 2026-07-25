package pl.bitforge.domofon.ui.shared

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bitforge.domofon.data.camera.CameraFrameGrabber
import pl.bitforge.domofon.domain.config.DomofonConfig
import pl.bitforge.domofon.data.mqtt.FakeTransport
import pl.bitforge.domofon.data.mqtt.GateService
import pl.bitforge.domofon.data.location.HomeDistanceTracker

@OptIn(ExperimentalCoroutinesApi::class)
class GateViewModelTest {

    private val transport = FakeTransport()

    private val completeConfig = DomofonConfig.EMPTY.copy(
        broker = DomofonConfig.EMPTY.broker.copy(host = "broker.local", clientId = "test"),
    )

    private val config = MutableStateFlow(completeConfig)
    private val cameraStatus = MutableStateFlow(CameraFrameGrabber.Status.IDLE)
    private val frame = MutableStateFlow<android.graphics.Bitmap?>(null)
    private val distance = MutableStateFlow<HomeDistanceTracker.Reading?>(null)

    private fun TestScope.viewModel(gate: GateService) =
        GateViewModel(gate, config, cameraStatus, frame, distance, backgroundScope)

    private fun TestScope.gate() = GateService(
        transport = transport,
        currentConfig = { config.value },
        scope = backgroundScope,
    )

    @Test
    fun `initial value is derived before any emission`() = runTest {
        config.value = DomofonConfig.EMPTY
        val vm = viewModel(gate())

        val state = vm.uiState.value
        assertFalse(state.configComplete)
        assertEquals("unknown", state.gateState)
        assertEquals("Open gate", state.primaryAction.label)
        assertEquals("", state.homeDistance)
    }

    @Test
    fun `a gate signal flows through to status text and primary action`() = runTest {
        val gate = gate()
        val vm = viewModel(gate)
        gate.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()
        h.deliver("hc12/rx/GateOpened", """{"ts":"2026-07-20T10:00:00Z"}""", retained = false)
        runCurrent()

        val state = vm.uiState.value
        assertEquals("opened", state.gateState)
        assertEquals("Gate — opened", state.statusText)
        assertEquals("close", state.primaryAction.action)
        assertTrue(state.configComplete)
    }

    @Test
    fun `distance line derives from the reading and the configured radius`() = runTest {
        config.value = completeConfig.copy(
            home = completeConfig.home.copy(radiusMeters = 2_000f),
        )
        val vm = viewModel(gate())
        distance.value = HomeDistanceTracker.Reading(1_540f)
        runCurrent()

        assertEquals("1.5 km · approaching home", vm.uiState.value.homeDistance)
    }

    @Test
    fun `camera visibility follows configuration live`() = runTest {
        val vm = viewModel(gate())
        assertFalse(vm.uiState.value.cameraConfigured)

        config.value = completeConfig.copy(
            camera = completeConfig.camera.copy(rtspUrl = "rtsp://cam/1"),
        )
        runCurrent()
        assertTrue(vm.uiState.value.cameraConfigured)
    }

    @Test
    fun `camera status is carried for placeholder wording`() = runTest {
        val vm = viewModel(gate())
        cameraStatus.value = CameraFrameGrabber.Status.ERROR
        runCurrent()
        assertEquals(CameraFrameGrabber.Status.ERROR, vm.uiState.value.cameraStatus)
    }

    @Test
    fun `send delegates to the gate service`() = runTest {
        val gate = gate()
        val vm = viewModel(gate)
        gate.acquire("phone-ui")
        transport.handles[0].connectComplete()

        vm.send("open")
        runCurrent()

        assertEquals("hc12/tx/OpenGate", transport.handles[0].published.single().topic)
    }
}
