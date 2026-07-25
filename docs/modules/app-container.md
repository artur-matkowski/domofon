# Module: app-container (AppContainer + DomofonApp)

*[Wiki home](../README.md) › modules › app-container*

## Responsibility

The composition root — the one place that constructs and wires objects. Everything else
receives dependencies through a constructor or one of the factories. Manual DI by
[decision D9](../architecture/decisions.md); no framework.

## The ownership tree

```
DomofonApp
└── AppContainer (AutoCloseable; closed only by tests — process death is the prod close)
    ├── appScope: CoroutineScope(SupervisorJob + Default)   ← parent of every
    │        process-lifetime coroutine (GateService jobs, receiver work, tracker loops)
    ├── secretStore  = SecretStore()                        (one Keystore alias)
    ├── configStore  = ConfigStore(app, secretStore)        (one prefs file, one listener, one flow)
    ├── gateService  = GateService(HiveMqTransport(), { configStore.current }, appScope)
    ├── geofenceManager = GeofenceManager(configStore)      (one fence ID)
    ├── gateNotifier = GateNotifier()                       (stateless renderer)
    └── gateEventNotifier = GateEventNotifier(...)          (the single collector; started
                                                             once from DomofonApp.onCreate)
Per-surface factories (the caller owns the lifecycle of what it makes):
    newCameraGrabber(context)        → CameraFrameGrabber
    newHomeDistanceTracker(context)  → HomeDistanceTracker (loop parented to appScope)
    newGateViewModel(scope, grabber, tracker) → GateViewModel on the surface's scope
    newPreferenceDataStore()         → ConfigPreferenceDataStore
```

Access from framework-constructed entry points (activities, the car service, receivers):

```kotlin
val Context.container: AppContainer get() = (applicationContext as DomofonApp).container
```

Everything constructed *by* the container gets dependencies via constructor instead.

## Invariants

1. **Construction order is the old init guarantee.** Building the container builds
   `ConfigStore`, and `DomofonApp.onCreate` touches the container before any component can
   run — so every entry point may read `configStore.current` synchronously from its first
   instruction.
2. **One interface only** — `MqttTransport`, the seam that varies. Tests run a *real*
   `GateService` over `FakeTransport`; multiplying interfaces for classes with one
   implementation would be ceremony, not decoupling (SOLID's D applied at the boundary
   that varies).
3. **Singletons are singletons by necessity, not habit**: one socket (`GateService`), one
   prefs file + listener (`ConfigStore`), one Keystore alias, one fence id, one
   notification collector. Everything else is per-surface.
4. **The factories hand out `applicationContext`**, so a surface-held grabber/tracker can
   never leak its activity.
5. **The `:restart` process also builds a container** (DomofonApp runs there too). That is
   harmless — idle objects — but nothing in that process may `acquire()` from
   `GateService`, which would open a second MQTT connection.

## The RAII inventory (who closes what)

| Resource | Owner → close path |
|---|---|
| MQTT connection (`MqttTransport.Handle`) | `GateService.teardown()` |
| A holder's claim (`ConnectionLease`) | the surface: onStop/onDestroy/`use {}` |
| EGL context (`OffscreenTextureReader`) | `RtspFrameSource.close()` (player first) |
| Camera session (whichever source) | `CameraFrameGrabber.stop()`, or `swap()` on a config change |
| The HTTP path's two halves | `HttpCameraSource.close()` — audio then image, reverse of acquisition |
| Separate audio player + its looper | `RtspAudioSource.close()` (posts teardown, waits for it, `quitSafely`) |
| The grabber's config-watch scope | `CameraFrameGrabber.stop()`; a stale `swap()` is refused by scope identity |
| The grabber's session queue | nothing — it shares appScope's job on purpose, so a teardown is never abandoned half-way by the surface that started it |
| Distance-poll scope | `HomeDistanceTracker.stop()`; parented to appScope as backstop |
| QML listeners | `QmlGateBinder.close()` ← `MainActivity.onDestroy` |
| Receiver work | bounded `withTimeoutOrNull` on appScope; `PendingResult.finish()` in `finally` |
| Everything above at once | `AppContainer.close()` cancels appScope (tests) |

## Related pages

[conventions](../conventions.md) (the lease/RAII idioms) · [gate](gate.md) ·
[testing](../testing.md)
