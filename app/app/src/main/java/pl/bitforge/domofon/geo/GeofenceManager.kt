package pl.bitforge.domofon.geo

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

/**
 * The home geofence. Entering it is what surfaces the gate on the car screen.
 *
 * Coordinates are constants for now; ch. 04's settings store is where they belong once it
 * exists, alongside the RTSP URL and the broker details.
 */
object GeofenceManager {

    private const val ID = "home"
    private const val TAG = "Domofon"

    const val HOME_LAT = 0.0
    const val HOME_LON = 0.0

    /** 2 km, well above docs/08's ~150 m reliability floor. Fires ~2 min out at 60 km/h. */
    const val RADIUS_M = 2_000f

    fun hasPermissions(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val background =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED &&
            background == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // guarded by hasPermissions()
    fun register(context: Context) {
        if (!hasPermissions(context)) {
            // Without "Allow all the time" the geofence is accepted and then never fires —
            // docs/08 calls this the #1 geofence bug. Refuse loudly instead.
            Log.w(TAG, "geofence NOT registered: background location not granted")
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(HOME_LAT, HOME_LON, RADIUS_M)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(30_000) // fresh enough at 2 km, battery-cheap
            .build()

        val request = GeofencingRequest.Builder()
            // No INITIAL_TRIGGER_ENTER. docs/08 assumes a 300-500 m radius; at 2 km the
            // house sits inside the fence, so an initial trigger would fire "approaching
            // home" on every app start and every reboot while parked at home.
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        LocationServices.getGeofencingClient(context)
            .addGeofences(request, pendingIntent(context))
            .addOnSuccessListener { Log.i(TAG, "geofence registered: ${RADIUS_M}m around home") }
            .addOnFailureListener { Log.e(TAG, "geofence registration failed", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, GeofenceReceiver::class.java),
            // MUTABLE: Play Services fills the transition details into this intent.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
}
