package pl.bitforge.domofon

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import pl.bitforge.domofon.config.ConfigStore

class DomofonApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Before anything else: every entry point — the activity, the car session, both
        // receivers — reads ConfigStore.current synchronously, and Application.onCreate is
        // the one place guaranteed to run before any of them.
        ConfigStore.init(this)

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
