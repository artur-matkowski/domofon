# The QML contract (app/qtquickview)

*[Wiki home](../README.md) › modules › ui-qml-contract*

## The bridge, mechanically

The Qt Gradle Plugin's nested build compiles `app/qtquickview/` (CMake) into
`libdomofon_arm64-v8a.so` + a generated Java wrapper per QML file. The wrapper package is

```
org.qtproject.example.<cmake target>.<QML module URI>   →   org.qtproject.example.domofon.DomofonQml
```

so the **CMake target name `domofon` and the URI `DomofonQml` are frozen** — changing
either renames the generated `Main` class and breaks the Kotlin import. The generated
`Main` exposes a typed setter/getter per root property and a
`connect<Signal>Listener` (returning an id for `disconnectSignalListener`) per root
signal.

## The property/signal surface (the ENTIRE Kotlin↔QML contract)

Kotlin → QML (written only by `QmlGateBinder.render`):

| Property | Type | Meaning |
|---|---|---|
| `statusText` | string | The one status line, worded by `GatePolicy`; "" hides |
| `lastError` | string | Why the last command failed; "" hides (20 s TTL upstream) |
| `cameraFrame` | string | `data:image/jpeg;base64,…` holding the newest still itself — see the invariant in [ui-phone](ui-phone.md) |
| `cameraStatus` | string | `CameraFrameGrabber.Status.name.lowercase()` |
| `cameraConfigured` | bool | Gates the whole camera panel |
| `audioNotice` | string | Why the gate is silent, worded by `GatePolicy`; "" hides. Only ever set on the HTTP camera path |
| `homeDistance` | string | Pre-formatted distance line; "" hides |

QML → Kotlin: `signal commandRequested(string action)` · `signal settingsRequested()`.

**Rules:** only primitives cross (a Bitmap cannot — which is why the frame travels as a
base64 string); properties stay per-field (batching into JSON would defeat the typed setters
and QML's per-property binding — consistency is guaranteed below the bridge by `render` being
the only writer); writes before `QtQmlStatus.READY` are silently dropped (the binder gates and
seeds).

`cameraFrame` is the one property that is not a small value: a ≤960 px JPEG is a few tens of
KB and base64 costs a third more. That is affordable at one frame every few seconds and would
not be at video rates — the frame *rate*, not the bridge, is what makes this design work.

## The scene (Main.qml)

One file: settings gear, the double-buffered camera panel (`imgA`/`imgB`, swap only on
`Image.Ready` — the fix for the one-frame blank on every snapshot; a failed load simply
never swaps), status/distance/error lines, and the three gate buttons
(Open/Close/Stop — deliberately three on the phone; [decision D12](../architecture/decisions.md)).
Sizing hangs off one `unit` (short edge / 100). Empty-string-means-hidden is the
visibility convention for the text lines.

Known wording-in-view exception: the camera placeholder ("Camera unreachable" /
"Connecting…") is composed in QML from `cameraStatus`. Moving it behind the bridge means a
new property; recorded, not yet worth it.

## The theme token table (canonical)

`Theme.qml` is a `pragma Singleton` (declared `QT_QML_SINGLETON_TYPE` in CMake — without
that the engine treats it as instantiable and every `Theme.` reference fails at runtime).
**No compile-time check spans the bridge**; this table is the single source and every
mirror points here:

| Token | Value | Mirrors |
|---|---|---|
| `base` | `#1e1e2e` | `PhoneTheme.BASE`, `colors.xml ic_launcher_background` |
| `surface` | `#11111a` | — |
| `text` | `white` | — |
| `muted` | `#a6adc8` | `PhoneTheme.MUTED` |
| `error` | `#f38ba8` | — |

(The palette is Catppuccin Mocha.)

## Build plumbing

`main.cpp` is an 11-line stub that is never the Android entry point — it exists only so
the QML module builds as a shared library. `gradle.properties` `qtProjectPath=qtquickview`
(root-relative) is how the plugin finds this project. R8/keep implications are in
[build-and-release](../build-and-release.md).

## Related pages

[ui-phone](ui-phone.md) · [build-and-release](../build-and-release.md) ·
[decisions D2](../architecture/decisions.md)
