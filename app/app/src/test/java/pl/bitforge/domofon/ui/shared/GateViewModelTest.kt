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
import pl.bitforge.domofon.data.camera.CameraFrame
import pl.bitforge.domofon.data.camera.CameraFrameGrabber
import pl.bitforge.domofon.domain.camera.AudioStatus
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
    private val cameraHealth = MutableStateFlow(CameraFrameGrabber.Health())
    private val frame = MutableStateFlow<CameraFrame?>(null)
    private val distance = MutableStateFlow<HomeDistanceTracker.Reading?>(null)

    /**
     * Stands in for the `ConfigStore` write. Writing straight back into [config] is what the
     * real one does the long way round — `putBoolean` lands in SharedPreferences, whose change
     * listener reparses into the flow — so the round trip is the behaviour under test.
     */
    private val setAudio: (Boolean) -> Unit = { enabled ->
        config.value = config.value.copy(camera = config.value.camera.copy(audioEnabled = enabled))
    }

    private fun TestScope.viewModel(gate: GateService) =
        GateViewModel(gate, config, cameraHealth, frame, distance, setAudio, backgroundScope)

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
        distance.value = HomeDistanceTracker.Reading(1_540f, nextFixInMs = 20_000)
        runCurrent()

        // No cadence: with the in-app fence off, the poll interval is trivia about a readout
        // rather than a fact about the arrival trigger.
        assertEquals("1.5 km · approaching home", vm.uiState.value.homeDistance)
    }

    @Test
    fun `the in-app fence adds its evaluation cadence to the distance line`() = runTest {
        config.value = completeConfig.copy(
            home = completeConfig.home.copy(radiusMeters = 2_000f, inAppFence = true),
        )
        val vm = viewModel(gate())
        distance.value = HomeDistanceTracker.Reading(1_540f, nextFixInMs = 20_000)
        runCurrent()

        // This is what the car row reports: the app's own trigger is alive and how sharp it
        // is. It says nothing about the Play Services fence, which cannot be observed.
        assertEquals("1.5 km · approaching home · next ≤20s", vm.uiState.value.homeDistance)
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
        cameraHealth.value = CameraFrameGrabber.Health(frames = CameraFrameGrabber.Status.ERROR)
        runCurrent()
        assertEquals(CameraFrameGrabber.Status.ERROR, vm.uiState.value.cameraStatus)
    }

    @Test
    fun `a dead separate audio stream gets its own line, leaving the picture alone`() = runTest {
        val vm = viewModel(gate())
        cameraHealth.value = CameraFrameGrabber.Health(
            frames = CameraFrameGrabber.Status.STREAMING,
            audio = AudioStatus.ERROR,
        )
        runCurrent()
        assertEquals("Gate audio unavailable", vm.uiState.value.audioNotice)
        // The stills are fine, and must keep saying so — this is the whole point of the
        // split: on the HTTP path the two halves fail independently.
        assertEquals(CameraFrameGrabber.Status.STREAMING, vm.uiState.value.cameraStatus)
    }

    @Test
    fun `audio riding the picture's own session reports no notice`() = runTest {
        val vm = viewModel(gate())
        cameraHealth.value = CameraFrameGrabber.Health(
            frames = CameraFrameGrabber.Status.STREAMING,
            audio = AudioStatus.NONE,
        )
        runCurrent()
        assertEquals("", vm.uiState.value.audioNotice)
    }

    @Test
    fun `the global audio setting reaches every surface`() = runTest {
        val vm = viewModel(gate())
        // Default on, so the car pane opens offering to mute rather than to unmute.
        assertTrue(vm.uiState.value.audioEnabled)

        config.value = completeConfig.copy(
            camera = completeConfig.camera.copy(audioEnabled = false),
        )
        runCurrent()
        assertFalse(vm.uiState.value.audioEnabled)
    }

    @Test
    fun `the car's mute button writes the setting the phone reads`() = runTest {
        // One lever, not a surface-local mute: the value goes out through the config store and
        // comes back through the same flow the Settings switch renders.
        val vm = viewModel(gate())

        vm.setAudioEnabled(false)
        runCurrent()
        assertFalse(vm.uiState.value.audioEnabled)
        assertFalse(config.value.camera.audioEnabled)

        vm.setAudioEnabled(true)
        runCurrent()
        assertTrue(vm.uiState.value.audioEnabled)
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
