# 10 — Troubleshooting (living document)

Seeded with the known traps per area. **When a guidance session solves a new problem,
append it here** in the same format: *Symptom → Cause → Fix*. Newest entries at the top
of each section.

## Qt for Android / build

*(Entries below the divider were all hit and fixed on 2026-07-10 while bootstrapping.)*

- **Symptom**: Android Studio has no *Import Qt Project* / *New Qt Project* action, even
  though the Qt Tools plugin is installed and `idea.log` says it loaded.
  **Cause**: the plugin registers both actions into the **File → New** group, which only
  exists once a project is open. The Welcome screen has no File menu. The Qt settings
  page is a *project*-level configurable for the same reason.
  **Fix**: open any project first, then **File → New → New Qt Project…**. Since plugin
  5.0 the dialog no longer has a Qt Path field — it lives in project
  **Settings → Languages & Frameworks → Qt**, persisted to `local.properties`.

- **Symptom**: `Failed to apply plugin 'org.jetbrains.kotlin.android'` — *"no longer
  required for Kotlin support since AGP 9.0"*.
  **Cause**: AGP 9 has built-in Kotlin; applying the Kotlin Android plugin too is a hard
  error. Qt's own shipped `qtquickview_kotlin` example declares **both** and therefore
  does not build as-is.
  **Fix**: delete `id("org.jetbrains.kotlin.android")` from the root *and* module build
  files.

- **Symptom**: `Cannot find a Java installation … matching {languageVersion=17}`, or
  `Toolchain installation '…' does not provide the required capabilities: [JAVA_COMPILER]`.
  **Cause**: two things at once. `kotlin { jvmToolchain(17) }` demands a JDK 17, and
  Debian's `openjdk-17-jre` / `openjdk-21-jre` packages contain **no `javac`** — they are
  JREs. Gradle also does not auto-scan `/usr/lib/jvm`.
  **Fix**: `sudo apt install openjdk-21-jdk`, verify with `ls $JAVA_HOME/bin/javac`, and
  drop the `jvmToolchain` block (plain `compileOptions` is enough).

- **Symptom**: `QtBuildTask … property 'projectPath' specifies directory '…' which
  doesn't exist`, and the printed path is one level too high.
  **Cause**: the `qtProjectPath` **property** is resolved relative to the *root project*
  directory, while the deprecated `QtBuild { projectPath = file(…) }` DSL was resolved
  relative to the *module*. The same string means different directories.
  **Fix**: recompute the path from the root project (where `gradle.properties` lives).

- **Symptom**: `Android SDK path not found. Add the 'sdk.dir' property in local.properties
  file or the 'ANDROID_SDK_ROOT' environment variable.` — even though `ANDROID_HOME` is set.
  **Cause**: AGP honours `ANDROID_HOME`; the Qt Gradle Plugin does not. It reads
  `sdk.dir` from `local.properties`, or `ANDROID_SDK_ROOT`.
  **Fix**: put `sdk.dir=…` in `local.properties` (which is what Android Studio does anyway).

- **Symptom**: `Warning: QtBuild extension is deprecated and will be removed…`
  **Cause**: the `QtBuild {}` DSL is deprecated from Qt Gradle Plugin 1.3.
  **Fix**: configure via properties instead — `qtPath` and `qtProjectPath` in
  `gradle.properties` or `local.properties`. Keep the machine-specific `qtPath` in
  `local.properties`, which is gitignored.

- **Symptom**: `sdkmanager` installs NDK `30.0.14904198` and the app crashes at start.
  **Cause**: `sdkmanager` happily offers **release-candidate** NDKs (it prints
  `30.0.14904198 rc1`), and AGP will select one if you don't pin.
  **Fix**: `sdkmanager "ndk;27.2.12479018"` and pin `ndkVersion = "27.2.12479018"` in the
  module build file. Confirm Qt's expectation: its bundled OpenSSL path literally reads
  `…prebuilt-openssl-…-for-android-ndk-r27c…`.

- **Symptom**: build fails to find a platform for `compileSdk = 36`.
  **Cause**: `platforms;android-36.1` is a *different* package from `platforms;android-36`.
  **Fix**: `sdkmanager "platforms;android-36"`.

- **Symptom**: no `gradlew` in Qt's example directories.
  **Cause**: Qt ships only `gradle/wrapper/gradle-wrapper.properties`; Android Studio
  generates the wrapper on import.
  **Fix**: `gradle wrapper --gradle-version 9.4.1`, or just open the project in Studio.

---

- **Symptom**: linker errors / `UnsatisfiedLinkError` at app start.
  **Cause**: NDK version differs from the one your Qt release was built with.
  **Fix**: install the exact NDK from Qt's docs for your Qt version (Qt 6.11 → r27c =
  `27.2.12479018`), select it in `local.properties`/module build file, clean build.

- **Symptom**: Qt Gradle plugin can't find Qt / CMake.
  **Cause**: `qtPath` wrong, or CMake/Ninja missing (aqtinstall doesn't bundle them).
  **Fix**: `qtPath` must point at the *versioned* dir (`~/Qt/6.11.1`, not `~/Qt`). Ninja
  ships inside both `~/Android/Sdk/cmake/<ver>/bin/` and `~/Qt/Tools/Ninja/` — put one on
  `PATH` or set `qtNinjaPath`; no `apt install` needed.

- **Symptom**: generated QML class (`Main`) unresolved in Kotlin.
  **Cause**: Qt Gradle task didn't run, or the import doesn't match the generated package.
  **Fix**: the package is `org.qtproject.example.<cmake target>.<qml module URI>`, e.g.
  target `domofon` + `URI DomofonQml` → `org.qtproject.example.domofon.DomofonQml.Main`.
  Run `./gradlew QtBuildTask` once and look under `app/build/qt_generated/**/src/`.

- **Symptom**: a QML property you set from Kotlin never changes anything.
  **Cause**: name collision — QML `Item` already defines `state`, `enabled`, `visible`,
  `parent`, `focus`, … Declaring `property string state` shadows the built-in.
  **Fix**: name it something unambiguous (this project uses `gateState`).

- **Symptom**: `adb devices` shows `unauthorized` or nothing.
  **Fix**: replug + accept dialog; check `plugdev` group membership and
  `android-sdk-platform-tools-common` udev rules; `adb kill-server && adb devices`.

- **Symptom**: after an Android Auto session in a real car ends, the phone UI comes back
  wildly zoomed — roughly 20% of one button fills the screen; killing and restarting the
  app fixes it.
  **Cause**: AA projection delivers `uiMode` (car mode) and possibly density
  configuration changes. Without `android:configChanges`, Android recreates
  `MainActivity` — but the Qt runtime is one `QGuiApplication` per process and survives
  the recreation, keeping the car-session display metrics. The new `QtQuickView` then
  lays out `Main.qml` (every size derives from `unit = min(width,height)/100`) against a
  stale devicePixelRatio, so the whole scene renders uniformly mis-scaled.
  **Fix**: `android:configChanges="uiMode|density|screenSize|smallestScreenSize|
  screenLayout|orientation|keyboardHidden"` on `MainActivity`, so the activity survives
  and Qt only sees a resize — its tested path. (Fallback if some device still
  mis-scales: push a `unitOverride` computed in Kotlin from the container size in dp
  into the QML root, bypassing Qt's own metrics for layout. Not currently wired in.)
  Fixed 2026-07-23; verify on the real car, not the DHU — the DHU never triggered it.

## RTSP / video

- **Symptom**: works in `ffplay`, black screen in app.
  **Checklist**: `Multimedia` linked in the QML project's CMake? `INTERNET` permission?
  URL with credentials URL-encoded (`@` in password → `%40`)? Logcat lines from
  `MediaPlayer`/FFmpeg?

- **Symptom**: multi-second latency.
  **Order of attack**: camera keyframe interval ≤ 1 s → use substream → go2rtc
  restream → libVLC fallback (ch. 04 §3). Measure after each step, not before.

- **Symptom**: video freezes/catches up rhythmically over VPN.
  **Cause**: usually TCP-based VPN transport (TCP-over-TCP).
  **Fix**: switch OpenVPN to UDP (ch. 09 §1.5).

## MQTT

- **Symptom**: app connects on Wi-Fi but not over VPN.
  **Cause**: broker listener bound to LAN interface only, or VPN subnet not allowed to
  reach it.
  **Fix**: check `listener` config / firewall; from the phone the broker's IP must be
  the one routed via VPN.

- **Symptom**: state stale after phone reconnects.
  **Cause**: retained message missing (bridge never published) or `cleanSession(true)`
  wiping the subscription.
  **Fix**: `mosquitto_sub -t domofon/gate/state` must print instantly; verify bridge
  startup publish; keep `cleanSession(false)`.

- **Symptom**: `mosquitto_pub` command does nothing.
  **Checklist**: ACL allows `phone` to write `domofon/gate/command`? Bridge subscribed
  (check its log at startup)? JSON valid, `action` in the allowlist?

- **Symptom**: over internet + VPN every fresh app start shows *"Gate system
  unreachable"* (phone) / *"Gate — unreachable"* (car), even though retained gate state
  displays correctly and commands work. On home Wi-Fi it (mostly) looks fine.
  **Cause**: availability was a boolean defaulting to *offline*, fed only by the
  `hc12/available` topic. If the bridge's `online` birth message is **not retained**, a
  client that connects *after* the bridge started never hears it — and a fresh VPN
  session always connects after. At home the app was often already connected across
  bridge restarts, which masked the bug.
  **Fix (app, 2026-07-23)**: availability is tri-state (`unknown`/`online`/`offline`).
  Only the bridge's own LWT `offline` shows the banner; `unknown` — the normal opening
  state of a fresh connection — shows nothing; and any **live** (non-retained) state
  message counts as proof of life, flipping the status to `online` even if the
  availability topic never speaks.
  **Fix (bridge)**: publish the birth `online` with `retain=true`, and register the LWT
  retained too (docs/02 already prescribes this for the domofon bridge; verify
  `hc12-web-service` actually does it). **Check** from a *fresh* client:
  `mosquitto_sub -t 'hc12/available' -v` must print a line immediately — silence means
  the birth is not retained.

## Android Auto

- **Symptom**: app not in car launcher.
  **Checklist (in order)**: AA developer mode + *Unknown sources* enabled? Manifest has
  the `CarAppService` intent-filter with `category.IOT` + `minCarApiLevel` meta-data +
  `automotive_app_desc`? Reconnect/restart head-unit server after install. **On a real
  car, Unknown sources is not enough** — see the next entry.

- **Symptom**: app shows fine in the **DHU** but is missing on a **real car** — Android
  Auto otherwise works there (Maps/Spotify appear), *Unknown sources* is verified on, and
  nothing shows in Logcat.
  **Cause**: *Unknown sources* **does not apply to Android for Cars App Library (templated)
  apps** — only to media, messaging-notification, and parked apps
  ([Google testing docs](https://developer.android.com/training/cars/testing)). The DHU is
  a *development* head unit and is exempt; a production car only lists App Library apps
  installed from a **trusted source**. The manifest, `category.IOT`,
  `ALLOW_ALL_HOSTS_VALIDATOR`, `minCarApiLevel` are **not** the blocker — the install
  source is. (Confirmed 2026-07-19 on a VW Passat B8 / MIB3, both wired and wireless.)
  **Fix**: distribute via Play *without* a full review — **Internal App Sharing** (upload
  the APK, open the share link on the phone, install via Play) or an **Internal Test
  Track**. Install that Play-delivered build, then reconnect to the car. Requires a Play
  Console developer account. See ch. 07 §4.

- **Symptom**: car app opens then immediately closes.
  **Cause**: host validator rejecting the head unit (common with DHU + sideloads).
  **Fix**: `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (ch. 07 §2).

- **Symptom**: notification shows on phone but not on car screen.
  **Checklist**: `CarAppExtender` attached? Extender importance `IMPORTANCE_HIGH`?
  AA settings → notifications enabled for the app?

- **Symptom**: DHU won't start, or starts and never projects.
  **Fix**: see the *Desktop Head Unit* section below — it has the real causes. (`libsdl2`
  and `libportaudio2` are usually already present and are **not** what's missing; the
  DHU's unmet dependency is LLVM's `libc++`.)

## Geofencing

- **Symptom**: geofence never fires. The big three, in order:
  1. Location permission not **"Allow all the time"**.
  2. App battery-restricted (needs *Unrestricted*, ch. 06 §4).
  3. Geofence not re-registered since last reboot (ch. 08 §5).
  Then: radius < 150 m (too small), phone location accuracy set to battery-saving mode.

- **Symptom**: fires very late.
  **Fix**: larger radius (300–500 m), `setNotificationResponsiveness` lower; accept
  that geofencing is approximate by design.

## Desktop Head Unit

- **Symptom**: `./desktop-head-unit` exits immediately with
  `error while loading shared libraries: libc++.so.1: cannot open shared object file`.
  **Cause**: the DHU is a Google binary linked against LLVM's C++ runtime, not GCC's.
  Debian installs neither `libc++1` nor `libc++abi1` by default, and nothing in the
  Android SDK puts them on the loader path.
  **Fix**: `./scripts/dhu.sh`, which points `LD_LIBRARY_PATH` at the host `libc++` the
  NDK already ships (`ndk/*/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu/`).
  `sudo apt install libc++1 libc++abi1` works too, but needs root for no benefit.
  Note `ldd` reports the missing library twice — once for the binary, once for its
  bundled `libusb`; both are satisfied by the same directory.

- **Symptom**: DHU prints `Connecting over ADB to localhost:5277... connected.` and then
  sits at **"Waiting for phone…"** forever. The phone logs nothing.
  **Cause**: the head unit server hands its session to the **first** TCP connection it
  accepts, and never recovers if that connection isn't the DHU. Anything that touches
  port 5277 first — a `nc` check, a `bash /dev/tcp` probe, an earlier DHU you killed —
  silently consumes the session. Every subsequent DHU run connects to a socket nobody
  is servicing.
  **Fix**: on the phone, stop and restart the head unit server, then run the DHU **once**,
  as the first thing to connect. `scripts/dhu.sh` deliberately performs no liveness probe
  for exactly this reason.

- **Symptom**: you want to check whether the head unit server is up before launching.
  **Cause / why you can't**: there is no non-destructive check.
  `adb forward` accepts the *local* connection before it knows whether anything listens
  on the phone, so a dead port looks identical to a live one (verified: forwarding an
  unused port 5999 still accepts, then EOFs). Reading `/proc/net/tcp` on the phone doesn't
  work either — Android hides other UIDs' sockets from the `shell` user (uid 2000), so
  Android Auto's listening socket is simply absent from the table.
  **Fix**: don't check. Start the server, run the DHU, and read its output.

- **Symptom**: DHU exits immediately after printing its version banner, no window.
  **Cause**: the DHU reads commands from an interactive console and quits on stdin EOF.
  Backgrounding it (`nohup ... &`, or any launcher that closes stdin) kills it instantly.
  **Fix**: run it in a real terminal.

- **Symptom**: the app is installed and runs on the phone, but never appears in the
  **DHU** launcher.
  **Cause**: the DHU won't list a sideloaded build until Android Auto is in developer mode.
  A prerequisite, not a bug: nothing in Logcat will tell you. (A **real car** is a
  *different* story — *Unknown sources* doesn't apply to App Library apps there; see the
  DHU-vs-car entry in the *Android Auto* section above.)
  **Fix**: ch. 07 §4 — Android Auto settings → tap *Version* 10×, then developer settings
  ⋮ → check **Unknown sources**. Reconnect afterwards.

## Configuration, R8 and the release build

Added 2026-07-23 during the pre-publication security pass (ch. 11).

- **Symptom**: `minifyRelease` fails with *"Missing classes detected"* naming
  `brotli4j`, `zstd`, `jzlib`, `log4j`, `conscrypt`, `jetty.alpn` or `tcnative`.
  **Cause**: Netty (shaded inside the HiveMQ client) probes for optional codecs and TLS
  providers that exist on neither Android nor this classpath.
  **Fix**: the `-dontwarn` block in `proguard-rules.pro`. Do not silence it with a blanket
  `-ignorewarnings`, which would also hide genuine missing references.

- **Symptom**: release build starts and immediately dies with `UnsatisfiedLinkError` or
  *"Can't find qt_libs"*, while the debug build is fine.
  **Cause**: resource shrinking. `QtLoader` looks its bootstrap arrays and strings up by
  *name* through `Resources.getIdentifier()`, so nothing references them in a way AAPT can
  follow and the shrinker removes them. ProGuard `-keep` rules do not apply to resources.
  **Fix**: `isShrinkResources = false` (current setting), or add `res/raw/keep.xml` listing
  `qt_libs`, `load_local_libs`, `bundled_libs`, `use_local_qt_libs`, `bundle_local_qt_libs`,
  `system_libs_prefix`, `fatal_error_msg`.

- **Symptom**: R8 keep rules for the MQTT client appear to do nothing — release builds
  still misbehave on connect.
  **Cause**: HiveMQ's Android documentation gives rules for `io.netty.**` and
  `org.jctools.**`. Those packages do not exist in the `-shaded` artifact this project
  uses; the rules match nothing and fail silently.
  **Fix**: target the relocated prefix — `com.hivemq.client.internal.shaded.io.netty.**`.

- **Symptom**: `READ_EXTERNAL_STORAGE` appears in the merged manifest even after adding
  `tools:node="remove"` for `WRITE_EXTERNAL_STORAGE`.
  **Cause**: the Qt AAR requests WRITE, and the manifest merger then *implies* READ from
  it. The implication is computed from the library manifest, so removing WRITE does not
  remove the thing derived from it.
  **Fix**: `tools:node="remove"` for **both**. Confirm in
  `app/build/outputs/logs/manifest-merger-release-report.txt`, which names the origin of
  every permission (`IMPLIED from … reason: …`).

- **Symptom**: the Android Auto app opens and instantly closes in a **release** build,
  having worked in debug.
  **Cause**: intended. The release host validator allowlists real Android Auto hosts only,
  and the DHU is an unknown host.
  **Fix**: test the car screen with the debug build. If a *real car* rejects a release
  build the same way, that is a genuine bug — check `hosts_allowlist_sample` resolved.

- **Symptom**: the broker password silently becomes empty; the app behaves as if never
  configured.
  **Cause**: the Android Keystore drops app keys when the user adds or removes a lock
  screen. `SecretStore.decrypt` cannot recover and returns "".
  **Fix**: re-enter it in Settings. Expected behaviour, not corruption — but if it happens
  *without* a lock-screen change, suspect two threads racing to generate the key (the
  reason `SecretStore.key()` is `@Synchronized`).

- **Symptom**: the app dies on launch, before anything renders. Logcat crash buffer:
  `SecurityException: Permission denial: setHideOverlayWindows: HIDE_OVERLAY_WINDOWS`
  at `MainActivity.onCreate`.
  **Cause**: `Window.setHideOverlayWindows()` (API 31+, part of the tapjacking guard)
  requires `android.permission.HIDE_OVERLAY_WINDOWS`. It is not a runtime permission, so
  there is no prompt to miss and no `checkSelfPermission` that would have caught it — the
  call simply throws unless the manifest declares it. Found on-device 2026-07-23; the
  build is clean, only launching the app reveals it.
  **Fix**: declare `<uses-permission android:name="android.permission.HIDE_OVERLAY_WINDOWS"/>`.
  `protectionLevel` is **normal**, so it is granted at install with no prompt and no entry
  in the user-visible permission list. Verify with
  `adb shell pm list permissions -f | grep -A4 HIDE_OVERLAY_WINDOWS`.

- **Symptom**: after the first-run redirect to Settings, backing out lands on a blank
  dark screen — no gate buttons, no settings gear. Taps reach the Qt window (logcat shows
  `QtWindow.onTouchEvent`) but nothing renders, and `QtQuickView status:` never logged
  READY for that process. Found on-device 2026-07-23, first cold start after a fresh
  install.
  **Cause**: `MainActivity.onCreate` started `SettingsActivity` in the same breath as
  `qtQuickView.loadContent()`. On a slow-enough cold start (dexopt + Qt lib extraction —
  i.e. exactly the launch where the redirect always fires) Settings covered the activity
  before the QML load completed, and a `QtQuickView` stopped mid-load stalls **forever**
  — it does not resume the load when the activity restarts. The visible "screen" was the
  wrapper `FrameLayout`'s `#1e1e2e` background. A warm start won the race, which is why
  the bug looked intermittent.
  **Fix**: never launch another activity over `MainActivity` until `onStatusChanged`
  reports `READY` — the first-run redirect now lives there, behind a `firstRun` flag set
  in `onCreate`. Costs ~¼ s of visible gate screen before Settings opens.
  **Residual risk (confirmed on-device)**: the stall is Qt behavior, not specific to the
  redirect. Launching the app with the screen off (`monkey` over adb while dozing), or
  anything else that stops the activity within the first ~300 ms of a cold start,
  reproduces it — and restarting the activity does *not* recover; only killing the
  process does (swipe from recents / `am force-stop`). Root cause per Qt 6.11 sources:
  `QtNative.runAction` queues `createRootWindow` while the app state is
  Suspended/Hidden, the state machine in `QtEmbeddedDelegate`'s lifecycle callbacks is
  guarded by `m_stateDetails.isStarted` (false during early startup), and
  `QtView.onAppStateDetailsChanged` even removes the view from its parent on
  `!isStarted` — several ways for the load to wedge with no retry path. If this bites in
  practice, the app-level cure is a watchdog: in `onStart`, if READY hasn't arrived and
  the activity was previously stopped, discard the wedged `QtQuickView`, create a fresh
  one and `loadContent` again.

- **Symptom**: `E/PreferenceGroup: PreferenceCategory should have a key defined if it
  contains an expandable preference`.
  **Cause**: the Topics category uses `initialExpandedChildrenCount="0"`, which makes it
  collapsible. androidx.preference persists the expanded state under the group's key, and
  the category had none.
  **Fix**: give it `app:key="cat.topics"`. Prefix category keys with `cat.` so they cannot
  collide with `ConfigStore`'s keys — categories hold no value, so nothing is written
  through the data store under them.

- **Symptom**: Play Console shows a signed `.aab` upload apparently succeeding, yet the
  release still errors with *"You must upload an APK or Android App Bundle"* (plus the
  two follow-on errors about the release adding/removing no bundles). A separate note
  says the app *"cannot declare both the device feature
  `android.hardware.type.automotive` and the metadata
  `com.google.android.gms.car.application`"*.
  **Cause**: the scaffold manifest declared
  `<uses-feature android:name="android.hardware.type.automotive" android:required="false"/>`
  under the mistaken belief it marks Android Auto capability. It actually declares an
  **Android Automotive OS** app (app runs in the car's own head unit), while the
  `com.google.android.gms.car.application` metadata declares **Android Auto**
  (projected). Play forbids one artifact declaring both, rejects the bundle during
  post-upload processing, and the release stays empty — the rejection note is easy to
  miss, so the empty-release errors look like the upload never happened.
  **Fix**: delete the `uses-feature` line. A projected Android Auto app needs only the
  metadata + `automotive_app_desc.xml`; no `uses-feature` at all. Rebuild
  `bundleRelease`, re-upload.

- **Symptom** (preemptive): bumping `androidx.media3` (the head-unit snapshot's RTSP
  stack) past 1.10.x fails the AAR metadata check with *"requires minCompileSdk 37"*.
  **Cause**: media3 1.10.1 already declares `minCompileSdk=36` — exactly this project's
  `compileSdk`; the next minor will move past it. Same trap as core-ktx 1.19.0.
  **Fix**: stay on 1.10.x until `compileSdk` moves. And remember media3-rtsp is a new
  reflective surface under R8 — re-test RTSP snapshots in a **release** build after any
  media3 or R8 change; debug proving nothing still holds.

## Backlog / future ideas

(park post-M8 wishes here)

- **Demo mode** — simulated gate state with no broker. Would remove the most likely Play
  rejection (a reviewer cannot reach your broker or your VPN, so the app looks broken to
  them) and makes the background-location demo video far easier to record. See ch. 11 §5.4.
- **Confirmation step on the car screen** — currently one binder-level click moves the
  gate. The host validator is the real control, but a confirmation template would mean a
  misconfigured validator is no longer sufficient on its own.
- **`res/raw/keep.xml`** so resource shrinking can be turned back on.
