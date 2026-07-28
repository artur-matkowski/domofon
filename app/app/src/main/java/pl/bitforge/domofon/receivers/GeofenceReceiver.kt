package pl.bitforge.domofon.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.bitforge.domofon.container
import pl.bitforge.domofon.data.location.GeofenceManager
import pl.bitforge.domofon.domain.FenceSide
import pl.bitforge.domofon.domain.GeofenceStatus
import pl.bitforge.domofon.domain.arrivalRefusal

/**
 * Entering the home fence surfaces the gate on the car screen; leaving it is recorded and
 * does nothing else.
 *
 * EXIT is registered ([GeofenceManager]) purely so the Settings row can say whether Play
 * Services saw us leave. It gates nothing: the ENTER's direction is the trigger, and making
 * it conditional on having separately observed the departure would refuse a real arrival
 * whenever the app was not running for it (Artur, 2026-07-28). See
 * [FenceSide][pl.bitforge.domofon.domain.FenceSide].
 *
 * No foreground service, unlike the original geofence design: the rx topics are retained, so the current state
 * lands within a second or two of connecting and a long-lived connection buys nothing for
 * a one-shot pop-up. Play also stopped accepting "geofencing" as a foreground-service use
 * case in August 2026, so that upgrade path is closed regardless.
 *
 * This receiver is `exported="false"` in every build type. The debug-only trigger that used
 * to live here — and the exported intent-filter it needed — moved to [DebugGeofenceTrigger]
 * in `src/debug`, because `GeofencingEvent` authenticates nothing: any app able to reach
 * this receiver could forge an ENTER and choose the moment a live "Open gate" button
 * appears in front of a driver.
 *
 * Every way out of here now leaves a trace in
 * [GeofenceStatusStore][pl.bitforge.domofon.data.location.GeofenceStatusStore]. A delivery
 * that arrives and is discarded used to look exactly like one that never arrived, and those
 * two have completely different fixes.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val status = app.container.geofenceStatus

        // Re-check the setting on every delivery: a fence registered before the user
        // switched the feature off can still be in flight inside Play Services.
        if (!app.container.configStore.current.home.isUsable) {
            Log.i(TAG, "geofence event ignored: feature disabled")
            status.recordRejection("feature disabled")
            return
        }

        val event = GeofencingEvent.fromIntent(intent)
        if (event == null) {
            status.recordRejection("unparseable event")
            return
        }
        if (event.hasError()) {
            Log.w(TAG, "geofence event error: ${event.errorCode}")
            status.recordRejection("error ${event.errorCode}")
            return
        }
        val transition = event.geofenceTransition
        if (transition != Geofence.GEOFENCE_TRANSITION_ENTER &&
            transition != Geofence.GEOFENCE_TRANSITION_EXIT
        ) {
            status.recordRejection("transition $transition")
            return
        }

        // Confirm this is *our* fence rather than trusting whatever the extras claim.
        if (event.triggeringGeofences.orEmpty().none { it.requestId == GeofenceManager.ID }) {
            Log.w(TAG, "geofence event for an unknown fence — ignored")
            status.recordRejection("unknown fence id")
            return
        }

        // Recorded, and that is all it does. It is not an arrival and never pops anything up;
        // it exists so a drive can be read back afterwards as "Play Services saw me leave and
        // then said nothing", which is a different fault from "it never delivered at all".
        if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.i(TAG, "geofence EXIT")
            status.recordSide(FenceSide.OUTSIDE)
            return
        }

        Log.i(TAG, "geofence ENTER")
        // Recorded before the flow runs, and separately from the pop-up: "Play Services
        // delivered" and "a pop-up appeared" are the two facts that have to be told apart.
        status.recordNativeEnter()

        val pending = goAsync()
        ArrivalFlow.run(app, GeofenceStatus.SOURCE_NATIVE) { pending.finish() }
    }

    private companion object {
        const val TAG = "Domofon"
    }
}

/**
 * The arrival pop-up itself, shared by every trigger that can claim an arrival: this
 * receiver, the debug trigger in `src/debug`, and the opt-in in-app fence in
 * [HomeDistanceTracker][pl.bitforge.domofon.data.location.HomeDistanceTracker].
 *
 * `goAsync()` is only valid inside a live `onReceive`, so each receiver keeps its own
 * `PendingResult` and hands the teardown in as [finish]. Callers that are not receivers pass
 * a no-op — the budget still applies, which is no bad thing for a flow that holds an MQTT
 * lease.
 */
internal object ArrivalFlow {

    /** goAsync() allows roughly 10 s; leave headroom to post the notification. */
    private const val STATE_TIMEOUT_MS = 6_000L

    /** Hard bound on the whole arrival flow, inside the goAsync budget with headroom. */
    private const val RECEIVER_BUDGET_MS = 9_500L
    private const val TAG = "Domofon"

    /**
     * @param source which trigger claimed the arrival — [GeofenceStatus.SOURCE_NATIVE] or
     *   [GeofenceStatus.SOURCE_IN_APP]. Recorded, so a drive can be read afterwards as
     *   "the native fence never fired and the in-app one carried it", which is the diagnosis
     *   the failing drive could not produce.
     */
    fun run(context: Context, source: String, finish: () -> Unit = {}) {
        // The app-wide scope, not an ad-hoc one — the old CoroutineScope created here was
        // never cancelled. The outer timeout hard-bounds the whole flow inside goAsync's
        // ~10 s budget.
        val container = context.container
        val status = container.geofenceStatus

        // One clock for every decision and every record below. `recordNativeEnter()` has just
        // stamped itself from the same clock, and the Settings row only reports a rejection
        // that is *newer* than the delivery — read twice, the two can land on the same
        // millisecond and the reason disappears.
        val now = System.currentTimeMillis() + 1

        // Nothing to announce to someone already looking at it: the car screen and the phone
        // app both show this state and the button that acts on it. Deliberately *before* the
        // cooldown is consumed — a pop-up we chose not to draw must not spend the budget for
        // the next real one.
        if (container.surfaces.anyVisible) {
            Log.i(TAG, "arrival from $source suppressed: a Domofon screen was in front")
            status.recordRejection("a Domofon screen was in front", now)
            finish()
            return
        }

        // The two triggers are independent and will often both notice the same approach a
        // few seconds apart. One announcement per arrival; the guard is persisted because
        // the native fence delivers into a process that is usually dead, so an in-memory
        // latch on either side would never see the other's pop-up.
        val refusal = arrivalRefusal(status.current, now)
        if (refusal != null) {
            Log.i(TAG, "arrival from $source suppressed: $refusal")
            status.recordRejection(refusal, now)
            finish()
            return
        }

        // Keeps the Settings readout honest — an arrival means we are inside. Nothing reads
        // it back to decide anything; see [FenceSide].
        status.recordSide(FenceSide.INSIDE, now)
        status.recordAnnounced(source, now)

        container.appScope.launch {
            try {
                withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    // use {} pairs the release with this acquisition and nothing else. The
                    // old owner count needed the acquire *outside* the try, because a throw
                    // between the two would release a slot never taken and tear down a
                    // connection the phone UI was still holding; a lease closes itself at
                    // most once, and acquire() no longer throws on bad config — the
                    // transport reports failures through the connection flow instead.
                    container.gateService.acquire("arrival").use {
                        // Null when the VPN is asleep or the broker is unreachable. The
                        // pop-up says so rather than not appearing at all — silence looks
                        // identical to a bug.
                        val state = container.gateService.awaitFreshState(STATE_TIMEOUT_MS)
                        Log.i(TAG, "arrival pop-up ($source) with state=${state?.state ?: "unknown"}")
                        container.gateNotifier.notifyApproaching(context, state)
                    }
                }
            } finally {
                finish()
            }
        }
    }
}
