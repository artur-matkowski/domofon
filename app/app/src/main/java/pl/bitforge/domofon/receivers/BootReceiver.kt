package pl.bitforge.domofon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.bitforge.domofon.container

/**
 * Geofences do not survive a reboot, and on many OEMs they do not survive an app update
 * either; nothing re-registers them but this and an activity start.
 *
 * It re-registers directly rather than starting a service to do it. Apps targeting
 * Android 15+ cannot start a `dataSync` foreground service from a BOOT_COMPLETED
 * receiver at all, and registering a geofence is a handful of milliseconds of work.
 *
 * Not direct-boot aware, deliberately: the home position lives in credential-encrypted
 * SharedPreferences, so there is nothing to read until the first unlock anyway.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot completed"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "app updated"
            else -> return
        }
        Log.i("Domofon", "$reason — restoring geofence")
        context.container.geofenceManager.sync(context.applicationContext)
    }
}
