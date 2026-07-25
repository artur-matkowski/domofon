package pl.bitforge.domofon

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import pl.bitforge.domofon.data.camera.CameraFrameGrabber
import pl.bitforge.domofon.data.config.ConfigPreferenceDataStore
import pl.bitforge.domofon.data.config.ConfigStore
import pl.bitforge.domofon.data.config.SecretStore
import pl.bitforge.domofon.data.mqtt.GateService
import pl.bitforge.domofon.data.mqtt.HiveMqTransport
import pl.bitforge.domofon.ui.notifications.GateNotifier
import pl.bitforge.domofon.data.location.GeofenceManager
import pl.bitforge.domofon.data.location.HomeDistanceTracker
import pl.bitforge.domofon.ui.notifications.GateEventNotifier
import pl.bitforge.domofon.ui.shared.GateViewModel

/**
 * The composition root — the one place that constructs and wires the app's objects.
 * Everything else receives its dependencies through a constructor (or one of the
 * factories below) instead of reaching for a global.
 *
 * **Singletons by necessity**, built once here: [configStore] (one prefs file, one change
 * listener), [secretStore] (one Keystore alias), [gateService] (one socket, one shared
 * state machine), [geofenceManager] (one fence ID). **Per-surface** objects — camera
 * grabbers, distance trackers — come from the factories, and the surface that makes one
 * owns its lifecycle.
 *
 * Entry points the framework constructs (activities, the car service, receivers) reach
 * this through [Context.container]. In production it lives exactly as long as the process,
 * so [close] is for tests — cancelling [appScope] is what deterministically stops every
 * coroutine the container's singletons launched.
 */
class AppContainer(app: Application) : AutoCloseable {

    /** Parent of every process-lifetime coroutine the container's objects launch. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secretStore = SecretStore()

    val configStore = ConfigStore(app, secretStore)

    val gateService = GateService(
        transport = HiveMqTransport(),
        currentConfig = { configStore.current },
        scope = appScope,
    )

    val geofenceManager = GeofenceManager(configStore)

    /** Stateless notification renderer; *when* to post is its callers' business. */
    val gateNotifier = GateNotifier()

    /** The single state-change→notification collector; started once by [DomofonApp]. */
    val gateEventNotifier = GateEventNotifier(app, gateService, gateNotifier, appScope)

    /** A per-surface camera still producer; the caller starts/stops it with its lifecycle. */
    fun newCameraGrabber(context: Context) =
        CameraFrameGrabber(context.applicationContext, configStore)

    /** A per-surface distance readout; same ownership rule as the grabber. */
    fun newHomeDistanceTracker(context: Context) =
        HomeDistanceTracker(context.applicationContext, configStore, geofenceManager, appScope)

    /** One ViewModel per surface, on that surface's lifecycle scope. */
    fun newGateViewModel(
        scope: CoroutineScope,
        grabber: CameraFrameGrabber,
        tracker: HomeDistanceTracker,
    ) = GateViewModel(
        gate = gateService,
        config = configStore.config,
        cameraStatus = grabber.status,
        frame = grabber.frame,
        distance = tracker.distance,
        scope = scope,
    )

    /** The write-through backend for the XML settings screen. */
    fun newPreferenceDataStore() = ConfigPreferenceDataStore(configStore)

    override fun close() {
        appScope.cancel()
    }
}

/**
 * How framework-constructed entry points reach the container. Everything constructed *by*
 * the container gets its dependencies through the constructor instead of calling this.
 */
val Context.container: AppContainer
    get() = (applicationContext as DomofonApp).container
