# Module: geo (data/location + receivers geofence parts)

*[Wiki home](../README.md) › modules › geo*

## Responsibility

The home geofence (the arrival pop-up trigger) and the foreground-only distance readout.

| Class | Owns |
|---|---|
| `data/location/GeofenceManager` | Register/remove the one fence (`ID = "home"`, ENTER **and** EXIT) to match settings; permission checks |
| `receivers/GeofenceReceiver` + `ArrivalFlow` | Validate the event; record EXIT as evidence; run the arrival pop-up; the shared arrival guard |
| `receivers/BootReceiver` | Re-register after reboot **and after an app update** (geofences survive neither) |
| `data/location/HomeDistanceTracker` | Adaptive-cadence fused-location polling → `StateFlow<Reading?>`, and the in-app fence |
| `domain/HomeFenceCrossing` | The inward-crossing rule, and `sideOf` — which side a fix can *prove* we are on |
| `domain/GeofenceStatus` + `data/location/GeofenceStatusStore` | What the app has observed about its own trigger, and the Settings line |
| `receivers/DebugGeofenceTrigger` (src/debug only) | Exercise the arrival flow without driving 2 km |

## Two triggers, and why

The arrival pop-up has **two independent triggers running in parallel**. They share the home
coordinates, the radius and `ArrivalFlow`, and nothing else.

| | Native fence (always) | In-app fence (`home.inAppFence`, opt-in, default off) |
|---|---|---|
| Evaluated by | Play Services, in its own process | this app, in `HomeDistanceTracker`'s loop |
| Works with the app dead | **yes** — the whole point | no |
| Schedule | GMS's own; `setNotificationResponsiveness` is a hint we cannot read back | ours, `0.5 × ETA` clamped 10 s‥10 min |
| Observable | only that it did or did not deliver | fully — distance, cadence, crossings |

The native one stays primary: a phone in a pocket is the normal case and only GMS covers it.
The in-app one exists because a 10 km round trip produced no pop-up and **nothing in the app
could say why** (Artur, 2026-07-27) — GMS reports no failure, no schedule and no liveness, so
"never registered", "registered but never evaluated" and "fired into a dropped pop-up" all
looked identical. Adding a trigger the app *can* see splits them apart, and it costs no new
permission: same coordinates, same "Allow all the time" grant, which is why the setting hangs
off `home.enabled`.

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
- **Both triggers funnel through `ArrivalFlow`, whose guard is `domain/arrivalRefusal`.** It
  enforces a 10-minute cooldown — the two triggers will routinely both notice one approach
  seconds apart, and one pop-up per arrival is the point — and, for the native fence only,
  Artur's departure rule (invariant 8). It returns the *reason* rather than a boolean, so a
  refusal is recorded and readable in Settings rather than looking like nothing happening.
  The state behind it is *persisted*, because the native fence delivers into a process that is
  usually dead — an in-memory latch on either side would never see the other's pop-up.
- **An arrival is not announced while a Domofon surface is in front** — the car screen on the
  head unit, or the phone app. It already shows the gate and the button. The refusal is
  recorded and does **not** consume the cooldown; see
  [ui-notifications](ui-notifications.md) invariant 12.
- Every outcome is recorded to `GeofenceStatusStore` and rendered in Settings: the
  registration result (including the Play Services status code on failure), the last native
  delivery, the last in-app crossing, which side of the fence the app last had evidence for
  (the input to invariant 8, so a refused arrival reads as a consequence rather than a
  mystery), the last pop-up and which trigger won it, and the reason any delivery was thrown
  away. **Timestamps and status codes only — never coordinates**
  (invariant 5); a settings row is as readable as logcat.
- The distance line ("1.5 km · approaching home") reuses the geofence's own "Allow all the
  time" grant and stays silent unless the feature is on and located — a build that never
  enabled the geofence has no location behavior at all, which keeps it off Play's
  location-policy radar. It runs while a surface is foreground; **with `home.inAppFence` on it
  additionally runs for the whole car session**, because there it is the trigger and a
  trigger that stops when the driver switches to Maps covers none of the drive. With the
  in-app fence on the line also carries the poll cadence — `1.5 km · approaching home ·
  next ≤20s` — which reports *this* trigger's liveness and says nothing about the native one.

## Invariants

1. **The real receiver is never exported.** `GeofencingEvent.fromIntent` parses
   unauthenticated extras; an exported receiver would let any app forge an ENTER at the
   exact moment a live "Open gate" button appears in front of a driver. The debug trigger
   is a *separate*, debug-source-set receiver taking no input.
2. **Deliveries re-check the setting and the fence id** — a fence registered before the
   user switched the feature off can still be in flight inside Play Services, and the
   extras' claims are not trusted over `triggeringGeofences`.
3. **No `INITIAL_TRIGGER_ENTER`, and the in-app fence's first reading never fires either.**
   At the default 2 km radius the house sits inside the fence, so either would announce
   "approaching home" on every reboot, app start or car-screen open while parked at home.
   `HomeFenceCrossing` mirrors this deliberately: the first reading only establishes which
   side we are on, and `reset()` restores that state whenever the readings stop being
   continuous (tracker stopped, radius edited). A `FenceSide.UNKNOWN` reading is not a reading
   at all — it neither fires nor changes which side we think we are on (invariant 9).
4. **Without background location the fence is refused loudly, and now *visibly*** — never
   registered silently. A registered-but-never-firing fence was the #1 geofence bug; a
   refusal that only reached logcat was the #2, because nobody drives with the phone on a
   cable. The refusal is recorded and the Settings row names the exact system setting.
   **`ACCESS_COARSE_LOCATION` must stay declared and must be requested together with FINE** —
   asking for FINE alone still shows the Android 12+ Precise/Approximate dialog, and
   "Approximate" leaves FINE *denied*, after which `hasPermissions()` is false forever.
5. **Coordinates never reach logcat — or the status store.** Radius, distance, timestamps and
   status codes only. The home position is the user's address.
6. **Distance cadence**: next fix at `0.5 × ETA`, ETA from `distance ÷ max(speed, 50 km/h)`
   (the floor is the whole trick — a red-light 0 km/h gives an infinite ETA), clamped
   10 s‥10 min, balanced-power fixes (the readout rounds to 10 m / 0.1 km; GPS-grade
   accuracy would spend battery on digits nobody sees).
7. **Everything that can drop a fence has a `sync()` behind it.** A fence registered by an
   old build fires at the old component until the first `GeofenceManager.sync()`; reboot,
   app update and force-stop drop fences outright. The call sites are: `MainActivity.onStart`,
   `SettingsActivity.onStop` and its permission-grant path, `BootReceiver`
   (`BOOT_COMPLETED` **and `MY_PACKAGE_REPLACED`**), and `CarGateSession.onCreateScreen`.
   This invariant used to say "which every activity start performs" — which quietly assumed
   the user opens the phone. A car-only user never does, so their fence was never repaired.
8. **An arrival needs evidence that we left.** Artur's rule, live testing 2026-07-28: *if the
   previous position was outside the fence and this one is inside, that is an arrival* —
   anything else is Play Services re-evaluating a fence around a parked car, which is what put
   "Approaching home" on the screen of a car sitting on its own driveway. The evidence is a
   persisted `FenceSide` in `GeofenceStatusStore`, written from three places: a native EXIT
   (which is **why EXIT is registered** — it is the only evidence that survives the app being
   dead for the whole trip, and it never pops anything up), every confident distance reading
   (whether or not the in-app fence is on), and the arrival itself. Two deliberate escapes:
   the rule applies to the **native fence only** — the in-app fence and the debug trigger
   observed the crossing themselves, and applying it to the debug trigger would refuse every
   test from a desk, which is inside the fence — and a recorded `INSIDE` **stops being
   believed after 12 h** (`SIDE_TRUST_MS`). That expiry is the deliberate trade: a stuck
   `INSIDE` would silence the feature permanently, which is the exact failure a 10 km round
   trip already cost once, and a redundant pop-up is the cheaper mistake.
9. **A fix only claims a side it can prove.** `sideOf` uses the fix's own reported accuracy as
   the margin: `INSIDE` needs `meters + accuracy ≤ R`, `OUTSIDE` needs `meters - accuracy > R`,
   and anything between is `UNKNOWN` — recorded nowhere and acted on by nobody. The tracker
   asks for `PRIORITY_BALANCED_POWER_ACCURACY`, and a cold fix in a just-started car can be
   cell-derived; under a bare `meters <= R` one bad fix between two good ones *is* an
   outside→inside transition. A fix with no accuracy at all proves nothing and is treated as
   `UNKNOWN`. What this cannot catch is a fix that is confidently wrong — nothing in the app
   can — which is one more reason the native fence keeps its own opinion.

## Gotchas

- `HomeDistanceTracker`'s per-start scope is a child of the container's `appScope`
  (Main.immediate dispatcher — StateFlow writes stay ordered with their collectors).
- `formatHomeDistance` is pure (`domain/HomeDistanceFormat.kt`); the radius is a
  parameter, supplied by the ViewModel from config — never read from a global.
- `GeofenceStatusStore` has its **own** SharedPreferences file, not a corner of `ConfigStore`.
  This is observed state, not settings: writing it through the config store would emit on
  `ConfigStore.config` every time the fence re-registered, waking the ViewModel, the QML
  binder and the car screen for a value none of them render.
- **The in-app fence behind Maps is the one thing this design cannot prove without a car.**
  An Android Auto session is not a foreground service, so whether the OS keeps feeding
  location to a backgrounded projected session is a device question. If it does not, the
  in-app fence only fires while Domofon is the visible car screen.
- Debug trigger (goes through the same arrival cooldown, so firing it twice inside 10 minutes
  gives one pop-up — that is the de-duplication working, not a bug):
  `adb shell am broadcast -a pl.bitforge.domofon.DEBUG_GEOFENCE -n pl.bitforge.domofon/.receivers.DebugGeofenceTrigger`

## Related pages

[gate](gate.md) (`awaitFreshState` semantics) ·
[ui-notifications](ui-notifications.md) · [config](config.md) ·
[security](../architecture/security.md)
