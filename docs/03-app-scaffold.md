# 03 — App scaffold: Kotlin host + embedded QML (milestone M2)

Goal: an Android Studio project in `app/` where a Kotlin `MainActivity` displays an
embedded QML view via `QtQuickView`. This is the skeleton every later chapter fills in.

Reference (keep open, it is the canonical example this chapter follows):
<https://doc.qt.io/qt-6/qml-in-android-studio-projects-example.html>

## 1. Create the project

The **Qt Tools for Android Studio** plugin (installed in ch. 01) provides wizards; the
cleanest route in practice:

1. Android Studio → **New Project → Empty Views Activity** (Kotlin, KTS).
   - Name: `Domofon`, package: `pl.bitforge.domofon`
   - Save location: `~/projekty/bitforge/domofon/app`
   - **Minimum SDK: API 28** (Qt for Android's floor), target latest.
2. With that project open, **File → New → New Qt Project…** (there is no *Tools → Qt*
   menu). Create the QML module inside the repo as `app/qtquickview/` with a `Main.qml`.

If you prefer wiring it manually, the moving parts are:

**`app/app/build.gradle.kts`** (module-level):

```kotlin
plugins {
    id("com.android.application")
    // No org.jetbrains.kotlin.android — AGP 9 has built-in Kotlin and rejects it.
    id("org.qtproject.qt.gradleplugin") version "1.4"
}

android {
    namespace = "pl.bitforge.domofon"
    compileSdk = 36
    ndkVersion = "27.2.12479018"             // r27c; never let AGP pick an rc NDK
    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }    // just your phone; add more later
    }
}
```

**`app/settings.gradle.kts`** — the Qt Gradle Plugin is published on **Maven Central**, so
`pluginManagement.repositories` must include `mavenCentral()`. Without it you get
*"Plugin \[id: 'org.qtproject.qt.gradleplugin'] was not found"*.

**Configuration goes in properties, not the build file.** The old `QtBuild {}` block is
deprecated since plugin 1.3:

```properties
# app/gradle.properties (committed) — resolved from the ROOT project dir, not the module
qtProjectPath=qtquickview

# app/local.properties (gitignored) — machine-specific
sdk.dir=/home/artur/Android/Sdk
qtPath=/home/artur/Qt/6.11.1
```

Note `sdk.dir`: the Qt plugin does not read `ANDROID_HOME`, only `local.properties` or
`ANDROID_SDK_ROOT`.

The Qt Gradle plugin builds the QML project with Qt's CMake and packs it into the APK —
no Qt Creator round-trips, everything from Android Studio's Run button.

## 2. The QML side

`qtquickview/Main.qml` — the placeholder that later chapters replace:

```qml
import QtQuick

Rectangle {
    id: root
    color: "#1e1e2e"

    // Properties Kotlin will SET (state flows in from MQTT via Kotlin):
    property string gateState: "unknown"

    // Signals Kotlin will LISTEN to (button presses flow out to MQTT):
    signal commandRequested(string action)

    Column {
        anchors.centerIn: parent
        spacing: 24

        Text {
            text: "Gate: " + root.gateState
            color: "white"; font.pixelSize: 32
            anchors.horizontalCenter: parent.horizontalCenter
        }
        Row {
            spacing: 16
            Button { text: "Open";  onClicked: root.commandRequested("open") }
            Button { text: "Close"; onClicked: root.commandRequested("close") }
        }
    }
}
```

(Use `import QtQuick.Controls` for `Button`; linking `Qt6::Quick` is enough, the import
scanner pulls Controls in.) The **property + signal surface** of this root object is the
entire Kotlin↔QML contract — keep it small and explicit.

Do not name the property `state`: QML's `Item` already has one (also `enabled`, `visible`,
`parent`, `focus`), and yours would shadow it. Hence `gateState`.

## 3. The Kotlin side

`MainActivity.kt`:

```kotlin
import org.qtproject.example.domofon.DomofonQml.Main   // generated — see below

class MainActivity : AppCompatActivity(), QtQmlStatusChangeListener {
    private val mainQml = Main()
    private var qmlReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val qtQuickView = QtQuickView(this)
        setContentView(qtQuickView, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        mainQml.setStatusChangeListener(this)
        qtQuickView.loadContent(mainQml)
    }

    override fun onStatusChanged(status: QtQmlStatus?, content: QtQuickViewContent?) {
        if (status != QtQmlStatus.READY || content != mainQml) return
        qmlReady = true
        mainQml.gateState = "hello from Kotlin"          // typed setter, generated
        mainQml.connectCommandRequestedListener { _, action ->   // typed, generated
            Log.i("Domofon", "QML asked: $action")
        }
    }
}
```

Three things that will bite you here:

- **The generated class's package is derived, not chosen.** It is
  `org.qtproject.example.<cmake target>.<qml module URI>`. With `project(domofon)` and
  `URI DomofonQml` in `qtquickview/CMakeLists.txt` you get
  `org.qtproject.example.domofon.DomofonQml.Main`. Rename either and the import moves.
  After a build, look under `app/build/qt_generated/**/src/` to see what was produced.
- **You get typed accessors, not string keys.** `property string gateState` becomes
  `setGateState(String)`/`getGateState()` (so `mainQml.gateState = …` in Kotlin), and
  `signal commandRequested(string action)` becomes
  `connectCommandRequestedListener { signalName, action -> }`.
- **Setting a property before `READY` does nothing.** Seed the view in `onStatusChanged`,
  and gate any later writes on a ready flag.

Exact class/package names for `QtQuickView`, `QtQmlStatus` and friends come from the Qt
example project — copy the imports from there; they occasionally move between Qt minors.

## 4. Recommended package layout (used by all later chapters)

```
app/src/main/java/pl/bitforge/domofon/
├── MainActivity.kt
├── gate/
│   ├── GateRepository.kt      # ch. 05 — single source of truth: MQTT ↔ StateFlow
│   └── MqttService.kt         # ch. 06 — foreground service wrapper
├── car/
│   └── DomofonCarAppService.kt # ch. 07
└── geo/
    └── GeofenceReceiver.kt    # ch. 08
```

The one architectural rule: **only `GateRepository` touches MQTT.** Activity, car
screen, notifications, geofence handler — all consume the same repository. You write
the MQTT logic once and every surface stays consistent.

## 5. Acceptance test — milestone M2

Run on the phone. ✅ **M2 passes when:**
- the QML screen renders inside the Kotlin activity,
- "Gate: hello from Kotlin" proves Kotlin→QML property flow,
- pressing *Open* logs `QML asked: open` in Logcat, proving QML→Kotlin signal flow.

That round-trip is the foundation of everything else. Commit the working skeleton.
