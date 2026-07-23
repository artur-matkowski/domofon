package pl.bitforge.domofon.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only: exercises the arrival pop-up without driving 2 km out and back.
 *
 *     adb shell am broadcast -a pl.bitforge.domofon.DEBUG_GEOFENCE \
 *       -n pl.bitforge.domofon/.geo.DebugGeofenceTrigger
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
        ArrivalPopUp.run(context.applicationContext) { pending.finish() }
    }
}
