package pl.bitforge.domofon.ui.notifications

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.bitforge.domofon.DomofonApp
import pl.bitforge.domofon.MainActivity
import pl.bitforge.domofon.R
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.receivers.GateCommandReceiver
import pl.bitforge.domofon.domain.GateState
import pl.bitforge.domofon.domain.GeofenceStatus
import pl.bitforge.domofon.domain.freeNotificationSlot
import pl.bitforge.domofon.domain.hourMinute
import pl.bitforge.domofon.domain.hourMinuteAt

/**
 * Renders gate state into heads-up notifications — a stateless renderer; *when* to post is
 * its callers' business (`GateEventNotifier` for state changes, the arrival flow, the
 * command receiver's failure path).
 *
 * The [CarAppExtender] is the whole trick behind "pop up while I'm driving": Android Auto
 * cannot be forced to open an app, but a high-importance notification carrying this
 * extender is drawn over whatever the car screen is showing, Google Maps included. The
 * action button on it is as close to "one tap from Maps" as the platform allows — see
 * docs/modules/ui-notifications.md.
 */
class GateNotifier {

    /**
     * A gate movement, on whichever of the two event slots is free.
     *
     * The alternation is not cosmetic: `opening` and `opened` are one gate cycle fifteen to
     * twenty-five seconds apart, and posting the second onto the live id of the first made it
     * an *update*, which the car host does not draw a heads-up for. See
     * [freeNotificationSlot] for why two ids rather than a shorter timeout or a cancel.
     */
    fun notifyStateChange(context: Context, gs: GateState) {
        if (gs.state == GatePolicy.STATE_UNKNOWN) return // disconnect reset, not news

        val slot = freeNotificationSlot(NOTIF_ID_EVENT, NOTIF_ID_EVENT_ALT, liveIds(context))
        val previous = if (slot == NOTIF_ID_EVENT) NOTIF_ID_EVENT_ALT else NOTIF_ID_EVENT

        // Before the post, not after: the two never overlap in the shade, and cancelling a
        // *different* id than the one being posted cannot swallow the post.
        NotificationManagerCompat.from(context).cancel(previous)

        post(
            context = context,
            notifId = slot,
            title = "Gate: ${gs.state}",
            // HH:mm, never the wire timestamp. This printed the raw ISO-8601 string, so a
            // heads-up over Maps read "Changed at 2026-07-27T18:34:12+02:00" — twenty-five
            // characters for a driver who has time for four.
            text = "Changed at ${hourMinute(gs.changedAt)}",
            state = gs.state,
            timeoutMs = EVENT_TIMEOUT_MS,
        )
    }

    /**
     * The ids this app currently has on screen.
     *
     * `getActiveNotifications` reports only this app's own notifications and needs no
     * permission for them. It is a binder call, so it is guarded: on a host where it throws or
     * the service is gone, an empty set means [notifyStateChange] picks the primary slot,
     * which is exactly the behaviour that existed before this became two ids.
     *
     * Read *before* posting, so the small delay between `notify()` and a notification becoming
     * visible here cannot matter — what is being asked about is the previous announcement,
     * which settled seconds ago.
     */
    private fun liveIds(context: Context): Set<Int> {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return emptySet()
        return runCatching { manager.activeNotifications.map { it.id }.toSet() }
            .getOrDefault(emptySet())
    }

    /**
     * The geofence pop-up: the whole point of the fence.
     *
     * @param atMs when the crossing was *detected*, not when this was called — the arrival
     *   flow spends up to 9.5 s waiting for fresh gate state in between. Rendered as `HH:mm`
     *   because a driver glancing at a heads-up should be able to tell this arrival from the
     *   one twenty minutes ago without remembering which is which.
     */
    fun notifyApproaching(context: Context, gs: GateState?, atMs: Long) {
        val state = gs?.state ?: GatePolicy.STATE_UNKNOWN
        val time = hourMinuteAt(atMs)
        post(
            context = context,
            notifId = NOTIF_ID_GEOFENCE,
            title = "Approaching home — gate: $state",
            text = if (gs == null) "$time · Gate unreachable — tap to retry" else "$time · Tap to control",
            state = state,
            // With no state we do not know which action is right, and offering the wrong
            // one at 60 km/h is worse than offering none.
            withAction = gs != null,
            timeoutMs = GeofenceStatus.ARRIVAL_POPUP_TTL_MS,
        )
    }

    /**
     * Drop the event and arrival notifications — the user is now looking at the real thing.
     *
     * Called when a surface comes to the front. Not just tidiness: an untapped arrival pop-up
     * used to sit in the shade indefinitely, so getting into the car the next morning showed
     * "Approaching home" from yesterday's drive, and the *next* arrival was an update of a
     * live notification rather than a new one — which the car host does not draw a heads-up
     * for (Artur, live testing 2026-07-28).
     *
     * The failure notification (1003) is deliberately left alone: it is the answer to a
     * button the user pressed, and invariant 7 keeps it loud.
     */
    fun clearTransient(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NOTIF_ID_EVENT)
        manager.cancel(NOTIF_ID_EVENT_ALT)
        manager.cancel(NOTIF_ID_GEOFENCE)
    }

    /**
     * The command was acknowledged by dismissing the notification and then did not land.
     * Saying nothing would leave the driver believing the gate is opening when it is not.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    fun notifyCommandFailed(context: Context, action: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val builder = NotificationCompat.Builder(context, DomofonApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle("Couldn't reach the gate")
            .setContentText("The \"$action\" command was not delivered. Tap to try in the app.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp(context, NOTIF_ID_FAILURE))
            .setAutoCancel(true)
            .setTimeoutAfter(EVENT_TIMEOUT_MS)

        manager.notify(NOTIF_ID_FAILURE, builder.extend(CarAppExtender.Builder().build()).build())
    }

    private fun openApp(context: Context, notifId: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled()
    private fun post(
        context: Context,
        notifId: Int,
        title: String,
        text: String,
        state: String,
        timeoutMs: Long,
        withAction: Boolean = true,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val open = openApp(context, notifId)

        val carExtender = CarAppExtender.Builder()
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .setContentIntent(open)

        val builder = NotificationCompat.Builder(context, DomofonApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            // `setAutoCancel` only fires on a *tap*, so without this every notification the
            // user ignored stayed in the shade for good. These announce an event, not a
            // standing status — the app is where current state is read.
            .setTimeoutAfter(timeoutMs)
            // On a locked phone the full text is hidden. "Approaching home — gate: opened"
            // on a lock screen tells anyone standing near it both that the owner is on the
            // way home and that the gate is currently open.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, DomofonApp.CHANNEL_EVENTS)
                    .setSmallIcon(R.drawable.ic_gate)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()
            )

        if (withAction) {
            val primary = GatePolicy.primaryAction(state)
            val icon = if (primary.action == "close") R.drawable.ic_gate_close else R.drawable.ic_gate_open
            val send = GateCommandReceiver.pendingIntent(context, primary.action, notifId)

            // Both extenders: the car one draws the button on the head unit, the plain one
            // on the phone. They are separate action lists.
            carExtender.addAction(icon, primary.label, send)
            builder.addAction(
                NotificationCompat.Action.Builder(icon, primary.label, send)
                    // Makes SystemUI demand an unlock before firing this from the shade.
                    // It is not sufficient on its own — it is not enforced against a direct
                    // PendingIntent.send() — so GateCommandReceiver re-checks server-side.
                    .setAuthenticationRequired(true)
                    .build()
            )
        }

        manager.notify(notifId, builder.extend(carExtender.build()).build())
    }

    private companion object {
        const val NOTIF_ID_EVENT = 1001

        /** Separate id from [NOTIF_ID_EVENT]: an arrival pop-up must not silently replace it. */
        const val NOTIF_ID_GEOFENCE = 1002

        /** A third id, so a failure report never overwrites the thing that failed. */
        const val NOTIF_ID_FAILURE = 1003

        /**
         * The second gate-event slot — not a fourth *kind* of notification, a spare id for the
         * one kind that repeats faster than it expires. See [freeNotificationSlot].
         */
        const val NOTIF_ID_EVENT_ALT = 1004

        /** How long an event or failure notification survives untapped. */
        const val EVENT_TIMEOUT_MS = 10L * 60 * 1000

        // The arrival pop-up's lifetime is
        // [pl.bitforge.domofon.domain.GeofenceStatus.ARRIVAL_POPUP_TTL_MS], not a constant of
        // this class. It has to stay strictly under the window in which the arrival guard
        // will permit another announcement: reposting to an id that still holds a live
        // notification is an *update*, and the car host draws no heads-up for an update — so
        // the second arrival appeared silently in the shade and never over Maps. Keeping both
        // numbers in one companion is what stops one of them being edited alone.
        //
        // Cancelling immediately before re-posting would look like the same fix and is not:
        // `cancel` and `notify` are asynchronous and the notify can be swallowed, which
        // trades a missing heads-up for a missing notification.
    }
}
