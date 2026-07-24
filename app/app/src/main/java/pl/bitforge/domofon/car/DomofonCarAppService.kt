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
import pl.bitforge.domofon.camera.CameraFrameGrabber
import pl.bitforge.domofon.gate.GateNotifier
import pl.bitforge.domofon.gate.GateRepository

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
            // The session owns the frame grabber the way it owns its MQTT slot. START/STOP
            // rather than create/destroy: a backgrounded car app must not keep pulling
            // RTSP video over the VPN for a screen nobody is looking at.
            val grabber = CameraFrameGrabber(carContext)

            // Observer first, then connect. Registering afterwards leaks an owner slot for
            // good if the host tears the session down in between — after which the count
            // never returns to zero and the connection is never rebuilt.
            //
            // But the same window cuts the other way: onDestroy between addObserver and
            // connect() would release a slot this session never took, dropping the count
            // below what the phone UI holds and tearing down a connection still in use. The
            // flag is what makes the release match the acquisition rather than the ordering.
            var holdsConnection = false
            lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = grabber.start()
                    override fun onStop(owner: LifecycleOwner) = grabber.stop()
                    override fun onDestroy(owner: LifecycleOwner) {
                        if (!holdsConnection) return
                        holdsConnection = false
                        GateRepository.disconnect()
                    }
                }
            )

            // An active Android Auto session is one of the three MQTT owners (ch. 06).
            GateRepository.connect()
            holdsConnection = true
            GateNotifier.observe(carContext, lifecycleScope)

            return GateScreen(carContext, grabber)
        }
    }
}
