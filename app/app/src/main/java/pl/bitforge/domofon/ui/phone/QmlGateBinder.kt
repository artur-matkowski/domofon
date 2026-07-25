package pl.bitforge.domofon.ui.phone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.qtproject.example.domofon.DomofonQml.Main
import pl.bitforge.domofon.ui.shared.GateUiState
import pl.bitforge.domofon.ui.shared.GateViewModel

/**
 * The one writer of the QML property surface, and the one owner of its signal listeners.
 *
 * [render] is the *entire* Kotlin→QML mapping, called from two places with the same code
 * path: the state collector while the view is live, and [onQmlReady] once for the seed.
 * Its predecessor wrote the six properties in two hand-maintained copies (six collectors
 * in onCreate plus a seed block in onStatusChanged) — the exact shape of bug this class
 * exists to delete.
 *
 * Writes are gated on [ready] because setting a QML property before the view reports
 * `QtQmlStatus.READY` is silently dropped; the seed in [onQmlReady] is what the collector
 * missed in the meantime.
 *
 * [close] disconnects both signal listeners — they hold the enclosing activity through
 * their lambdas on the Qt side, and were previously never disconnected — and clears the
 * frame files. Owned by MainActivity, closed from its onDestroy.
 */
class QmlGateBinder(
    private val mainQml: Main,
    private val viewModel: GateViewModel,
    private val frames: FrameFileStore,
    scope: CoroutineScope,
    private val onSettingsRequested: () -> Unit,
) : AutoCloseable {

    /** True once the QML scene reported READY; property writes before that are no-ops. */
    @Volatile
    var ready = false
        private set

    private var commandListenerId = 0
    private var settingsListenerId = 0

    init {
        viewModel.uiState
            .onEach { if (ready) render(it) }
            .launchIn(scope)

        viewModel.frame
            .onEach { bitmap -> if (ready && bitmap != null) mainQml.cameraFrame = frames.write(bitmap) }
            .launchIn(scope)
    }

    /**
     * Call when the view reports `QtQmlStatus.READY`: seeds the scene with the state the
     * collectors dropped while unready, and wires the QML→Kotlin signals.
     *
     * Re-seeding on a repeated READY is harmless (it renders current state), but the
     * signal listeners are connected **once**: `QtQuickView` can re-emit READY, and a
     * second `connect…Listener` would leave the first connected — every button press then
     * sends its command twice, and the leaked listener holds the activity for good.
     */
    fun onQmlReady() {
        ready = true
        render(viewModel.uiState.value)
        viewModel.frame.value?.let { mainQml.cameraFrame = frames.write(it) }

        if (commandListenerId != 0 || settingsListenerId != 0) return
        commandListenerId = mainQml.connectCommandRequestedListener { _, action ->
            viewModel.send(action)
        }
        settingsListenerId = mainQml.connectSettingsRequestedListener { _, _ ->
            onSettingsRequested()
        }
    }

    private fun render(state: GateUiState) {
        mainQml.statusText = state.statusText
        mainQml.lastError = state.lastError
        mainQml.cameraConfigured = state.cameraConfigured
        mainQml.cameraStatus = state.cameraStatus.name.lowercase()
        mainQml.homeDistance = state.homeDistance
    }

    override fun close() {
        ready = false
        if (commandListenerId != 0) mainQml.disconnectSignalListener(commandListenerId)
        if (settingsListenerId != 0) mainQml.disconnectSignalListener(settingsListenerId)
        commandListenerId = 0
        settingsListenerId = 0
        frames.deleteAll()
    }
}
