package pl.bitforge.domofon.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Geofences do not survive a reboot; nothing re-registers them but this. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("Domofon", "boot completed — re-registering geofence")
        GeofenceManager.register(context.applicationContext)
    }
}
