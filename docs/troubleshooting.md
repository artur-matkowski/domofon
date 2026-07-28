# Troubleshooting (living document)

*[Wiki home](README.md) › troubleshooting*

**When a session solves a new problem, append it here** in the same format:
*Symptom → Cause → Fix*. Newest entries at the top of each section. This log is
append-only history — entries are never rewritten to match later refactors.

> **Reading old entries:** references like "ch. 04" or "docs/11" point at the original
> chapter-numbered design record, which was restructured into this wiki on 2026-07-25
> (chapters 00–09/11 → [architecture/](architecture/overview.md), [modules/](modules/gate.md),
> [build-and-release.md](build-and-release.md); this file was chapter 10). Class names in
> older entries may predate the refactor: `GateRepository` → `GateService` + friends
> ([modules/gate.md](modules/gate.md)), `MainActivity.writeFrame` → `FrameFileStore`,
> `ArrivalPopUp` → `ArrivalFlow`, packages `gate/ geo/ camera/ config/ car/` →
> `domain/ data/ ui/ receivers/`. The symptoms and causes remain exactly as valid.

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

- **Symptom**: with a DHU (or car) session connected, closing the phone app and reopening
  it either **crashes** it — and the launch after that works — or comes up as a plain dark
  rectangle with no buttons, no status line, no gear. It is *not* the mis-scale above: a
  mis-scale renders a huge fragment of a button, this renders nothing at all. Diagnosed
  on-device 2026-07-24.
  **Cause**: Qt is one `QGuiApplication` per process, owned by the activity that first
  loaded it, and **it cannot be started twice in one process**. The bound
  `DomofonCarAppService` keeps the app process alive for the whole car session, so a
  `MainActivity` that is destroyed (back out, swipe from recents) and relaunched lands in
  that same process and loads Qt a second time. Usually that aborts the process outright:

  ```
  Abort message: 'JNI DETECTED ERROR IN APPLICATION: java_class == null
      in call to IsInstanceOf
      from void org.qtproject.qt.android.QtNative.runPendingCppRunnables()'
  ```

  — Qt's stale JNI class cache, on the main looper. When it does not abort, the
  `QtQuickView` attaches and even creates its root window, but the scene never renders and
  `onStatusChanged` never reports `READY`. The crash is why the *next* launch works: it
  kills the process, so that one gets a fresh Qt runtime. Without a car session the process
  dies with the task and none of this is reachable — which is why it looks DHU-specific.
  Same family as the blank-screen entry in the *Configuration / settings* section below.
  **Evidence to confirm it is this and not something else** (all read-only):
  `adb logcat -b crash -d` shows the abort message above;
  `dumpsys activity services pl.bitforge.domofon` shows the car service bound by
  `com.google.android.projection.gearhead`; `dumpsys activity activities` shows several
  `MainActivity` `ActivityRecord`s inside one pid; on the blank variant `dumpsys activity
  top` shows `QtQuickView` → `QtWindow` → `QtTextureView` present and correctly sized, with
  a clean activity config, while the screen is uniform `#1e1e2e` — the wrapper
  `FrameLayout`'s background — and no `QtQuickView status: READY` in logcat for that pid.
  **Fix**: `MainActivity` refuses to load Qt twice. A process-scoped
  `qtHostedInThisProcess` flag is set the first time it builds the view; a later instance
  that finds it set builds nothing and hands the launch to `QtRestartActivity`, which lives
  in its own `:restart` process, kills the main process and starts `MainActivity` again —
  in a fresh process, where Qt loads normally. A `QtQuickView` can also stall without any
  of this (see the blank-screen entry), so a `QML_READY_TIMEOUT_MS` watchdog takes the same
  exit if `READY` never arrives. A `SharedPreferences` timestamp suppresses a second
  restart within 30 s *of one that never rendered*, so a genuine loop stops at the
  "close Domofon and open it again" fallback text; reaching `READY` clears the stamp, so
  ordinary back-out-and-reopen cycles restart as often as they need to.
  Manual equivalent, if you are on an older build:
  `adb shell am force-stop pl.bitforge.domofon`, then launch.
  **Cost, accepted**: killing the process drops the car session with it. The host rebinds
  by itself — instantly when the gate app is what is on the car screen, otherwise the next
  time you open it there.
  Verified on device 2026-07-24 with the DHU connected, release build: four launches with a
  back-out between each, three automatic restarts, every one rendered, zero crashes.

## RTSP / video

- **Symptom**: changing a camera setting — the RTSP address, the source dropdown, the gate
  audio switch — does not bring the stream back. The panel keeps showing the *old* camera's
  last picture and says nothing; force-stopping the app is the only thing that fixes it.
  Phone only, no car session involved. (Reported 2026-07-25.)
  **Cause**: five separate defects in the teardown/reopen path, any one of which produces the
  same "nothing happened". The configuration itself was never at fault — it reached the
  grabber every time.
  1. `RtspFrameSource.close()` only *posted* its teardown and returned, while
     `CameraFrameGrabber.swap()` opened the replacement synchronously. Two sessions at a
     camera that allows one, so the new one was refused and sat out a 30 s backoff. The same
     gap sat between `stop()` and the next `start()`.
  2. A failed `OffscreenTextureReader.setup()` emitted ERROR and returned **without arming
     the retry or the watchdog**, on the reasoning that a device with no EGL context has none
     to offer. False here: this is rarely the *first* context in the process (Qt holds one for
     the whole UI), and the interesting one is the second, built moments after another was
     torn down. One transient failure = camera dead until force-stop.
  3. The retired source posted `onStatus(IDLE)` from its own thread *after* the replacement
     had already reported CONNECTING, so a late IDLE landed on top of the live session. A late
     `onFrame` could likewise deliver a frame from the old URL after a switch.
  4. `start()` published `handler` *after* posting the work that reads it
     (`handler = Handler(t.looper).also { it.post { … } }` — Kotlin evaluates the RHS first,
     and `t.looper` has already blocked until the loop is running). If the player thread won
     the race, `startAttempt()` found a null handler and gave up silently: no player, no
     watchdog, no retry. Same code in `RtspAudioSource`.
  5. `OffscreenTextureReader.release()` called `eglTerminate` on `EGL_DEFAULT_DISPLAY` — one
     process-wide handle, shared with Qt's scene graph and the platform renderer.
  And a sixth thing kept all of it invisible: the QML status text was `visible: cameraFrame
  === ""`, and `cameraFrame` is never cleared once set. A reopen that never connected looked
  exactly like one that worked.
  **Fix**: sources that hold a session at the camera now **block until they have let go of it**
  (bounded, 2 s), and every open and close runs on one serialised queue in the grabber — so
  "close, then open" is true rather than aspirational, without blocking the UI thread that
  called `stop()`. A failed EGL setup goes through `failAttempt()` like any other failure.
  Closed sources report no status at all, and each source's callbacks are pinned to a
  generation the grabber bumps on every teardown. Handlers are published before anything that
  reads them, and the cross-thread fields are `@Volatile`. `eglTerminate` is gone —
  destroying our own context and surface plus `eglReleaseThread()` is the whole job. The panel
  now shows a small "Camera unreachable" / "Connecting…" badge over a stale picture, so the
  next failure of this shape announces itself. Covered by `CameraFrameGrabberTest`.
  **The rule this leaves behind**: a `close()` that only posts its teardown is not a close.
  If the next thing the caller does is acquire the same exclusive resource, close must be
  synchronous — and then it must not be called from the main thread.

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

- **Symptom**: on the **phone**, the camera panel blanks for a frame on every snapshot — a
  black flicker at exactly the snapshot interval. (Observed on device 2026-07-24.)
  **Cause**: a QtQuick `Image` given a new `source` **drops its pixmap immediately** and
  then decodes on a worker thread (`asynchronous: true`), so between the two there is
  nothing to paint and the panel's own background shows through. `cache: false` guarantees
  a real reload every time. A second, rarer face of the same bug: `writeFrame()` rewrote one
  fixed cache file, so the loader could be reading `camera-frame.jpg` while the next JPEG
  was being written over it — a torn read fails the load and the panel stays blank until the
  *following* snapshot.
  **Fix** (2026-07-24): **double-buffer, both sides.** `MainActivity.writeFrame()` alternates
  between `camera-frame-0.jpg` and `camera-frame-1.jpg` (no file is rewritten while it may
  still be read; the alternating name is also what makes each URL differ, so the old hidden
  `?v=` cache-buster is gone). `Main.qml` holds **two** stacked `Image`s and a `showA` bool:
  the new URL goes to whichever is hidden, and the swap happens in `onStatusChanged` only on
  `Image.Ready`, with a 150 ms opacity `Behavior`. The visible still is never the one
  loading, so there is no blank frame — and a failed load simply never swaps, leaving the
  last good picture up.
  **The rule this leaves behind**: never point a live `Image` at a changing URL. Load into a
  spare and swap on `Ready`.

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

- **Symptom**: the app reports itself connected, but the gate reads `unknown` forever and
  Open/Close/Stop do nothing — while the legacy REST path (`POST /sendhc12`) moves the gate
  normally. Force-stopping the app fixes it until it happens again.
  **Cause (app, found 2026-07-24)**: `GateRepository` held owner slots but no client, and
  nothing in the class recovered from that. The whole reconnect machinery answers "the
  connection failed"; this is "the connection was never attempted", which looked identical
  on screen and had no path back. The owner count could reach it from both directions:
  - **too high** — `connect()` read `else if (owners > 1 || client != null) return`, so with
    two slots held and the client already retired, every later `connect()` returned without
    opening anything. Harmless until `ad92c4a` gave `teardown()` three callers that retire
    the client *without* dropping the count (`refresh()`, the broker-changed rebuild,
    `reconnectNow()`).
  - **too low** — `ArrivalPopUp` called `connect()` *inside* the `try` whose `finally`
    disconnects, so a throwing `connect()` released a slot it never took; and the car
    session's `onDestroy` released one whether or not its `connect()` had run yet. Either
    drops the count below what the phone UI holds, tearing down a connection still in use —
    and since `MainActivity.onStart` will not fire again while it is already on screen,
    nothing rebuilds it.

  What it looks like from outside, and how to confirm it in two commands: the app process is
  alive and its activity resumed, yet
  ```
  adb shell "cat /proc/net/tcp /proc/net/tcp6 | grep -i 075B"     # 075B = port 1883
  ```
  prints **nothing**, and the broker log shows the phone's last session ending cleanly with
  no attempt since (`docker logs mosquitto | grep <your client id prefix>`). Meanwhile
  `mosquitto_sub -t 'hc12/#' -v` shows `hc12/available online` and fresh retained
  `hc12/rx/Gate*` waiting to be collected. If a force-stop and relaunch fixes it, it is this
  class of fault and not anything you configured.
  **Fix (app)**: `connect()` now opens whenever there is no client, whatever the count says;
  `teardown()` resets the connection status so a retired client cannot leave `CONNECTED`
  behind; both miscounting call sites acquire and release symmetrically; and a 15 s watchdog
  enforces the invariant *somebody holds the connection ⇒ a client exists*, acting only when
  there is no client at all so it can never race the reconnect backoff.

- **Symptom**: everything is configured, the broker shows the app connected, and still no
  state and no actuation — with no error anywhere.
  **Cause**: a topic prefix typed without its trailing slash. `GateRepository` concatenates
  the prefix with a bare signal name, so `hc12/rx` yields `hc12/rxGateOpened` and `hc12/tx`
  yields `hc12/txOpenGate`. The broker accepts both the subscription and the publish; nothing
  ever matches; the settings row renders the value back exactly as typed, so it looks right.
  **Fix (app, 2026-07-24)**: `ConfigStore` normalises both prefixes on read, so a value
  already stored is repaired too, not just the next one typed.

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

- **Symptom**: **the whole head-unit screen dims and comes back** every snapshot interval —
  a smooth ~200 ms fade down and up, then the new still and text. Not a glitch; it looks
  like the app being switched off and on. (Observed in the Passat, 2026-07-24.)
  **Cause**: that fade is the host's **screen-transition animation**. The Car App Library
  updates a template *in place* only when the new one counts as a **refresh** of the
  previous one. `GateScreen` was appending a rotating `- / | \` spinner to the
  first row's title on every build (commit `f3547cf`, to stop a *frozen* still), and its row
  count flipped 1↔2 as the error/distance line appeared and disappeared. So every snapshot
  was, by construction, a full template transition.
  **Fix** (2026-07-24): make the pane's shape constant — **exactly one row, always**: status
  as the title, error and distance as its text lines (a pane row allows two). The spinner is
  gone. Between snapshots the only difference is the bitmap, which is what the refresh path
  is for. Also `debounce(150 ms)` on the merged invalidate flow, so a snapshot and a distance
  reading landing together no longer push two templates back to back.
  **Correction (2026-07-27)**: the rule was written up above as "same number of rows, same
  strings", and that is **wrong** — see the "not allowed while driving" entry below. The
  compared set is the template title, the row *count*, and each row's *title*; row texts,
  the image and the actions are free. The 2026-07-24 fix was therefore half right (row count)
  and half harmful (status in the row title), and the half it got wrong is what closed the
  app while driving three days later.
  **Watch for the flip side**: this is the exact trade the spinner was making. If the still
  goes back to being *frozen*, the host is not repainting the pane image on a refresh, and
  the answer is a coarse deliberate change (e.g. a minute-resolution stamp on a row *text*
  line, which is free) — blink once a minute, not once a snapshot. Do not go back to a
  per-snapshot spinner in the row **title**.

- **Symptom**: on the head unit, after a few minutes of driving, the app is replaced by
  **"this action is not allowed while driving"** and closes. Switching to navigation and back
  makes it work again. Never reproduces while parked. (Observed in the Passat, 2026-07-27.)
  **Cause**: the host's **template quota** — five template pushes per task, after which it
  "displays an error message to the user before closing the app". A push is free only if it
  is a *refresh*, and the AndroidX javadoc defines that for `PaneTemplate` as: template title
  unchanged, row count unchanged, **and each row's title unchanged**. Row `addText(…)` lines,
  the pane image and the action labels are **not compared**. `GateScreen` had
  `Row.setTitle(state.statusText)` — the single most volatile value in the single compared
  slot — so every status change, every reconnect and every `lastError` set-and-expire spent a
  step. The camera-less `GridTemplate` was worse: its template title was
  `"<status> · <distance>"` and its first item title flipped `"Open gate"`/`"Close gate"`, so
  *every* push was a step, and `GridTemplate` is not a legal *last* template for a task
  either. Backgrounding to Maps and returning fixes it because re-entering from the launcher
  **resets the quota**; parking lifts the restriction entirely, which is why the DHU never
  showed it.
  **Fix** (2026-07-27): constant template title (`app_name`) and constant row title
  (`"Gate"`); status on text line 1, error-else-distance on text line 2; the gate state moved
  into the pane *image* on camera-less installs (`ic_gate_state_*`), which is also free.
  `GridTemplate` deleted — one `PaneTemplate` for every configured state. Every update is now
  a refresh, so the quota never advances.
  **Rule of thumb for this screen**: if a value moves, it belongs in a row text, the image, or
  an action label. Never in a title.

- **Symptom**: opening the Domofon car screen (or the phone app, or Settings) immediately pops
  a heads-up notification about a gate that has not moved. (Artur, 2026-07-27.)
  **Cause**: `GateEventNotifier` filtered with `.drop(1)`, which drops one value in the life
  of the *process* — the initial `unknown`. But `teardown()` resets `gateState` to `unknown`
  on every disconnect ([gate](modules/gate.md) invariant 4), and the next connection *learns*
  the real state from the retained `rx` topics. `unknown → closed` is a new value, so
  acquiring a lease looked exactly like the gate moving. The comment in
  `modules/ui-notifications.md` claiming `drop(1)` "skips the state a surface connects into"
  was simply false. Same mechanism made `ArrivalFlow` post notification 1001 about 750 ms
  before the arrival pop-up 1002 it exists to deliver.
  **Fix**: `domain/StateChangeAnnouncer` — a transition out of `unknown` is learning, not
  news; and a command silences the next state change (only the next, consumed by it) so your
  own tap does not announce the `opening` you caused.

- **Symptom**: the *second* arrival pop-up of the day never draws a heads-up over Maps — it
  appears in the shade and nowhere else. Related: getting into the car in the morning, the
  phone is already showing "Approaching home" while the car has not moved. (Artur, 2026-07-28.)
  **Cause**: one cause, both symptoms. The arrival notification carried `setAutoCancel(true)`
  and nothing else, and `setAutoCancel` fires only on a **tap** — so an ignored pop-up stayed
  in the shade indefinitely. That is the stale "Approaching home"; and because id 1002 was
  still live, the next `notify(1002, …)` was an **update** of an existing notification rather
  than a new one, which the car host does not draw a heads-up for.
  **Fix**: `setTimeoutAfter` on all three notifications — arrival 30 s (was 5 min; see the
  cooldown entry below), event and failure 10 min — plus `GateNotifier.clearTransient` when a
  Domofon surface comes to the front. The arrival timeout is deliberately shorter than the
  window in which another arrival may be announced, so the id is
  always free by the time another arrival is permitted. **Do not** "fix" this by cancelling
  immediately before re-posting: `cancel` and `notify` are asynchronous and the notify can be
  swallowed, which trades a missing heads-up for a missing notification.
  **Still worth checking on the device**, because no app-side change reaches it: Android Auto →
  Settings → Notifications, that Domofon is permitted. A disabled toggle there gives exactly
  the "no pop-up over Maps" symptom.

- **Symptom**: the gate is opened from the wall button; a heads-up says `Gate: opening` over
  Maps, and then **nothing** when it finishes — `Gate: opened` only ever appears as a shade
  entry. Same for any second gate movement inside ten minutes.
  **Cause**: the same update-is-not-a-heads-up fact as the arrival entry above, but time cannot
  fix this one. A gate cycle is *two* announcements fifteen to twenty-five seconds apart, and
  the second landed on id 1001 while the first was still live (`EVENT_TIMEOUT_MS` is 10 min).
  An update changes the shade entry and draws nothing.
  **Fix** (D16): a second event id, 1004, and `domain/freeNotificationSlot` picks whichever of
  the pair is not in `NotificationManager.getActiveNotifications()`, cancelling the other
  first. **Do not** "fix" this by shortening `EVENT_TIMEOUT_MS` — it would have to be under a
  travel time nobody can predict — and do not cancel-then-repost onto 1001, for the reason in
  the arrival entry. Cancelling a *different* id than the one being posted is what makes this
  safe where a same-id cancel is a race.
  **If a state change stops appearing at all**, check `clearTransient`: it must cancel **both**
  1001 and 1004, or one slot stays occupied forever and every other announcement is an update.

- **Symptom**: a heads-up pops over the Domofon car screen itself, announcing a state that
  screen is already displaying next to a button that acts on it. (Artur, 2026-07-28.)
  **Cause**: `GateNotifier` was context-blind — nothing in the app tracked whether a Domofon
  surface was in front of the user, so there was no way to ask.
  **Fix**: `ui/shared/SurfacePresence`, written from `CarGateSession`'s lifecycle observer and
  `MainActivity.onStart`/`onStop`, read by `StateChangeAnnouncer` (rule 3) and `ArrivalFlow`.
  Command failures are exempt. See [ui-notifications](modules/ui-notifications.md) invariant 12
  and [D14](architecture/decisions.md). If notifications ever go *permanently* silent, suspect
  a stuck flag first — which is why `MainActivity.onStop` clears it before every early return.

- **Symptom**: notification body reads `Changed at 2026-07-27T18:34:12+02:00`. (Artur,
  2026-07-28.)
  **Cause**: `GateState.changedAt` is the `ts` string exactly as the bridge published it, and
  `GateNotifier` printed it.
  **Fix**: `domain/GateTimestamp.hourMinute` → `Changed at 18:34`. Fixed 24-hour pattern, not
  a locale short-time format (12-hour in several locales). That file is now also the single
  wire-timestamp parser; `GateProtocol` calls it.

- **Symptom**: `Close gate` never appears while the gate is `opening`; the one button on the
  car screen and the notification both offer `Open gate`, which does nothing. (Artur,
  2026-07-27.)
  **Cause**: `GatePolicy.primaryAction` was `state == "opened"`. Every mid-travel state fell
  into the else branch.
  **Fix**: `opened`, `opening` and `stuck_opening` offer Close; the rest offer Open. Free on
  the car screen — action labels are not part of the template refresh comparison.

- **Measured template limits** (read out of `androidx.car.app:app:1.7.0` itself, so no need
  to re-derive them): a **`Pane` takes at most 2 actions** —
  `PaneTemplate.Builder.build()` validates against
  `ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`, `maxActions = 2`, `maxCustomTitles = 2` —
  which is why the car screen offers the primary action plus Stop and not the phone's three
  buttons. A **pane row takes 2 text lines** and no click listener
  (`ROW_CONSTRAINTS_PANE`). A template `ActionStrip` is capped at 2 actions with only 1
  custom title (`ACTIONS_CONSTRAINTS_SIMPLE`). Over-stepping any of these is an
  `IllegalArgumentException` at *runtime*, not a compile error — a green build proves
  nothing here.

## Icons and theming

- **Symptom**: the app icon is **invisible on any light background** — the launcher, the app
  info screen, the Android Auto header. The white "gate bars" are there on a dark wallpaper
  and gone on a white one. (Observed on device 2026-07-24.)
  **Cause**: `android:icon` pointed at `@drawable/ic_gate` — a bare 24 dp vector, solid
  `#FFFFFFFF` paths on a transparent canvas, with no background layer. A modern launcher
  treats a non-adaptive icon as *legacy* and composites it onto a **white shim**: white art
  on a white plate. The same applies to every white-only vector this app hands to the car
  host, on a light head-unit theme.
  **Fix** (2026-07-24): a real adaptive icon —
  `res/mipmap-anydpi-v26/ic_launcher.xml` (+ `_round`), with
  `@color/ic_launcher_background` (`#1E1E2E`, the QML scene colour) as the background layer,
  `@drawable/ic_gate_foreground` (the same art, scaled onto the 108-unit canvas inside the
  72-unit safe zone) as the foreground, and the same drawable as `<monochrome>` for Android
  13 themed icons. Manifest now uses `@mipmap/ic_launcher` + `android:roundIcon`. `minSdk` is
  28, so `-v26` resolves on every supported device and there is no legacy PNG set to keep in
  sync. In the car, resource icons go through `GateScreen.themedIcon()`, which sets
  `CarColor.DEFAULT` as the tint and lets the host recolour them for whichever theme it is
  drawing.
  **Not** changed, deliberately: `GateNotifier` still uses `R.drawable.ic_gate` as the
  notification small icon. That slot *wants* a white silhouette on transparency — the system
  tints it — and is the one place the old asset was right. The camera still is never tinted
  either; tinting a photograph flattens it.

## Geofencing

- **Symptom**: geofence never fires. **Start at Settings → "Arrival trigger status"** — since
  2026-07-27 the app records this and the row tells you which of the three failures you have:
  | It says | Then |
  |---|---|
  | `NOT registered — needs "Allow all the time"` | permission; the row is the fix |
  | `Registration failed: GEOFENCE_NOT_AVAILABLE` | device location off, or set to battery-saving |
  | `Registered …` + `no arrival seen yet` | armed and never evaluated — battery restriction (needs *Unrestricted*), or not re-registered since a reboot/update |
  | `last arrival …` but no pop-up | delivery worked; read `Last event ignored: …` — it names the guard that dropped it — or suspect the broker |

  Then: radius < 150 m (too small).

- **Symptom**: the *second* inward crossing of one drive produces no pop-up. Out past the fence
  and back → pop-up, as intended; immediately out and back again → nothing, with the head unit
  connected the whole time. (Artur, live testing 2026-07-28.)
  **Cause**: `ArrivalFlow`'s guard was a flat **ten-minute cooldown** (`ARRIVAL_COOLDOWN_MS`),
  and the second crossing landed inside it. Settings said so honestly —
  `Last event ignored: another pop-up was posted minutes ago` — which is the D13 status model
  working correctly and reporting a rule that was wrong. The cooldown was never meant to
  rate-limit arrivals: its one job is collapsing *one* approach noticed by both triggers seconds
  apart, and ten minutes is roughly twenty times what that needs.
  **Fix** (D15): `arrivalRefusal` takes the claiming `source` and refuses on two grounds,
  neither of them "too soon" — a **different** trigger within `CROSS_TRIGGER_WINDOW_MS` (90 s),
  i.e. the same crossing seen twice; and any repost within `ARRIVAL_POPUP_TTL_MS` (30 s), which
  is a rule about notification id 1002 rather than about arrivals. Every genuinely separate
  inward crossing announces, however soon it follows.
  **If you are tempted to widen either window**, note that the checklist line "fire the debug
  trigger twice inside ten minutes → exactly one pop-up" was *inverted* by this fix: two pop-ups
  is the correct outcome now.
  **Also check** `Last event ignored: a Domofon screen was in front` — that is a different guard
  (D14 point 1, deliberately kept) and has a different fix.

- **Symptom**: the fence was never registered, and Settings says so, but the user is sure they
  granted location. (2026-07-27.)
  **Cause**: Android 12+ shows a **Precise / Approximate** choice whenever an app could accept
  either. Tapping "Approximate" grants `ACCESS_COARSE_LOCATION` and leaves
  `ACCESS_FINE_LOCATION` *denied* — `GeofenceManager.hasPermissions()` then returns false and
  `sync()` refuses, permanently, because nothing asks again.
  **Fix**: declare `ACCESS_COARSE_LOCATION` in the manifest and request it **together with**
  FINE, which is what makes "Precise" a real choice. Background stays a separate request — a
  combined foreground+background request is denied outright.

- **Symptom**: nothing repairs the fence for a user who only ever opens the app on the head
  unit. (2026-07-27.)
  **Cause**: `sync()` was called from activity starts, Settings and `BOOT_COMPLETED` only. A
  car session starts no activity, and an app update drops geofences on many OEMs with no boot
  to follow.
  **Fix**: `sync()` from `CarGateSession.onCreateScreen`, and `MY_PACKAGE_REPLACED` added to
  `BootReceiver`.

- **Symptom**: "Approaching home" is on screen while the car is parked on its own driveway.
  (Artur, 2026-07-28.)
  **First check whether anything actually fired.** In the observed case nothing had — it was
  the previous drive's pop-up, never dismissed, still in the shade; see the notification entry
  above. Settings → *Arrival trigger status* settles it: if `Last pop-up` is hours old, no
  trigger fired and this is that entry, not this one.
  **If a trigger really did fire on the driveway**, `Last pop-up … (in-app)` means a cold
  cell-derived fix landing kilometres off followed by a good one, which under a bare
  `meters <= radius` is a textbook outside→inside crossing. Fixed by
  [geo](modules/geo.md) invariant 9 (`sideOf`): a side is claimed only when the fix's own
  accuracy does not span the fence. `(Play Services)` means GMS delivered an ENTER while
  parked — plausible after `sync()` re-registers the fence, since a re-added fence has no
  tracked state inside GMS. **Nothing in the app guards against that, deliberately** — see
  invariant 8 and [D14](architecture/decisions.md). An ENTER *is* a direction; refusing one
  because the app had not separately witnessed the departure drops real arrivals (phone off at
  home, on again on the way back), which is a far worse bug than one redundant pop-up that
  expires by itself in 30 s.
  **Diagnostic, not a gate**: the `Last seen: inside/outside the fence` line says whether Play
  Services delivered the EXIT on the way out. No EXIT and no ENTER on a real round trip means
  the fence is dead, not late.

- **Symptom**: fires very late.
  **Fix**: larger radius (300–500 m); `setNotificationResponsiveness` is 0 since 2026-07-27
  (it was 30 s, which is most of a kilometre at road speed) — but it is only a *hint* to Play
  Services, which may be slower and never reports what it actually chose. Accept that
  geofencing is approximate by design; if it matters, switch on **"Also watch from inside the
  app"**, whose cadence you can at least see on the car screen.

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

- **Symptom**: the **DHU** shows a left app rail with **two apps side by side**, and
  Android Auto's launcher/dashboard, none of which the real car does — the car runs one
  app full-screen.
  **Cause**: that is Coolwalk's **two-pane widescreen layout**, which Android Auto renders
  on a **wide aspect ratio**. The panel's native 1280×640 is 2:1 — wide enough to tile.
  (Forcing dpi does *not* fix this: dpi was tried at 240 and 320 and the DHU still tiled;
  the trigger is the aspect, not the dp width.)
  **Fix**: drive the DHU at a **narrower aspect** in `scripts/passat-b8.ini`. It uses a 5:3
  aspect at native `dpi = 160` — `resolution = 1280x720` with `marginwidth = 80` cropping it
  to 1200×720 (margins subtract total pixels; drop to `800x480` for the low-res 5:3 base).
  This trades the car's exact 2:1 geometry for matching its single-pane behavior. Push the
  resolution up at the same aspect via the 1920×1080 base (`marginwidth = 120` → 1800×1080);
  if tiling returns as you raise it, the trigger is dp width, not aspect.

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

- **Symptom** (preemptive; **dormant since 2026-07-24** — media3 is no longer a dependency,
  so this only bites again if audio playback brings it back): bumping `androidx.media3`
  past 1.10.x fails the AAR metadata check with *"requires minCompileSdk 37"*.
  **Cause**: media3 1.10.1 already declares `minCompileSdk=36` — exactly this project's
  `compileSdk`; the next minor moves past it. Same trap as core-ktx 1.19.0.
  **Fix**: stay on 1.10.x until `compileSdk` moves. And remember media3 is a fresh
  reflective surface under R8 — re-test in a **release** build after any media3 or R8
  change; debug proving nothing still holds.

- **Symptom**: the phone shows `Gate: unknown` with no error and the app looks like it
  never reaches the broker — while `mosquitto_sub -h <host> -u <user> -P <pass> -t
  'hc12/available' -W 2` from a PC on the same LAN works perfectly.
  **Cause**: usually *nothing is wrong with the connection*. Until this was fixed, five
  completely different states all rendered as that same silent screen: never connected;
  connected but the broker holds no retained `hc12/rx/Gate*`; connected but every payload
  was dropped for an unparseable `ts`; connected but the subscriptions were ACL-denied;
  and connected and perfectly healthy. `GateRepository.connected` existed but nothing
  consumed it, and `Main.qml` only ever showed an error when `hc12/available` actively
  published `offline`.
  **Fix**: `GateRepository` now exposes `ConnectionState` (`DISCONNECTED / CONNECTING /
  CONNECTED / DEGRADED / FAILED` + a user-facing reason) and every surface binds it. The
  phone says "Connected — no gate state reported yet" for the healthy-but-empty case,
  which is the one that started this.
  **To diagnose the same class of problem without a UI**, two commands settle it:
  `mosquitto_sub -t 'hc12/#' -v -W 5` shows what is actually retained (and whether each
  payload really carries an ISO-8601 `ts` — `GateRepository.onMessage` silently drops
  anything else), and on the phone, with the app running:
  ```
  adb shell "cat /proc/net/tcp /proc/net/tcp6 | grep -i 075B"
  ```
  `075B` is port 1883; state `01` is ESTABLISHED. A socket there means the app is
  connected no matter what the screen says. Note the app's uid is in the 8th column —
  match it against `adb shell dumpsys package pl.bitforge.domofon | grep userId`.

- **Symptom**: after a Wi-Fi drop, a VPN flap or moving between networks, the app never
  reconnects — not slowly, *never*, until it is force-stopped. `adb logcat -s Domofon:W`
  repeats `MQTT disconnected (SERVER)` / `CONNECT failed as CONNACK contained an Error
  Code: NOT_AUTHORIZED` with a lengthening backoff. Meanwhile a cold start of the app
  connects instantly. **This was the original "the app does not connect to my broker"
  report** — with no connection UI, one network transition left the app permanently mute
  and there was nothing on screen to say so.
  **Cause**: HiveMQ's `automaticReconnect`. Measured on device, this is the whole
  experiment:

  | attempt | result |
  |---|---|
  | HiveMQ auto-reconnect after the flap | `NOT_AUTHORIZED`, indefinitely |
  | cold start seconds later, same credentials, same client id | connects immediately |

  So it is not the credentials, not a client-id collision with a stale session, and not
  broker-side rate limiting — a client built from scratch is accepted at the very moment
  the reconnector is being refused. Whatever the reconnector re-sends is not the CONNECT
  that was handed to the builder; a consumed password buffer fits the evidence.
  **Fix**: `automaticReconnect` is gone. `GateRepository.scheduleReconnect` rebuilds the
  whole client on any non-USER disconnect, with the same 1 s → 30 s backoff, guarded by
  the connection epoch so a burst of disconnect events cannot stack up rebuilds. Verified:
  recovery in ≤ 10 s with zero auth failures, where before it never recovered.
  **If you hit this again**, the discriminator is cheap and conclusive — reproduce the
  failure, then `adb shell am force-stop pl.bitforge.domofon` and relaunch. If the cold
  start works, the fault is in the reconnect path, not in anything you configured.

- **Symptom**: the app dies a few seconds after launch, every launch, with an RTSP URL
  configured. `adb logcat -b crash -d` shows `JNI DETECTED ERROR IN APPLICATION: non-zero
  capacity for nullptr pointer` in `nativeCreatePlanes`, on the `camera-grab` thread.
  **Cause**: `CameraFrameGrabber` points ExoPlayer's video output at an `ImageReader`
  surface and then reads `Image.getPlanes()`. MediaCodec is free to return buffers that
  live only on the GPU, and reading a plane from one of those aborts the process from
  native code — it is not a Java exception, so the `try/catch` around the conversion never
  sees it. Reproduced on a Samsung SM-G990B2 (Exynos), Android 16. Adding
  `HardwareBuffer.USAGE_CPU_READ_OFTEN` to `ImageReader.newInstance` does **not** fix it,
  and `Image.getHardwareBuffer()` returns nothing useful, so the condition cannot even be
  probed for before the fatal read.
  **Fix** (2026-07-24, **resolved**): the `ImageReader` is gone, not the decoder.
  `OffscreenTextureReader` gives the player a `SurfaceTexture` on an offscreen EGL context,
  draws that external texture into an FBO sized to the target, and `glReadPixels` back into
  a `Bitmap`. Reading a GPU-only buffer *with the GPU* is exactly what it is for, and the
  scaling comes free in the draw. The `ENABLED` flag is gone too — there is nothing left to
  switch off. See ch. 04 §1.1.
  **The rule this leaves behind**: never read decoded video frames on the CPU — no
  `ImageReader`, no `getPlanes()`. GL readback, or nothing. Live *playback* was never
  affected, so audio may use a player freely.
  **Two ways the GL path goes wrong**, both visible at a glance rather than as a crash: a
  picture that is upside down (swap the V values in `OffscreenTextureReader.TEX_COORDS`),
  and a green strip down one edge (the `SurfaceTexture` transform matrix is being ignored,
  so the decoder's macroblock padding is being sampled).

- **Symptom**: with audio playing from the gate, the *picture* freezes and logcat shows
  `camera: playback error ERROR_CODE_IO_UNSPECIFIED` on a loop — one failure every reconnect,
  the audio unaffected. Seen the moment live audio was added.
  **Cause**: audio was first built as a *second* ExoPlayer (`RtspAudioPlayer`) opening its own
  RTSP session to the same camera, in parallel with `RtspFrameSource`'s. **This camera allows
  only one RTSP session** — the second connection knocks the first off (and over the VPN two
  streams also compete for the tunnel). Whichever connected second won; the stills lost.
  Reproduced on SM-G990B2, 2026-07-24, every foreground cycle.
  **Fix**: **one RTSP session per camera.** Audio was folded into `RtspFrameSource` — the same
  player keeps the audio track (`setTrackTypeDisabled(AUDIO, !audioEnabled)`) and renders it to
  the speaker while it renders video to the GL surface. `RtspAudioPlayer` was deleted. One
  handshake, no contention, and the least data over the VPN. If a future need ever wants audio
  and video on genuinely independent lifecycles, the answer is a go2rtc restream (one ingest,
  many clients), **not** a second direct connection — assume cameras cap concurrent sessions.

- **Symptom**: gate audio plays but is **choppy / stuttering** — on Wi-Fi and over the VPN
  alike, with or without the head unit connected, so it is not bandwidth. The *same* camera's
  audio is smooth when viewed in Frigate.
  **Cause**: media3's RTSP client renders the camera's raw RTP timing more or less literally,
  and this camera's audio RTP is irregular enough that the audio renderer underruns. Frigate
  is not a fair comparison: it plays **go2rtc's** re-muxed output, and go2rtc reorders and
  re-timestamps the packets — exactly the jitter-smoothing media3's RTSP path does not do. And
  for a *live* source more buffering cannot help: there is no future audio to pre-buffer.
  **Fix / workaround**: point **Camera address (RTSP)** at a go2rtc RTSP restream instead of
  the camera — `rtsp://<host>:8554/<stream>` (Frigate bundles go2rtc; its stream names come
  from the config, e.g. `drzwi_main`). This stays inside the one-URL model — the app still
  requires no backend of its own; a user who already runs go2rtc/Frigate just points at it.
  Confirmed smooth on Artur's setup 2026-07-24. **Accepted limitation**: a raw-camera user
  with no go2rtc may still hear choppiness on a camera whose RTP timing is irregular; revisit
  if media3's RTSP jitter handling improves. Use the `_main` stream — audio is usually absent
  from substreams.

- **Symptom**: the snapshot URL returns a picture instantly with `curl` from the laptop,
  and the app shows "Camera unreachable" forever. `adb logcat -s Domofon:W` says
  `camera: snapshot fetch failed (UnknownServiceException)`, and unfiltered logcat has
  `CLEARTEXT communication to 192.168.x.x not permitted by network security policy`.
  **Cause**: Android blocks cleartext HTTP by default from API 28 on. Nothing in this app
  had ever tripped it — MQTT is a raw socket the platform does not inspect as HTTP, and
  RTSP is not HTTP either — so the camera snapshot was the first request to meet the
  policy.
  **Fix**: `res/xml/network_security_config.xml` (`cleartextTrafficPermitted="true"`),
  referenced by `android:networkSecurityConfig` on `<application>`. It cannot be scoped to
  a host list: every address in this app is typed by the user at runtime, by design. If the
  camera ever gets a certificate, just use `https://` in the setting — the config permits
  cleartext, it does not require it.

- **Symptom**: the snapshot never appears; logcat says *"the snapshot endpoint requires
  Digest auth, which this client does not speak"*.
  **Cause**: exactly what it says. `HttpURLConnection` implements Basic only, and most
  Hikvision/Dahua firmware insists on Digest. The credentials are correct; the picture is
  still never coming.
  **Fix**: put go2rtc in front of the camera and point the setting at
  `http://<host>:1984/api/frame.jpeg?src=gate`. It handles the camera's auth and quirks,
  and it is the same component that would later transcode the audio. (Implementing Digest
  in the app is ~60 lines and was deliberately not done — go2rtc solves more problems.)

- **Symptom**: the snapshot shows an old picture and the status says unreachable, but the
  camera is fine.
  **Cause**: by design. A failed fetch keeps the last good frame — a gate picture from
  thirty seconds ago beats a grey placeholder — and retries on a fixed 30 s backoff that is
  independent of the configured interval. So a camera that recovers can take up to 30 s to
  show it, no matter how low *Snapshot interval* is set.

- **Symptom**: `scripts/build-release.sh` fails on a **clean** release build while
  `build-debug.sh` is fine, with `compileReleaseKotlin` reporting `Unresolved reference
  'qtproject'` / `QtQuickView` / `QtQmlStatus` in `MainActivity.kt`. Confusingly it may
  *succeed* if run twice, and the version never advances (0.1.0 recomputed each time).
  **Cause**: two independent things. (1) The Qt Gradle Plugin 1.4 hooks its AAR-generation
  task (`QtBuildTask`, which produces `qt_generated/aars/domofon.aar` with the QtQuickView
  classes) into the *debug* compile tasks but, for release, only into the later
  assemble/collect/lintVital tasks — **not `compileReleaseKotlin`**. On a clean build Gradle
  runs the release Kotlin compile before the Qt AAR exists, so the `aars/*.aar` fileTree is
  empty and every `org.qtproject.*` symbol is unresolved. It "works the second time" only
  because a prior run left the AAR on disk. (2) The build-release script tags `v<next>` only
  *after* a successful build, so a failed build never bumps the baseline — hence the same
  0.1.0 every attempt (this part is by design, just surprising when the build keeps failing).
  **Fix** (2026-07-24, **resolved**): in `app/app/build.gradle.kts`, make the release compile
  tasks depend on the Qt task:
  `tasks.matching { it.name == "compileReleaseKotlin" || it.name == "compileReleaseJavaWithJavac" }.configureEach { dependsOn("QtBuildTask") }`.
  Verified with a clean `:app:bundleRelease :app:assembleRelease` (R8 on) in a scratchpad
  copy — both the `.aab` and the release `.apk` build. Still to prove on device (R8).

## Build machine / host resources

- **Symptom**: `scripts/build-release.sh` freezes the whole desktop. Screen black, mouse
  still moving but at ~0.5 fps, keyboard dead, no OOM message, no recovery — nine minutes
  of it on 2026-07-24 before a hard reboot. It had happened before, more mildly.
  **Cause**: the build was the last straw, not the cause. Three things stacked up:
  1. **The machine was already starved.** `~/.gradle/daemon/<ver>/daemon-<pid>.out.log`
     records system-wide `MemAvailable` every 5 s (Gradle's `MemInfoOsMemoryInfo` reads
     `/proc/meminfo`), and it is the best forensic record you have of a freeze — it
     survives the reboot. That night it showed available RAM under 3 GB from 18:49 onward,
     0.29 GB at 19:46, and **0.58 GB at 21:33:51**. The release build started at 21:34:05.
  2. **A 3.1 GiB Gradle worker daemon squatted for 2h45m.** Gradle's default
     `org.gradle.daemon.idletimeout` is 10800000 ms — three hours. From 18:49 the log
     repeats `3334298009 physical memory requested … 0 released` every 5 s, **3474 times**:
     Gradle trying and failing to reclaim its own worker on a machine with nothing to give.
     And it was not one Gradle stack but **two**: the Qt Gradle Plugin runs a *nested*
     Gradle build out of `app/build/qt_generated/qtquickview/android-build-<target>/` with
     its **own wrapper — Gradle 9.3.1, against the outer project's 9.4.1** — and its own
     generated `gradle.properties` carrying another `-Xmx3200m` **and
     `org.gradle.parallel=true`**. `~/.gradle/daemon/9.3.1/` has its own daemon logs, and
     they show that daemon alive at **21:41:56 with 301 MB free**, logging the identical
     `0 released` spiral while the machine was already frozen. Two independent 3.2 GB-heap
     daemon stacks, each spawning its own workers.
  3. **Nothing on the host could break the deadlock.** Swap is one 3.7 GB partition, there
     is no zram, and neither `earlyoom` nor `systemd-oomd` is installed. With nothing to
     kill and nowhere to swap, the kernel reclaim-thrashes instead. A frozen desktop with a
     live mouse pointer *is* that thrash.

  **Fix** (2026-07-24): `scripts/lib/memguard.sh`, sourced by both build scripts.
  * `preflight_memory` refuses to start a build below a threshold (10 GB release, 6 GB
    debug) and prints the fattest processes, so the refusal says what to close. It runs
    before the version math in the release script, so a refusal can never leave a tag
    behind. `./gradlew --stop` is attempted first — that alone would have returned ~3 GB
    that night. Bypass: `DOMOFON_SKIP_MEM_CHECK=1`.
  * `run_capped` runs Gradle inside a transient `systemd-run --user --scope` with
    `MemoryHigh` / `MemoryMax` / `MemorySwapMax`. A runaway build is OOM-killed **inside its
    own cgroup** and the session survives. Requires cgroup v2 with the memory controller
    delegated to `user-<uid>.slice`, which Debian 13 + KDE already does; if it is missing
    the helper warns loudly and runs uncapped rather than refusing to build.
  * Both scripts pass **`--no-daemon`**, and that is measured, not assumed: a process that
    detaches inside a scope does *not* die with it — it keeps running and stays in that
    scope's cgroup, which therefore also stays alive. A surviving Gradle daemon would make
    the next build's cap meaningless (the client joins the old daemon and does its work in
    the *old* cgroup), and brings the squatter straight back.
  * **`--no-daemon` alone is not enough**, because it only governs the outer build — Qt's
    nested Gradle build starts a daemon of its own regardless. So after the command
    returns, `run_capped` reaps whatever is still in its own cgroup (TERM, then KILL after
    5 s). That is precise rather than blunt: only this build ever put processes in that
    cgroup. Observed on a clean debug build: *"reaping 5 process(es) the build left
    behind"*, after which the scope disappears and no Gradle JVM survives. One scope, one
    build, nothing outlives it. The cost is a cold Gradle start per build.
  * Measured peaks inside the cgroup, for calibrating the caps: **4140 MB** clean debug,
    632 MB incremental debug, and **4528 MB for a release build in which R8 genuinely ran**
    (`minifyReleaseWithR8` executed, along with packaging and bundling; the Qt native and
    Kotlin stages were up-to-date from an earlier build, so a fully clean release would sit
    somewhere above this — the two heavy phases are largely sequential, so expect ~5–6 GB
    rather than anything near the ceiling). Debug is capped at 9G, release at 12G. Both
    have room; neither has ever reached `MemoryHigh`.
  * `app/gradle.properties` gained `kotlin.daemon.jvmargs` (the Kotlin daemon is a second
    JVM and silently inherits `org.gradle.jvmargs` — two 3.2 GB ceilings where one was
    meant), `org.gradle.workers.max=6` (12 cores would allow 12 concurrent dex/R8 workers),
    and `org.gradle.daemon.idletimeout=1800000`. `org.gradle.jvmargs` was deliberately left
    at `-Xmx3200m`: the "heap usage: 3% of 3.1 GiB" in the log is measured at build *start*
    and says nothing about R8's peak.

  **Not fixed, and it is a host decision rather than a repo one** — `MemoryMax` limits, it
  does not reserve. If Firefox, Chromium, Steam (a running game showed up as a 6 GB
  `GameThread`) and Docker have already taken 30 GB, the pre-flight will refuse to start
  and that is the intended behaviour. To make the *machine* survive memory pressure rather
  than just the build: install `earlyoom` (`1.8.2-1` is in Debian 13, not installed here)
  or enable `systemd-oomd`; add zram to back up the 3.7 GB swap partition; and note that
  `/tmp` is a **16 GB tmpfs**, so the agent's mandated `/tmp/claude-*/scratchpad` build
  copies are unreclaimable RAM until deleted or rebooted — build somewhere disk-backed.

## Backlog / future ideas

(park post-M8 wishes here)

- **Demo mode** — simulated gate state with no broker. Would remove the most likely Play
  rejection (a reviewer cannot reach your broker or your VPN, so the app looks broken to
  them) and makes the background-location demo video far easier to record. See ch. 11 §5.4.
- **Confirmation step on the car screen** — currently one binder-level click moves the
  gate. The host validator is the real control, but a confirmation template would mean a
  misconfigured validator is no longer sufficient on its own.
- **`res/raw/keep.xml`** so resource shrinking can be turned back on.
- ~~**Live audio from the gate camera**~~ — **done 2026-07-24, on the phone.** Audio is folded
  into `RtspFrameSource` (one RTSP session — see the concurrent-session entry above), gated by
  a **Camera audio** switch (on by default). ExoPlayer handles focus
  (`setAudioAttributes(..., handleAudioFocus = true)`, usage MEDIA / content SPEECH) so a nav
  prompt ducks the gate audio rather than being silenced by it. Codec on the test camera is
  **AAC**; and **G.711 is not a problem** either (the old note was wrong) — media3 1.10.1's
  RTSP stack decodes PCMA/PCMU, AAC, AC-3, AMR/AMR-WB, Opus and raw PCM with no per-codec code,
  so only a codec outside that set (e.g. G.726) would need a go2rtc transcode. Left to prove:
  audio during an **Android Auto** session, and nav-ducking in the car.
- ~~Replace the RTSP frame grab with an HTTP JPEG snapshot~~ — **superseded 2026-07-24.** It
  was built and it worked, but it made the app camera-brand-dependent; the frame grab came
  back on a safe (GL) footing instead, and the HTTP path stayed as an optional override.
  See the `nativeCreatePlanes` entry above.
