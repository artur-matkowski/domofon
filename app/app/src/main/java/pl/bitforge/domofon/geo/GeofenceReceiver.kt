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
import pl.bitforge.domofon.config.ConfigStore
import pl.bitforge.domofon.gate.GateNotifier
import pl.bitforge.domofon.gate.GateRepository

/**
 * Entering the home fence surfaces the gate on the car screen.
 *
 * No foreground service, unlike docs/08: the rx topics are retained, so the current state
 * lands within a second or two of connecting and a long-lived connection buys nothing for
 * a one-shot pop-up. Play also stopped accepting "geofencing" as a foreground-service use
 * case in August 2026, so that upgrade path is closed regardless.
 *
 * This receiver is `exported="false"` in every build type. The debug-only trigger that used
 * to live here — and the exported intent-filter it needed — moved to [DebugGeofenceTrigger]
 * in `src/debug`, because `GeofencingEvent` authenticates nothing: any app able to reach
 * this receiver could forge an ENTER and choose the moment a live "Open gate" button
 * appears in front of a driver.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext

        // Re-check the setting on every delivery: a fence registered before the user
        // switched the feature off can still be in flight inside Play Services.
        if (!ConfigStore.current.home.isUsable) {
            Log.i(TAG, "geofence event ignored: feature disabled")
            return
        }

        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "geofence event error: ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        // Confirm this is *our* fence rather than trusting whatever the extras claim.
        if (event.triggeringGeofences.orEmpty().none { it.requestId == GeofenceManager.ID }) {
            Log.w(TAG, "geofence event for an unknown fence — ignored")
            return
        }

        Log.i(TAG, "geofence ENTER")
        val pending = goAsync()
        ArrivalPopUp.run(app) { pending.finish() }
    }

    private companion object {
        const val TAG = "Domofon"
    }
}

/**
 * The arrival pop-up itself, split out so the debug trigger in `src/debug` can drive it.
 *
 * `goAsync()` is only valid inside a live `onReceive`, so each receiver keeps its own
 * `PendingResult` and hands the teardown in as [finish].
 */
internal object ArrivalPopUp {

    /** goAsync() allows roughly 10 s; leave headroom to post the notification. */
    private const val STATE_TIMEOUT_MS = 6_000L
    private const val TAG = "Domofon"

    fun run(context: Context, finish: () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Outside the try, deliberately. Inside it, a connect() that threw — a malformed
            // host reaches the HiveMQ builder as an IllegalArgumentException — would still
            // run the finally, releasing an owner slot it never took. That drives the count
            // below what the phone UI is holding, and the next disconnect() tears down a
            // connection somebody is still using, with no lifecycle event left to rebuild it.
            GateRepository.connect()
            try {
                // Null when the VPN is asleep or the broker is unreachable. The pop-up says
                // so rather than not appearing at all — silence looks identical to a bug.
                val state = GateRepository.awaitFreshState(STATE_TIMEOUT_MS)
                Log.i(TAG, "geofence pop-up with state=${state?.state ?: "unknown"}")
                GateNotifier.notifyApproaching(context, state)
            } finally {
                GateRepository.disconnect()
                finish()
            }
        }
    }
}
