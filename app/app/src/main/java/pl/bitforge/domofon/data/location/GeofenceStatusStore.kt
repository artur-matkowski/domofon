package pl.bitforge.domofon.data.location

import android.content.Context
import android.content.SharedPreferences
import pl.bitforge.domofon.domain.FenceSide
import pl.bitforge.domofon.domain.FenceSync
import pl.bitforge.domofon.domain.GeofenceStatus

/**
 * Where [GeofenceStatus] is kept between processes.
 *
 * Its own SharedPreferences file rather than a corner of [ConfigStore][pl.bitforge.domofon.data.config.ConfigStore]:
 * this is observed *state*, not settings, and writing it through the config store would emit
 * on `ConfigStore.config` every time the fence re-registered — waking the ViewModel, the QML
 * binder and the car screen for a value none of them render.
 *
 * Persistent, not in-memory, for one specific reason: the native fence delivers into a
 * process that is usually dead, so the arrival cooldown that de-duplicates the two triggers
 * has nowhere else it could live and still work.
 *
 * Synchronous like the config store, and for the same reason — every writer here is a
 * `BroadcastReceiver` inside `goAsync`'s ~10 s budget with no coroutine to suspend in (D8).
 */
class GeofenceStatusStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    val current: GeofenceStatus
        get() = GeofenceStatus(
            sync = runCatching { FenceSync.valueOf(prefs.getString(SYNC, "")!!) }
                .getOrDefault(FenceSync.NEVER),
            syncAtMs = prefs.getLong(SYNC_AT, 0L),
            syncDetail = prefs.getString(SYNC_DETAIL, "").orEmpty(),
            lastNativeEnterAtMs = prefs.getLong(NATIVE_ENTER_AT, 0L),
            lastInAppCrossingAtMs = prefs.getLong(IN_APP_CROSSING_AT, 0L),
            lastAnnouncedAtMs = prefs.getLong(ANNOUNCED_AT, 0L),
            lastAnnouncedBy = prefs.getString(ANNOUNCED_BY, "").orEmpty(),
            lastRejection = prefs.getString(REJECTION, "").orEmpty(),
            lastRejectionAtMs = prefs.getLong(REJECTION_AT, 0L),
            side = runCatching { FenceSide.valueOf(prefs.getString(SIDE, "")!!) }
                .getOrDefault(FenceSide.UNKNOWN),
            sideAtMs = prefs.getLong(SIDE_AT, 0L),
        )

    /**
     * Which side of the fence we last observed. A readout; nothing gates on it
     * ([FenceSide]).
     *
     * **Only writes when the side actually changes.** The distance tracker calls this on every
     * fix, and its cadence floor is 10 s — rewriting the same value all the way home would be
     * a prefs commit per fix for no information. It also makes the timestamp mean "since when",
     * which is the more useful of the two readings on a settings row.
     */
    fun recordSide(side: FenceSide, atMs: Long = System.currentTimeMillis()) {
        if (current.side == side) return
        prefs.edit()
            .putString(SIDE, side.name)
            .putLong(SIDE_AT, atMs)
            .apply()
    }

    fun recordSync(result: FenceSync, detail: String = "", atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(SYNC, result.name)
            .putLong(SYNC_AT, atMs)
            .putString(SYNC_DETAIL, detail)
            .apply()
    }

    fun recordNativeEnter(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(NATIVE_ENTER_AT, atMs).apply()
    }

    fun recordInAppCrossing(atMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(IN_APP_CROSSING_AT, atMs).apply()
    }

    fun recordAnnounced(source: String, atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(ANNOUNCED_AT, atMs)
            .putString(ANNOUNCED_BY, source)
            .apply()
    }

    /** Why a delivery was thrown away — the other half of "the fence fired but nothing happened". */
    fun recordRejection(reason: String, atMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString(REJECTION, reason)
            .putLong(REJECTION_AT, atMs)
            .apply()
    }

    private companion object {
        const val FILE = "domofon.geofence-status"

        const val SYNC = "sync"
        const val SYNC_AT = "syncAt"
        const val SYNC_DETAIL = "syncDetail"
        const val NATIVE_ENTER_AT = "nativeEnterAt"
        const val IN_APP_CROSSING_AT = "inAppCrossingAt"
        const val ANNOUNCED_AT = "announcedAt"
        const val ANNOUNCED_BY = "announcedBy"
        const val REJECTION = "rejection"
        const val REJECTION_AT = "rejectionAt"
        const val SIDE = "side"
        const val SIDE_AT = "sideAt"
    }
}
