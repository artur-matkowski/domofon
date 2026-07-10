# 08 — Geofencing: auto pop-up near home (milestone M7)

Goal: when you come within X meters of home, the gate control surfaces by itself —
as a heads-up notification on the car screen (driving) and a notification/pop-up on the
phone.

## 1. What "auto pop-up" can legally mean on Android

- **Driving (Android Auto)**: apps cannot self-launch on the car screen. The geofence
  fires → `MqttService` starts → it posts the `CarAppExtender` HUN (ch. 06/07) with the
  current state and a tap-to-open into `GateScreen`. One tap instead of zero — that is
  the platform maximum.
- **Phone in hand**: same notification. A true full-screen takeover
  (`setFullScreenIntent`) is technically possible but reserved for alarms/calls and
  needs a special permission grant on Android 14+; the high-priority heads-up
  notification is the sane choice. Revisit only if it annoys you in practice.

## 2. Dependencies + permissions

```kotlin
implementation("com.google.android.gms:play-services-location:21.3.0") // check latest
```

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
```

**Permission flow matters** (Android denies shortcuts here): first request
`ACCESS_FINE_LOCATION` (normal dialog) → *then*, in a second step,
`ACCESS_BACKGROUND_LOCATION`, which sends the user to system settings to pick
**"Allow all the time"**. Geofencing without "all the time" silently never fires — this
is the #1 geofence bug.

Add a small settings screen section: home latitude/longitude (long-press on a map app
→ copy coordinates) + radius. Store with the other settings (ch. 04/05).

## 3. Registering the geofence

`geo/GeofenceManager.kt`:

```kotlin
object GeofenceManager {
    private const val ID = "home"

    fun register(ctx: Context, lat: Double, lon: Double, radiusM: Float) {
        val geofence = Geofence.Builder()
            .setRequestId(ID)
            .setCircularRegion(lat, lon, radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .setNotificationResponsiveness(30_000)   // check ~every 30 s: fresh enough, battery-cheap
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence).build()

        val pi = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        LocationServices.getGeofencingClient(ctx).addGeofences(request, pi)
    }
}
```

Radius guidance: geofences are fuzzy (cell/Wi-Fi assisted). **300–500 m** gives you the
pop-up comfortably before the driveway; below ~150 m expect late or missed triggers.

## 4. Reacting to entry

`geo/GeofenceReceiver.kt`:

```kotlin
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            // Start the MQTT keeper (ch. 06): it connects, posts the current-state
            // notification (with CarAppExtender), and self-stops after 15 min.
            context.startForegroundService(
                Intent(context, MqttService::class.java)
                    .putExtra("reason", "geofence"))
        }
    }
}
```

Small addition to `MqttService`: when started with `reason=geofence`, post a
notification **immediately on the first retained state message** (not only on change) —
"Approaching home — gate is closed", tap → app/car screen. That is your pop-up.

## 5. Surviving reboots

Geofences do not persist across reboots. Re-register:

```xml
<receiver android:name=".geo.BootReceiver" android:exported="false">
    <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
</receiver>
```

`BootReceiver.onReceive` → `GeofenceManager.register(...)` from stored settings. Also
re-register whenever the user edits home coordinates/radius.

## Acceptance test — milestone M7

1. Set home coordinates + 400 m radius; grant "Allow all the time" location.
2. Drive (or walk/bike) out beyond ~1 km, then return, phone in pocket, app not opened,
   VPN on.

✅ **M7 passes when, before you reach the gate, the phone (and the car screen, if on
Android Auto) shows "Approaching home — gate: closed", and the tap lands you on the
gate controls.**

If nothing fires: check *Allow all the time*, battery *Unrestricted* (ch. 06), and that
the geofence was registered after the last reboot — the three usual suspects, in that
order.
