package pl.bitforge.domofon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class DomofonApp : Application() {

    /**
     * Built on first touch, which happens right below — before any component can run.
     * Constructing [AppContainer] constructs ConfigStore, preserving the old guarantee
     * that every entry point (the activity, the car session, both receivers) can read the
     * config synchronously the moment it exists.
     */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()

        // Exactly one process-wide state-change collector — the per-surface observers it
        // replaces double-posted every notification when phone and car were both open.
        container.gateEventNotifier.start()

        val nm = getSystemService(NotificationManager::class.java)

        // IMPORTANCE_HIGH is what makes a notification a heads-up one — on the phone and,
        // via CarAppExtender, over whatever is on the Android Auto screen.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Gate events", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Gate state changes" }
        )

        // Reserved for the foreground service in ch. 06; deliberately low-profile.
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Gate watcher", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Ongoing connection while near home" }
        )
    }

    companion object {
        const val CHANNEL_EVENTS = "gate_events"
        const val CHANNEL_SERVICE = "gate_service"
    }
}
