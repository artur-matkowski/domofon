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

/**
 * Turns gate-state changes into heads-up notifications.
 *
 * The [CarAppExtender] is the whole trick behind "pop up while I'm driving": Android Auto
 * cannot be forced to open an app, but a high-importance notification carrying this
 * extender is drawn over whatever the car screen is showing, Google Maps included.
 *
 * ch. 06 moves this into the MqttService so it keeps working with the app backgrounded.
 */
object GateNotifier {

    private const val NOTIF_ID_EVENT = 1001

    /** Notify on every state change *after* the current one — the current state is not news. */
    fun observe(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        GateRepository.gateState
            .drop(1)
            .onEach { notifyStateChange(app, it) }
            .launchIn(scope)
    }

    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() below
    private fun notifyStateChange(context: Context, gs: GateState) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, DomofonApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle("Gate: ${gs.state}")
            .setContentText("Changed at ${gs.changedAt}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setContentIntent(openApp)
                    .build()
            )
            .build()

        manager.notify(NOTIF_ID_EVENT, notification)
    }
}
