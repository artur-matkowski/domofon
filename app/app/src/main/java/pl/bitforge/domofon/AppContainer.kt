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
import pl.bitforge.domofon.data.location.GeofenceStatusStore
import pl.bitforge.domofon.data.location.HomeDistanceTracker
import pl.bitforge.domofon.domain.GeofenceStatus
import pl.bitforge.domofon.receivers.ArrivalFlow
import pl.bitforge.domofon.ui.notifications.GateEventNotifier
import pl.bitforge.domofon.ui.shared.GateViewModel
import pl.bitforge.domofon.ui.shared.SurfacePresence

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

    /** For the container's own callbacks, which outlive whichever surface created them. */
    private val appContext: Context = app.applicationContext

    /** Parent of every process-lifetime coroutine the container's objects launch. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val secretStore = SecretStore()

    val configStore = ConfigStore(app, secretStore)

    val gateService = GateService(
        transport = HiveMqTransport(),
        currentConfig = { configStore.current },
        scope = appScope,
    )

    /**
     * What the app has observed about its own arrival trigger — registration outcome,
     * deliveries, pop-ups. Persistent, because the native fence delivers into a process that
     * is usually dead and the arrival cooldown has to survive that.
     */
    val geofenceStatus = GeofenceStatusStore(app)

    val geofenceManager = GeofenceManager(configStore, geofenceStatus)

    /** Stateless notification renderer; *when* to post is its callers' business. */
    val gateNotifier = GateNotifier()

    /**
     * Which Domofon surfaces are in front of the user. A singleton because it is the
     * *process's* answer: the car session writes it, the phone activity writes it, and the
     * notification path — which belongs to neither — reads it.
     */
    val surfaces = SurfacePresence()

    /** The single state-change→notification collector; started once by [DomofonApp]. */
    val gateEventNotifier =
        GateEventNotifier(app, gateService, gateNotifier, surfaces, appScope)

    init {
        // Upgrade hygiene, once per process and cheap. Frames used to reach QML as a pair of
        // cache files; they now cross as data URIs and nothing writes these any more, but an
        // install upgrading from that build still has the last two gate pictures on disk and
        // nothing left that would ever delete them.
        repeat(2) { java.io.File(app.cacheDir, "camera-frame-$it.jpg").delete() }
    }

    /**
     * A per-surface camera still producer; the caller starts/stops it with its lifecycle.
     *
     * The session scope shares [appScope]'s job but runs on `Dispatchers.IO`, and is
     * deliberately not the surface's: closing an RTSP session blocks until it is really gone
     * (which is what stops a replacement racing it at the camera), and that must neither run
     * on the UI thread that called `stop()` nor be cancelled by it. It dies with the
     * container, which is what makes it deterministic in a test.
     */
    fun newCameraGrabber(context: Context) = CameraFrameGrabber(
        config = configStore.config,
        sources = CameraFrameGrabber.androidSources(context.applicationContext),
        sessions = CoroutineScope(appScope.coroutineContext + Dispatchers.IO),
    )

    /**
     * A per-surface distance readout; same ownership rule as the grabber.
     *
     * It also carries the opt-in in-app fence, which is why it is handed an arrival callback:
     * the tracker owns the readings, [ArrivalFlow] owns what an arrival *does*, and neither
     * has to know about the other. The callback is inert unless `home.inAppFence` is on.
     */
    fun newHomeDistanceTracker(context: Context) =
        HomeDistanceTracker(
            context = context.applicationContext,
            configStore = configStore,
            geofence = geofenceManager,
            status = geofenceStatus,
            appScope = appScope,
            // requireDeparture = false: this trigger *is* an observed outside→inside
            // crossing, so asking the persisted fence side the same question again would
            // only give it a second chance to say no.
            onArrival = {
                ArrivalFlow.run(appContext, GeofenceStatus.SOURCE_IN_APP, requireDeparture = false)
            },
        )

    /** One ViewModel per surface, on that surface's lifecycle scope. */
    fun newGateViewModel(
        scope: CoroutineScope,
        grabber: CameraFrameGrabber,
        tracker: HomeDistanceTracker,
    ) = GateViewModel(
        gate = gateService,
        config = configStore.config,
        cameraHealth = grabber.health,
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
