package pl.bitforge.domofon.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.bitforge.domofon.BuildConfig
import pl.bitforge.domofon.gate.GateNotifier
import pl.bitforge.domofon.gate.GateRepository

/**
 * Entering the home fence surfaces the gate on the car screen.
 *
 * No foreground service, unlike docs/08: the rx topics are retained, so the current state
 * lands within a second or two of connecting and a long-lived connection buys nothing for
 * a one-shot pop-up. A live-updating service is the ch. 06 upgrade if this proves too thin.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // Debug-only: exercises the exact pop-up path without a 2 km drive. The
        // intent-filter that makes this reachable lives in src/debug/AndroidManifest.xml,
        // so release builds cannot be poked this way.
        if (BuildConfig.DEBUG && intent.action == ACTION_DEBUG_TRIGGER) {
            Log.i(TAG, "debug geofence trigger")
            popUp(app)
            return
        }

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error: ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        Log.i(TAG, "geofence ENTER — home")
        popUp(app)
    }

    private fun popUp(context: Context) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                GateRepository.connect()
                // Null when the VPN is asleep or the broker is unreachable. The pop-up says
                // so rather than not appearing at all — silence looks identical to a bug.
                val state = GateRepository.awaitState(STATE_TIMEOUT_MS)
                Log.i(TAG, "geofence pop-up with state=${state?.state ?: "unknown"}")
                GateNotifier.notifyApproaching(context, state)
            } finally {
                GateRepository.disconnect()
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DEBUG_TRIGGER = "pl.bitforge.domofon.DEBUG_GEOFENCE"

        /** goAsync() allows roughly 10 s; leave headroom to post the notification. */
        private const val STATE_TIMEOUT_MS = 6_000L
        private const val TAG = "Domofon"
    }
}
