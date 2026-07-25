package pl.bitforge.domofon.gate

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.car.app.notification.CarAppExtender
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import pl.bitforge.domofon.DomofonApp
import pl.bitforge.domofon.MainActivity
import pl.bitforge.domofon.R
import pl.bitforge.domofon.data.mqtt.GateService
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.domain.GateState

/**
 * Turns gate state into heads-up notifications.
 *
 * The [CarAppExtender] is the whole trick behind "pop up while I'm driving": Android Auto
 * cannot be forced to open an app, but a high-importance notification carrying this
 * extender is drawn over whatever the car screen is showing, Google Maps included. The
 * action button on it is as close to "one tap from Maps" as the platform allows — see
 * docs/07.
 */
object GateNotifier {

    private const val NOTIF_ID_EVENT = 1001

    /** Separate id from [NOTIF_ID_EVENT]: an arrival pop-up must not silently replace it. */
    private const val NOTIF_ID_GEOFENCE = 1002

    /** A third id, so a failure report never overwrites the thing that failed. */
    private const val NOTIF_ID_FAILURE = 1003

    /** Notify on every state change *after* the current one — the current state is not news. */
    fun observe(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        GateService.instance.gateState
            .drop(1)
            .onEach { notifyStateChange(app, it) }
            .launchIn(scope)
    }

    fun notifyStateChange(context: Context, gs: GateState) {
        if (gs.state == GatePolicy.STATE_UNKNOWN) return // disconnect reset, not news
        post(
            context = context,
            notifId = NOTIF_ID_EVENT,
            title = "Gate: ${gs.state}",
            text = "Changed at ${gs.changedAt}",
            state = gs.state,
        )
    }

    /** The geofence pop-up: the whole point of the fence. */
    fun notifyApproaching(context: Context, gs: GateState?) {
        val state = gs?.state ?: GatePolicy.STATE_UNKNOWN
        post(
            context = context,
            notifId = NOTIF_ID_GEOFENCE,
            title = "Approaching home — gate: $state",
            text = if (gs == null) "Gate unreachable — tap to retry" else "Tap to control",
            state = state,
            // With no state we do not know which action is right, and offering the wrong
            // one at 60 km/h is worse than offering none.
            withAction = gs != null,
        )
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
}
