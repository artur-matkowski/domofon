package pl.bitforge.domofon.ui.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import pl.bitforge.domofon.container
import pl.bitforge.domofon.data.mqtt.ConnectionLease

class DomofonCarAppService : CarAppService() {

    /**
     * The only access control on this service. It is exported — it has to be, the car host
     * binds it — and it carries no `android:permission`, so whatever this returns is the
     * whole security boundary.
     *
     * `ALLOW_ALL_HOSTS_VALIDATOR` therefore meant: any installed app, holding no
     * permissions at all, could bind here, complete the handshake, call `onAppCreate` to
     * force an authenticated MQTT connection to the user's broker, fetch the template, and
     * invoke the grid item's `OnClickDelegate` over the binder — **opening the gate**, with
     * nothing drawn on screen and nothing in the notification shade. It is kept for debug
     * builds only, because the Desktop Head Unit is an unknown host and the car app would
     * otherwise open and instantly close during development.
     */
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // hosts_allowlist_sample is the library's own list of the Android Auto and
            // Automotive host package/signature pairs. "Sample" is a misnomer inherited
            // from Google's example app; it is the real allowlist and the documented way
            // to build a production validator.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = CarGateSession()
}

/**
 * One projection session: owns the session-scoped surfaces (camera grabber, distance
 * tracker, its own GateViewModel) and this session's claim on the MQTT connection.
 */
class CarGateSession : Session() {

    override fun onCreateScreen(intent: Intent): Screen {
        // The session owns the frame grabber the way it owns its MQTT lease. START/STOP
        // rather than create/destroy: a backgrounded car app must not keep fetching
        // stills over the VPN for a screen nobody is looking at.
        // Carries the still *and* the gate audio (one RTSP session, gated by the Camera
        // audio setting) — hearing the gate while driving is the point of this on the car.
        val grabber = carContext.container.newCameraGrabber(carContext)

        // Geofences are Play Services' to keep, and they go away — on reboot, on app update,
        // on a force-stop. Every *activity* start re-registers them (docs/modules/geo.md
        // invariant 7), which quietly assumed the phone gets opened. A car-only session never
        // did, so a user who only ever launches this on the head unit could drive with no
        // fence at all and nothing would repair it.
        carContext.container.geofenceManager.sync(carContext.applicationContext)

        // Distance to home. Silent unless the geofence feature is on and located.
        val distanceTracker = carContext.container.newHomeDistanceTracker(carContext)

        // With the in-app fence on, this tracker *is* an arrival trigger, and an arrival
        // trigger that stops the moment the driver switches to Maps is no trigger at all —
        // which is the whole of the drive it has to cover. So it follows the session rather
        // than the screen's visibility in that case. Off, it stays a readout and keeps the
        // old rule: no location for a screen nobody is looking at.
        //
        // The grabber is never included in this. Stills are expensive, cross the VPN, and are
        // worth nothing to a backgrounded screen; location at 10 s–10 min is not comparable.
        val fenceFollowsSession = carContext.container.configStore.current.home.inAppFence
        if (fenceFollowsSession) distanceTracker.start()

        // Observer first, then acquire. Registering afterwards leaks the lease for good
        // if the host tears the session down in between — after which the connection is
        // never released. The reverse ordering hazard the old owner-count needed a flag
        // for is gone: onDestroy closing a still-null lease is a no-op, and closing the
        // same lease twice is one too.
        var lease: ConnectionLease? = null
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    grabber.start()
                    // start() is a no-op when already running, so this is safe either way.
                    distanceTracker.start()
                }
                override fun onStop(owner: LifecycleOwner) {
                    grabber.stop()
                    if (!fenceFollowsSession) distanceTracker.stop()
                }
                override fun onDestroy(owner: LifecycleOwner) {
                    // Unconditional: whatever start()ed it, the session ending is the end of
                    // it. stop() is idempotent.
                    distanceTracker.stop()
                    lease?.close()
                    lease = null
                }
            }
        )

        // An active Android Auto session is one of the three MQTT holders (docs/architecture/decisions.md D5).
        lease = carContext.container.gateService.acquire("car-session")

        val viewModel = carContext.container.newGateViewModel(lifecycleScope, grabber, distanceTracker)
        return GateScreen(carContext, viewModel)
    }
}
