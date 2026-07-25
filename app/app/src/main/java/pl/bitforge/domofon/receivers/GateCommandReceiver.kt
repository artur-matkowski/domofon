package pl.bitforge.domofon.receivers

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pl.bitforge.domofon.R
import pl.bitforge.domofon.container

/**
 * Backs the button inside the heads-up notification, so tapping *Open gate* on the car
 * screen sends the command without ever opening the app.
 *
 * A receiver, not a service: the work is one publish. [goAsync] buys roughly ten seconds,
 * and connect + publish over the VPN lands in one or two.
 *
 * `exported="false"`, so nothing outside this app can broadcast to it directly. It is not
 * the only caller worth worrying about, though — see [refuseWhileLocked].
 */
class GateCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val app = context.applicationContext

        if (refuseWhileLocked(app)) {
            Log.i(TAG, "command '$action' refused: device locked")
            Toast.makeText(app, R.string.unlock_to_open, Toast.LENGTH_LONG).show()
            return
        }

        // Dismiss immediately: the tap is acknowledged now, but the gate takes seconds to
        // move, and a notification still sitting there reads as "nothing happened".
        if (notifId >= 0) NotificationManagerCompat.from(app).cancel(notifId)

        // The app-wide scope, not an ad-hoc one: the old CoroutineScope created here was
        // never cancelled, so a hung send leaked its Job past the receiver forever. The
        // timeout keeps the work inside goAsync's ~10 s budget with margin for the
        // failure notification.
        val pending = goAsync()
        app.container.appScope.launch {
            try {
                val ok = withTimeoutOrNull(RECEIVER_BUDGET_MS) {
                    app.container.gateService.sendCommandAwait(action)
                } ?: false
                Log.i(TAG, "notification action '$action' -> ${if (ok) "sent" else "FAILED"}")
                // The dismissal above was the acknowledgement. Without this, a command that
                // never left the phone looks exactly like one that worked.
                if (!ok) app.container.gateNotifier.notifyCommandFailed(app, action)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The last line of defence on a physical lock.
     *
     * `Action.setAuthenticationRequired(true)` covers the ordinary case — a person tapping
     * the button on a locked screen — but SystemUI only enforces it for taps it renders.
     * Anything holding a `PendingIntent` to this receiver can fire it directly, and a
     * `NotificationListenerService` (notification access is routinely granted to smartwatch
     * companions, automation apps and notification-history apps) can read
     * `sbn.notification.actions[0].actionIntent` and `send()` it verbatim, extras and all.
     * Re-checking here is the only place that reaches both paths.
     */
    private fun refuseWhileLocked(context: Context): Boolean {
        if (!context.container.configStore.current.requireUnlockForCommands) return false
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        // isDeviceSecure(): with no PIN/pattern/biometric configured there is nothing to
        // unlock, so refusing would just break the feature for no gain in safety.
        return keyguard.isDeviceSecure && keyguard.isKeyguardLocked
    }

    companion object {
        private const val ACTION_SEND = "pl.bitforge.domofon.SEND_COMMAND"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_NOTIF_ID = "notif_id"
        private const val TAG = "Domofon"

        /** Hard bound on the receiver's work, inside goAsync's ~10 s with headroom. */
        private const val RECEIVER_BUDGET_MS = 9_500L

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
