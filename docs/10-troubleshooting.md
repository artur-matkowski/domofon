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

## Backlog / future ideas

(park post-M8 wishes here)

-
