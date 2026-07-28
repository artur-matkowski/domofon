package pl.bitforge.domofon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.bitforge.domofon.domain.GeofenceStatus

/**
 * Debug-only: exercises the arrival pop-up without driving 2 km out and back.
 *
 *     adb shell am broadcast -a pl.bitforge.domofon.DEBUG_GEOFENCE \
 *       -n pl.bitforge.domofon/.receivers.DebugGeofenceTrigger
 *
 * This class exists only in `src/debug`, so it is absent from the release APK entirely —
 * not merely disabled by a `BuildConfig.DEBUG` check in shipped code.
 *
 * It is deliberately a *separate* receiver from [GeofenceReceiver] rather than an exported
 * intent-filter on it. `GeofencingEvent.fromIntent` parses unauthenticated intent extras,
 * so exporting the real receiver would let any app on the device forge a geofence ENTER —
 * choosing the exact moment a live "Open gate" button appears in front of a driver. This
 * one takes no input at all; it just calls the pop-up path.
 */
class DebugGeofenceTrigger : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i("Domofon", "debug geofence trigger")
        val pending = goAsync()
        // Claims to be the native fence, because that is what it stands in for — including
        // going through the same guard, so firing this twice reproduces the real behaviour
        // rather than bypassing it: two shots more than 30 s apart give two pop-ups, and a
        // second inside 30 s is refused because notification 1002 is still on screen.
        ArrivalFlow.run(context.applicationContext, GeofenceStatus.SOURCE_NATIVE) { pending.finish() }
    }
}
