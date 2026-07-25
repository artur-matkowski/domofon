package pl.bitforge.domofon.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import pl.bitforge.domofon.data.config.ConfigStore
import pl.bitforge.domofon.receivers.GeofenceReceiver

/**
 * The home geofence. Entering it is what surfaces the gate on the car screen.
 *
 * The position is the user's, read from the injected [ConfigStore] — never a constant in
 * this file. The feature also defaults to *off*: background location is the most intrusive
 * permission the app asks for, and grabbing it before the user has asked for the feature
 * is both rude and the single most common reason Play rejects a location declaration.
 */
class GeofenceManager(private val configStore: ConfigStore) {

    fun hasPermissions(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val background =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED &&
            background == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Registers, or tears down, to match the current settings.
     *
     * Safe to call repeatedly — on boot, after a settings change, after a permission grant.
     */
    @SuppressLint("MissingPermission") // guarded by hasPermissions()
    fun sync(context: Context) {
        val home = configStore.current.home
        if (!home.isUsable) {
            // Covers both "switched off" and "half-filled form": turning the feature off
            // has to actually stop the tracking, not just stop reacting to it.
            Log.i(TAG, "geofence not active: disabled or no home position set")
            remove(context)
            return
        }
        if (!hasPermissions(context)) {
            // Without "Allow all the time" the geofence is accepted and then never fires —
            // the #1 geofence bug (docs/modules/geo.md). Refuse loudly instead.
            Log.w(TAG, "geofence NOT registered: background location not granted")
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(home.latitude!!, home.longitude!!, home.radiusMeters)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(30_000) // fresh enough at 2 km, battery-cheap
            .build()

        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_ENTER. the original design assumed a 300-500 m radius; at the default
            // 2 km the house sits inside the fence, so an initial trigger would fire
            // "approaching home" on every app start and every reboot while parked at home.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            // Radius only, never the coordinates — that is the user's home address, and
            // logcat is readable by adb and by anything holding READ_LOGS.
            .addOnSuccessListener { Log.i(TAG, "geofence registered (r=${home.radiusMeters}m)") }
            .addOnFailureListener { Log.e(TAG, "geofence registration failed", it) }
    }

    fun remove(context: Context) {
        LocationServices.getGeofencingClient(context)
            .removeGeofences(listOf(ID))
            .addOnFailureListener { Log.w(TAG, "geofence removal failed", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            // MUTABLE: Play Services fills the transition details into this intent. It
            // names an explicit component, so only GMS can make use of it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    companion object {
        const val ID = "home"
        private const val TAG = "Domofon"
    }
}
