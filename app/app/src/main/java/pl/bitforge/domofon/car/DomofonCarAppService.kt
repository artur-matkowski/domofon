package pl.bitforge.domofon.car

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
import pl.bitforge.domofon.gate.GateNotifier

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

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // The session owns the frame grabber the way it owns its MQTT lease. START/STOP
            // rather than create/destroy: a backgrounded car app must not keep fetching
            // stills over the VPN for a screen nobody is looking at.
            // Carries the still *and* the gate audio (one RTSP session, gated by the Camera
            // audio setting) — hearing the gate while driving is the point of this on the car.
            val grabber = carContext.container.newCameraGrabber(carContext)

            // Distance to home, started and stopped with the session exactly like the
            // grabber: a backgrounded car app must not keep pulling location for a screen
            // nobody is looking at. Silent unless the geofence feature is on and located.
            val distanceTracker = carContext.container.newHomeDistanceTracker(carContext)

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
                        distanceTracker.start()
                    }
                    override fun onStop(owner: LifecycleOwner) {
                        grabber.stop()
                        distanceTracker.stop()
                    }
                    override fun onDestroy(owner: LifecycleOwner) {
                        lease?.close()
                        lease = null
                    }
                }
            )

            // An active Android Auto session is one of the three MQTT holders (ch. 06).
            lease = carContext.container.gateService.acquire("car-session")
            GateNotifier.observe(carContext, lifecycleScope)

            return GateScreen(carContext, grabber, distanceTracker)
        }
    }
}
