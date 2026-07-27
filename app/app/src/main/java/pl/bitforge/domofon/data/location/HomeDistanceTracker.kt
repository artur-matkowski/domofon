package pl.bitforge.domofon.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.bitforge.domofon.data.config.ConfigStore
import pl.bitforge.domofon.domain.HomeFenceCrossing
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * How far the phone is from the home geofence centre, for every surface that wants it.
 *
 * The app's first *active* location code. Everything else about location is the geofence
 * ([GeofenceManager]), which the platform tracks for us and which never tells us a
 * distance — only that we crossed the fence. This class asks, on a schedule, "where are we
 * now" and turns it into metres-from-home.
 *
 * Deliberately built like [pl.bitforge.domofon.data.camera.CameraFrameGrabber]: a plain object
 * owned by whatever is on screen (the phone activity, the car session), started and stopped
 * with that owner's lifecycle. It holds nothing open in the background — the fix is pulled
 * only while a surface is foreground, so no foreground service and no permission beyond the
 * one the geofence already asked for.
 *
 * The permission is exactly that reuse: this reads the home coordinates the user already
 * entered for the geofence and rides the "Allow all the time" grant the geofence required.
 * With the feature off, or the grant missing, it produces nothing. So a build that never
 * turned the geofence on has no new location behaviour at all — which is what keeps this
 * off Play's location-policy radar (see CLAUDE.md).
 *
 * Home coordinates live here, never in the MQTT layer: the
 * config is sliced so the MQTT layer never sees the user's address. Same reason nothing in
 * here is logged but the distance itself.
 */
class HomeDistanceTracker(
    private val context: Context,
    private val configStore: ConfigStore,
    private val geofence: GeofenceManager,
    private val status: GeofenceStatusStore,
    /** Parent of every polling scope this creates — container shutdown stops them all. */
    private val appScope: CoroutineScope,
    /**
     * Called when these readings cross the fence inward and the in-app fence is switched on.
     * Wired to `ArrivalFlow` by the container; a parameter rather than a direct call so this
     * class keeps knowing nothing about MQTT or notifications.
     */
    private val onArrival: () -> Unit = {},
) {

    /**
     * Metres from the home geofence centre. A null [distance] value means "unavailable".
     *
     * [nextFixInMs] is how long this loop intends to wait before looking again. It is on the
     * reading because the car screen shows it: with the in-app fence on, a driver wants to
     * know the readout is still alive and roughly how sharp it is. Note what it is *not* —
     * it says nothing about the Play Services fence, whose evaluation schedule the app can
     * neither see nor set.
     */
    data class Reading(val meters: Float, val nextFixInMs: Long)

    private val _distance = MutableStateFlow<Reading?>(null)

    /**
     * Current distance, or null when there is nothing to show — the feature is off, the
     * permission is missing, or no fix has arrived yet. The last good reading is kept across
     * a stop/start within one process, so returning to the screen redraws instantly rather
     * than blanking until the next fix.
     */
    val distance: StateFlow<Reading?> = _distance.asStateFlow()

    /** Non-null exactly while the loop is running. Guards the repeatable start/stop. */
    private var scope: CoroutineScope? = null

    /** The in-app fence, rebuilt whenever the radius changes under it. Null = not armed. */
    private var crossing: HomeFenceCrossing? = null
    private var crossingRadius = 0f

    /**
     * Begin producing readings. No-op if already running, or if the feature is off / the
     * location grant is missing — in which case the readout is hidden. Safe to call on every
     * `onStart`, mirroring [GeofenceManager.sync]: a later call re-evaluates the guards after
     * the user enables the feature or grants the permission in Settings.
     */
    @Synchronized
    fun start() {
        if (scope != null) return
        if (!configStore.current.home.isUsable || !geofence.hasPermissions(context)) {
            _distance.value = null
            return
        }
        // Main.immediate: every step is either a suspending Play-services call or a delay, so
        // there is no CPU work to keep off the UI thread, and staying on Main keeps the
        // StateFlow writes ordered with the collectors that read them. The job parents to
        // the app scope, so a container close cancels a loop its owner forgot to stop.
        val s = CoroutineScope(
            appScope.coroutineContext + SupervisorJob(appScope.coroutineContext.job) +
                Dispatchers.Main.immediate
        )
        scope = s
        s.launch { loop() }
    }

    /** Stop producing. Cancels the loop and any in-flight fix; the last reading is kept. */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        // The readings are about to stop being continuous. Forgetting which side of the
        // fence we were on is what stops a tracker stopped outside and restarted inside from
        // reporting an arrival nothing observed.
        crossing?.reset()
    }

    /**
     * The fence to test this reading against, rebuilt if the user changed the radius.
     *
     * A radius edit moves the boundary, so which side we were on last time is no longer a
     * comparable answer — a fresh fence re-establishes it from the next reading instead.
     */
    @Synchronized
    private fun fenceFor(radiusMeters: Float): HomeFenceCrossing {
        val existing = crossing
        if (existing != null && crossingRadius == radiusMeters) return existing
        return HomeFenceCrossing(radiusMeters).also {
            crossing = it
            crossingRadius = radiusMeters
        }
    }

    /** The in-app fence is off, or unusable. Forget the side rather than keep a stale one. */
    @Synchronized
    private fun disarmFence() {
        crossing = null
        crossingRadius = 0f
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val home = configStore.current.home
            if (!home.isUsable) {
                // Home was cleared or the feature switched off while we were running.
                _distance.value = null
                return
            }

            val fix = currentFix()
            if (fix == null) {
                // No fix this round (location off, or GMS could not resolve one). Keep the
                // last reading rather than blanking on a transient miss, and retry soon.
                delay(MIN_DELAY_MS)
                continue
            }

            val homeLoc = Location("home").apply {
                latitude = home.latitude!!   // non-null: guaranteed by home.isUsable
                longitude = home.longitude!!
            }
            val meters = fix.distanceTo(homeLoc)
            val next = nextDelayMs(meters, fix)
            _distance.value = Reading(meters, next)

            // The opt-in in-app fence, evaluated on the same readings that feed the readout.
            // Beside the Play Services fence, never instead of it: this one only runs while a
            // surface is alive, but it is the only one whose evaluation the app can observe.
            if (home.inAppFence) {
                if (fenceFor(home.radiusMeters).onReading(meters)) {
                    Log.i(TAG, "in-app fence: crossed inward")
                    status.recordInAppCrossing()
                    onArrival()
                }
            } else {
                disarmFence()
            }

            // Distance and cadence only — never the coordinates. metres-from-home is what is
            // already on screen; the home position is not.
            Log.i(TAG, "distance ${meters.roundToInt()} m, next fix in ${next / 1000} s")
            delay(next)
        }
    }

    /**
     * One fresh fix, or null. Balanced power rather than high accuracy: this drives a
     * coarse "how far" readout that rounds to 10 m / 0.1 km, so a GPS-grade fix would spend
     * battery for digits nobody sees. Wrapped by hand because the project does not depend on
     * kotlinx-coroutines-play-services; the [CancellationTokenSource] is what makes a
     * cancelled loop actually abandon an in-flight request.
     */
    @SuppressLint("MissingPermission") // guarded by GeofenceManager.hasPermissions in start()
    private suspend fun currentFix(): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cts = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cts.cancel() }
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
                .addOnSuccessListener { cont.resume(it) }   // it may be null
                .addOnFailureListener { cont.resume(null) }
        }
    }

    /**
     * When to take the next reading: half the estimated drive time home, clamped to
     * [MIN_DELAY_MS]‥[MAX_DELAY_MS].
     *
     * Speed is the fix's own GNSS reading. Deriving it from consecutive fixes is useless
     * when they can be ten minutes apart, so an absent speed falls back to 0 — which the
     * floor immediately lifts. The [FLOOR_SPEED_MPS] floor (assume never slower than 50 km/h)
     * is the whole trick: without it a red-light 0 km/h gives an infinite ETA, and a crawling
     * 5 km/h gives an absurd one. There is intentionally no ceiling — a genuinely fast
     * approach shortens the interval, which the 10 s floor already bounds.
     */
    private fun nextDelayMs(meters: Float, fix: Location): Long {
        val speed = if (fix.hasSpeed()) fix.speed else 0f    // m/s
        val effective = max(speed, FLOOR_SPEED_MPS)
        val etaSeconds = meters / effective
        val raw = (0.5f * etaSeconds * 1000f).toLong()
        return raw.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
    }

    private companion object {
        const val TAG = "Domofon"

        /** 50 km/h in m/s — the assumed minimum approach speed. See [nextDelayMs]. */
        const val FLOOR_SPEED_MPS = 50f * 1000f / 3600f

        const val MIN_DELAY_MS = 10_000L      // 10 s
        const val MAX_DELAY_MS = 600_000L     // 10 min
    }
}

