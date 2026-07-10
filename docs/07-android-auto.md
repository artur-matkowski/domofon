# 07 — Android Auto (milestone M6)

Goal: gate control on the car screen, and a heads-up notification (HUN) over whatever
is currently displayed (e.g. navigation) when the gate state changes.

**Reality check first** (from ch. 00): the car screen only renders Google's
*Car App Library* templates — no QML, no custom layouts, and apps cannot force-launch
themselves. Your car UI will be: a grid of actions (Open / Close / Stop) + current
state, and HUNs for state changes. That is exactly what a gate needs.

## 1. Dependencies + manifest

```kotlin
dependencies {
    implementation("androidx.car.app:app:1.7.0")           // check latest stable
    implementation("androidx.car.app:app-projected:1.7.0") // Android Auto projection
}
```

Manifest:

```xml
<uses-feature android:name="android.hardware.type.automotive" android:required="false"/>

<application ...>
    <meta-data android:name="androidx.car.app.minCarApiLevel" android:value="1"/>
    <meta-data android:name="com.google.android.gms.car.application"
               android:resource="@xml/automotive_app_desc"/>

    <service android:name=".car.DomofonCarAppService" android:exported="true">
        <intent-filter>
            <action android:name="androidx.car.app.CarAppService"/>
            <category android:name="androidx.car.app.category.IOT"/>
        </intent-filter>
    </service>
</application>
```

`res/xml/automotive_app_desc.xml`:

```xml
<automotiveApp>
    <uses name="template"/>
</automotiveApp>
```

`IOT` is the official category for "control your smart devices from the car" apps — a
gate is its textbook case.

## 2. Service, session, screen

`car/DomofonCarAppService.kt`:

```kotlin
class DomofonCarAppService : CarAppService() {
    // Personal/sideloaded app — skip host signature validation:
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // The AA session is one of the three MQTT owners (ch. 06 model):
            GateRepository.connect(Settings.load(carContext).mqtt)
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) GateRepository.disconnect()
            })
            return GateScreen(carContext)
        }
    }
}
```

`car/GateScreen.kt` — a `GridTemplate` fed by the same `GateRepository`:

```kotlin
class GateScreen(carContext: CarContext) : Screen(carContext) {
    init {
        // Redraw whenever the state changes:
        lifecycleScope.launch {
            GateRepository.gateState.collect { invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val state = GateRepository.gateState.value.state
        fun item(title: String, icon: Int, action: String) =
            GridItem.Builder()
                .setTitle(title)
                .setImage(CarIcon.Builder(
                    IconCompat.createWithResource(carContext, icon)).build())
                .setOnClickListener { GateRepository.sendCommand(action) }
                .build()

        return GridTemplate.Builder()
            .setTitle("Gate — $state")
            .setSingleList(ItemList.Builder()
                .addItem(item("Open",  R.drawable.ic_gate_open,  "open"))
                .addItem(item("Close", R.drawable.ic_gate_close, "close"))
                .addItem(item("Stop",  R.drawable.ic_gate_stop,  "stop"))
                .build())
            .build()
    }
}
```

Note what's *absent*: no MQTT code, no REST, no state parsing — `GateRepository` from
ch. 05 already does it all. The car screen is ~60 lines.

## 3. The heads-up notification on the car screen

One line upgrades the ch. 06 notification to also surface in the car — uncomment and
implement `carAppExtender()`:

```kotlin
.extend(CarAppExtender.Builder()
    .setImportance(NotificationManager.IMPORTANCE_HIGH)   // HIGH → heads-up on car
    .build())
```

With Android Auto connected, a state change now pops "Gate: opening" **over the current
car screen** (even over Google Maps); tapping it opens your car app's `GateScreen`.
This is the requirement "while driving, show me the gate is being opened" — done.

## 4. Enable your (sideloaded) app in Android Auto

Google only lists Play-distributed apps by default. For a personal build:

1. Phone → **Android Auto app → Settings** → tap *Version* 10× → developer mode.
2. Developer settings (⋮ menu) → check **Unknown sources**.
3. Reconnect to the car / restart the head unit server. Your app appears in the
   launcher (IOT apps show up directly).

This must be repeated only if you reset Android Auto's data.

## 5. Testing with the Desktop Head Unit (DHU)

Installed in ch. 01. Start the head unit server on the phone (Android Auto → dev
settings ⋮ → **Start head unit server**), then, **in a real terminal**:

```bash
./scripts/dhu.sh
```

That wrapper exists because the raw binary needs two things doing first. It sets up the
`adb forward tcp:5277 tcp:5277` the DHU expects, and it puts LLVM's `libc++` on
`LD_LIBRARY_PATH` — the DHU is linked against `libc++.so.1` / `libc++abi.so.1`, which
Debian does not install by default. Rather than `sudo apt install libc++1 libc++abi1`,
the script borrows the host copies the Android NDK already ships under
`ndk/*/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu/`.

Two rules, both of which cost an afternoon to learn (ch. 10):

- **The DHU must be the first thing to connect** after you start the head unit server.
  The server gives its session to whatever connects first and never recovers, so a
  "is the port open?" probe permanently wedges it. If you see *"Waiting for phone…"*,
  stop and restart the server on the phone, then run the DHU once.
- **Run it in a terminal.** It reads console commands and exits at stdin EOF, so
  backgrounding it makes it die instantly with no window.

A car screen opens on your Debian desktop, projecting from the phone. Iterate here —
it's 10× faster than walking to the car. Keyboard shortcuts and options:
<https://developer.android.com/training/cars/testing/dhu>.

## Acceptance test — milestone M6

In the DHU (then once for real in the car):

1. App appears in the car launcher; opening it shows the gate grid with live state.
2. Tapping *Open* drives the real gate (bridge logs show the REST call); the grid
   title updates as the state changes.
3. With **Google Maps in the foreground** on the car screen, change the gate state →
   HUN pops over the map; tapping it opens the gate screen.

✅ **M6 passes when all three work in the DHU and you've smoke-tested once in the car.**
