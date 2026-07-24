package pl.bitforge.domofon

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * Relaunches [MainActivity] in a brand-new process.
 *
 * Qt is one `QGuiApplication` per process and cannot be started twice in one: a second
 * `QtQuickView` renders nothing and usually aborts the process from
 * `QtNative.runPendingCppRunnables` first. [MainActivity] detects that case before it
 * touches Qt and hands the launch here.
 *
 * The manifest puts this activity in its own `:restart` process, and that is the entire
 * design: it has to be the thing that outlives the kill. Killing the main process from
 * inside itself and hoping the pending activity start survives is a race, and an
 * `AlarmManager` `PendingIntent` fired after the process is gone runs into targetSdk 36's
 * background-activity-launch rules. Here the start comes from a foreground activity that is
 * very much alive.
 *
 * Note that this process runs [DomofonApp.onCreate] too — that is harmless: it only
 * initialises [pl.bitforge.domofon.config.ConfigStore] and declares notification channels.
 * Nothing here may touch `GateRepository`, which would build a second MQTT client.
 */
class QtRestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pid = intent.getIntExtra(EXTRA_PID, 0)
        Log.w(TAG, "restarting pl.bitforge.domofon: killing pid $pid")

        // Same UID, so this is permitted. It also drops whatever the old process held —
        // including a bound car session, which the host rebinds by itself within a second.
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)

        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    companion object {
        private const val TAG = "Domofon"
        private const val EXTRA_PID = "pl.bitforge.domofon.extra.PID"
        private const val PREFS = "qt-restart"
        private const val KEY_LAST_RESTART = "last_restart_ms"

        /**
         * A restart this soon after one that never produced a UI means restarting does not
         * cure it, and repeating would be a launch loop the user cannot escape.
         */
        private const val COOLDOWN_MS = 30_000L

        /**
         * Starts the restart and finishes [activity]. Returns false without doing anything
         * when the previous restart was recent *and* never reached a rendered UI — the
         * caller then has to say something on screen rather than loop.
         */
        fun restart(activity: Activity): Boolean {
            val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val since = now - prefs.getLong(KEY_LAST_RESTART, 0L)
            if (since in 0 until COOLDOWN_MS) {
                Log.w(TAG, "restart suppressed: previous one was ${since}ms ago and never rendered")
                return false
            }
            // commit(), not apply(): the process is about to be killed, and an unwritten
            // stamp is a lost loop guard.
            prefs.edit().putLong(KEY_LAST_RESTART, now).commit()

            activity.startActivity(
                Intent(activity, QtRestartActivity::class.java)
                    .putExtra(EXTRA_PID, Process.myPid())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            activity.finish()
            return true
        }

        /**
         * Called once the QML is actually on screen: the restart worked (or was never
         * needed), so it must not count against the next one. Without this, backing out and
         * reopening twice inside [COOLDOWN_MS] — an entirely normal thing to do during a
         * car session, when every reopen needs a fresh process — would hit the loop guard
         * and land the user on the fallback text instead of the gate.
         */
        fun clearRestartStamp(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_RESTART)
                .apply()
        }
    }
}
