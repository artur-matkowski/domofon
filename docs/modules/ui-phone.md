# Module: ui-phone (MainActivity + ui/phone)

*[Wiki home](../README.md) › modules › ui-phone*

## Responsibility

Hosting the QML scene and rendering into it. The scene itself and the property/signal
contract are in [ui-qml-contract.md](ui-qml-contract.md).

| Class | Owns |
|---|---|
| `MainActivity` (root package — the launcher component name must not change) | The `QtQuickView` host, insets/edge-to-edge, tapjacking guard, Qt process-restart machinery, lifecycle of the lease/grabber/tracker, first-run redirect, notification permission ask |
| `ui/phone/QmlGateBinder` | **The one writer of the QML surface** and owner of its signal listeners; `AutoCloseable` |
| `ui/phone/FrameFileStore` | The two alternating camera-frame files and their invariant |
| `ui/phone/PhoneTheme` | The Android mirror of the two Theme.qml tokens the host needs |
| `QtRestartActivity` (root package, `:restart` process) | Relaunching `MainActivity` in a brand-new process |

## The render path

`GateViewModel.uiState` → `QmlGateBinder.render(state)` → six typed QML property setters.
`render` is called from exactly two places with the same code: the live collector, and
`onQmlReady()` once as the seed (writes before `QtQmlStatus.READY` are silently dropped;
the seed is what the collector missed while unready). *Its predecessor maintained the
mapping in two hand-written copies, which is the bug class the binder exists to delete.*

Frames: a `Bitmap` cannot cross the bridge, so `FrameFileStore.write` persists a JPEG and
QML gets a `file://` URL — see the invariant below.

## Invariants

1. **Qt is one `QGuiApplication` per process, forever.** A second `MainActivity` in a
   process that already hosted Qt renders nothing and usually SIGABRTs from
   `QtNative.runPendingCppRunnables`. Normally the process dies with the task; a bound car
   session keeps it alive for the whole drive, turning "swipe away and reopen" into
   exactly this. Hence: `qtHostedInThisProcess` (process-scoped, deliberately never
   reset) hands the launch to `QtRestartActivity`, which lives in its own `:restart`
   process *because it must outlive the kill*, stamps a cooldown (loop guard, `commit()`
   because the process is about to die), and relaunches. `clearRestartStamp` on READY is
   what lets normal rapid reopen work during a car session.
2. **The QML-READY watchdog (4 s) restarts a stalled load.** A `QtQuickView` stopped
   mid-load stalls *forever* (a launch with the screen off does it); it is re-armed on
   every start while unready, and only fires while STARTED (background activity launches
   would be dropped under targetSdk 36 anyway).
3. **Frame files alternate names** (`camera-frame-{0,1}.jpg`): both a `?v=` buster and
   alternating names give QML a changed URL (required — setting an identical string emits
   no change and QtQuick caches Image sources by URL), but **only alternating names
   guarantee the file Qt's loader thread is reading is never rewritten mid-read**, which
   can fail the load and blank the panel. The QML side holds up its half by double-
   buffering (swap on `Image.Ready` only).
4. **The binder is closed from `onDestroy`** — the QML signal listeners hold the activity
   through their Qt-side lambdas; close disconnects them (`disconnectSignalListener`) and
   deletes the frame files.
5. **`configChanges` on the manifest entry is load-bearing** (uiMode/density/…): an
   Android Auto connect/disconnect would otherwise recreate the activity against Qt's
   surviving runtime and stale devicePixelRatio — observed as a wildly mis-scaled UI after
   unplugging in a real car. Accepted side effect: rotation does not recreate either.
6. **The first-run Settings redirect fires from READY, not onCreate** — covering the
   activity mid-QML-load strands the load permanently (see the blank-screen entry in
   [troubleshooting](../troubleshooting.md)).
7. **Tapjacking guard on this exported activity** — see
   [security](../architecture/security.md).

## Gotchas

- The `hostingQt=false` placeholder instance (created only to hand off to the restart)
  owns nothing; every lifecycle callback must leave it alone.
- The wrapper `FrameLayout` is named `qtHost` — the name `container` would shadow the
  `Context.container` extension.
- Edge-to-edge: the wrapper absorbs the bars' insets and paints `PhoneTheme.BASE` behind
  them so the bar areas read as part of the (always dark) scene; bar icons forced light.

## Related pages

[ui-qml-contract](ui-qml-contract.md) · [camera](camera.md) ·
[app-container](app-container.md) · [troubleshooting](../troubleshooting.md)
