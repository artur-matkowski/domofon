# 06 — Notifications + background strategy (milestone M5)

Goal: gate-state changes produce notifications even when the app UI is not on screen —
without a battery-hungry 24/7 connection.

## 1. The connectivity model (decide once, here)

The app connects to MQTT in exactly three situations:

| Situation | Who connects | Covered in |
|---|---|---|
| App in foreground | `MainActivity` (`onStart`/`onStop`, ch. 05) | done |
| Android Auto session active | `DomofonCarAppService` session lifecycle | ch. 07 |
| Geofence entered | short-lived foreground service (this chapter) | trigger in ch. 08 |

This covers every requirement (you see state at home, while driving, and when
approaching home) with near-zero idle battery cost. A permanent connection would fight
Doze, OEM task killers, and Android 14/15 foreground-service time limits — for events
you wouldn't act on anyway.

## 2. Notification channel + permission

`Application.onCreate`:

```kotlin
val channel = NotificationChannel(
    "gate_events", "Gate events", NotificationManager.IMPORTANCE_HIGH
).apply { description = "Gate state changes" }
getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
```

Android 13+ requires runtime permission — request in `MainActivity` on first launch:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

```kotlin
if (checkSelfPermission(POST_NOTIFICATIONS) != PERMISSION_GRANTED)
    requestPermissions(arrayOf(POST_NOTIFICATIONS), 1)
```

## 3. The foreground service (MQTT keeper)

`gate/MqttService.kt` — started by the geofence receiver (ch. 08); holds the MQTT
connection and posts notifications on state changes:

```kotlin
class MqttService : Service() {
    private var lastState: String? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID_STICKY, buildStickyNotification())
        GateRepository.connect(Settings.load(this).mqtt)
        scope.launch {
            GateRepository.gateState.collect { gs ->
                if (lastState != null && gs.state != lastState) notifyStateChange(gs)
                lastState = gs.state
            }
        }
        // Self-stop after 15 min — geofence re-entry restarts it. Never runs forever.
        scope.launch { delay(15.minutes); stopSelf() }
        return START_NOT_STICKY
    }

    private fun notifyStateChange(gs: GateState) {
        val n = NotificationCompat.Builder(this, "gate_events")
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle("Gate: ${gs.state}")
            .setContentText("Changed at ${gs.changedAt}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(mainActivityPendingIntent())
            .setAutoCancel(true)
            // .extend(carAppExtender())  ← added in ch. 07 for Android Auto HUN
            .build()
        NotificationManagerCompat.from(this).notify(NOTIF_ID_EVENT, n)
    }

    override fun onDestroy() { GateRepository.disconnect(); scope.cancel(); super.onDestroy() }
}
```

Manifest (Android 14+ requires an explicit foreground-service type; `specialUse` fits
"personal smart-home connection while near home"):

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE"/>

<service android:name=".gate.MqttService"
         android:foregroundServiceType="specialUse"
         android:exported="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
              android:value="Maintains MQTT connection for gate notifications near home"/>
</service>
```

The sticky (`NOTIF_ID_STICKY`) notification is the mandatory FGS one — make it
low-profile: channel with `IMPORTANCE_LOW`, text "Watching the gate…". Event
notifications use the separate high-importance `gate_events` channel.

**Note**: `GateRepository.connect`/`disconnect` are now called from two owners
(activity and service). Make the repository reference-counted or idempotent (the `if
(client != null) return` guard from ch. 05 already makes double-connect harmless; make
`disconnect` only act when *no* owner remains — simplest: a `connectedOwners` counter).

## 4. Battery exemption (recommended, once)

Settings → Apps → Domofon → Battery → **Unrestricted**. Same for **OpenVPN for
Android** — an OEM killing the VPN kills everything downstream. On aggressive OEMs
(Xiaomi/Samsung/OnePlus) see <https://dontkillmyapp.com> for the extra switches.

## Acceptance test — milestone M5

1. Start `MqttService` manually for the test (temporary button in the app, or
   `adb shell am start-foreground-service -n pl.bitforge.domofon/.gate.MqttService`).
2. Put the app in the background, lock the screen.
3. Change gate state (`psql UPDATE` or real gate).

✅ **M5 passes when a heads-up "Gate: opening" notification appears on the locked
phone, and tapping it opens the app.**
