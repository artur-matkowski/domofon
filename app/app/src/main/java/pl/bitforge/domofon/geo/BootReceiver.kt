package pl.bitforge.domofon.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import pl.bitforge.domofon.container

/**
 * Geofences do not survive a reboot; nothing re-registers them but this.
 *
 * It re-registers directly rather than starting a service to do it. Apps targeting
 * Android 15+ cannot start a `dataSync` foreground service from a BOOT_COMPLETED
 * receiver at all, and registering a geofence is a handful of milliseconds of work.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("Domofon", "boot completed — restoring geofence")
        context.container.geofenceManager.sync(context.applicationContext)
    }
}
