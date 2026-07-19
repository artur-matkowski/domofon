package pl.bitforge.domofon.gate

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Backs the button inside the heads-up notification, so tapping *Open gate* on the car
 * screen sends the command without ever opening the app.
 *
 * A receiver, not a service: the work is one publish. [goAsync] buys roughly ten seconds,
 * and connect + publish over the VPN lands in one or two.
 */
class GateCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val app = context.applicationContext

        // Dismiss immediately: the tap is acknowledged now, but the gate takes seconds to
        // move, and a notification still sitting there reads as "nothing happened".
        if (notifId >= 0) NotificationManagerCompat.from(app).cancel(notifId)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val ok = GateRepository.sendCommandAwait(action)
                Log.i(TAG, "notification action '$action' -> ${if (ok) "sent" else "FAILED"}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_SEND = "pl.bitforge.domofon.SEND_COMMAND"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_NOTIF_ID = "notif_id"
        private const val TAG = "Domofon"

        fun pendingIntent(context: Context, action: String, notifId: Int): PendingIntent {
            val intent = Intent(context, GateCommandReceiver::class.java)
                .setAction(ACTION_SEND)
                .putExtra(EXTRA_ACTION, action)
                .putExtra(EXTRA_NOTIF_ID, notifId)
            return PendingIntent.getBroadcast(
                context,
                // Distinct request code per action+notification, or the extras of whichever
                // intent was built first get reused for both buttons.
                31 * action.hashCode() + notifId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
