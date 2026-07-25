# Module: geo (data/location + receivers geofence parts)

*[Wiki home](../README.md) › modules › geo*

## Responsibility

The home geofence (the arrival pop-up trigger) and the foreground-only distance readout.

| Class | Owns |
|---|---|
| `data/location/GeofenceManager` | Register/remove the one fence (`ID = "home"`) to match settings; permission checks |
| `receivers/GeofenceReceiver` + `ArrivalFlow` | Validate the ENTER event; run the arrival pop-up |
| `receivers/BootReceiver` | Re-register after reboot (geofences do not survive one) |
| `data/location/HomeDistanceTracker` | Adaptive-cadence fused-location polling → `StateFlow<Reading?>` |
| `receivers/DebugGeofenceTrigger` (src/debug only) | Exercise the arrival flow without driving 2 km |

## Behavior

- The feature defaults **off**; background location is the most intrusive permission the
  app asks for, and it is requested only from the settings screen, only when the user
  switches the feature on, with the Play-mandated disclosure dialog *before* the runtime
  prompt, foreground and background requested **separately** (a combined request is denied
  outright — the classic silent geofence killer).
- On ENTER: `ArrivalFlow` takes a `ConnectionLease("arrival")` via `use {}`, waits
  `awaitFreshState(6 s)` and posts the arrival notification — with an action button only
  when state is known ("offering the wrong action at 60 km/h is worse than offering
  none"). The whole flow is bounded to 9.5 s inside goAsync's budget, on the container's
  `appScope`.
- The distance line ("1.5 km · approaching home") runs only while a surface is foreground,
  reuses the geofence's own "Allow all the time" grant, and stays silent unless the
  feature is on and located — a build that never enabled the geofence has no location
  behavior at all, which keeps it off Play's location-policy radar.

## Invariants

1. **The real receiver is never exported.** `GeofencingEvent.fromIntent` parses
   unauthenticated extras; an exported receiver would let any app forge an ENTER at the
   exact moment a live "Open gate" button appears in front of a driver. The debug trigger
   is a *separate*, debug-source-set receiver taking no input.
2. **Deliveries re-check the setting and the fence id** — a fence registered before the
   user switched the feature off can still be in flight inside Play Services, and the
   extras' claims are not trusted over `triggeringGeofences`.
3. **No `INITIAL_TRIGGER_ENTER`.** At the default 2 km radius the house sits inside the
   fence, so an initial trigger would fire "approaching home" on every reboot while parked
   at home.
4. **Without background location the fence is refused loudly** (log), never registered
   silently — a registered-but-never-firing fence was the #1 geofence bug.
5. **Coordinates never reach logcat** — radius and distance only. The home position is the
   user's address.
6. **Distance cadence**: next fix at `0.5 × ETA`, ETA from `distance ÷ max(speed, 50 km/h)`
   (the floor is the whole trick — a red-light 0 km/h gives an infinite ETA), clamped
   10 s‥10 min, balanced-power fixes (the readout rounds to 10 m / 0.1 km; GPS-grade
   accuracy would spend battery on digits nobody sees).
7. **Moving `GeofenceReceiver` (class rename/repackage) self-heals in one launch**: a
   fence registered by an old build fires at the old component until the first
   `GeofenceManager.sync()` — which every activity start performs.

## Gotchas

- `HomeDistanceTracker`'s per-start scope is a child of the container's `appScope`
  (Main.immediate dispatcher — StateFlow writes stay ordered with their collectors).
- `formatHomeDistance` is pure (`domain/HomeDistanceFormat.kt`); the radius is a
  parameter, supplied by the ViewModel from config — never read from a global.
- Debug trigger:
  `adb shell am broadcast -a pl.bitforge.domofon.DEBUG_GEOFENCE -n pl.bitforge.domofon/.receivers.DebugGeofenceTrigger`

## Related pages

[gate](gate.md) (`awaitFreshState` semantics) ·
[ui-notifications](ui-notifications.md) · [config](config.md) ·
[security](../architecture/security.md)
