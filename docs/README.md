# Domofon — Design Record

The design, the reasoning and the acceptance tests behind the **Domofon** entry-phone
Android app: RTSP gate camera (video + audio) in a QML view, gate control and live state
over MQTT, Android Auto integration, and a geofence-triggered pop-up — all reachable from
anywhere through OpenVPN.

## How to use these chapters

1. Read [00-architecture.md](00-architecture.md) first — it explains every component and
   **why** it is designed this way (including two hard Android Auto constraints).
2. Each chapter ends with an **acceptance test**, and the chapters map 1:1 to the
   milestones in [../MILESTONES.md](../MILESTONES.md). A milestone is not ticked until
   its test has passed *on the hardware*, which is the one thing Claude cannot do.
3. Read the chapter before changing the code it describes: several of them record a
   constraint that is not visible in the code itself.
4. When something breaks, check [10-troubleshooting.md](10-troubleshooting.md) first.
   Resolved problems get appended there (Symptom → Cause → Fix) so they are never solved
   twice — several entries below cost a whole session each.

## Chapters

| # | Chapter | Builds | Milestone |
|---|---------|--------|-----------|
| 00 | [Architecture](00-architecture.md) | understanding | — |
| 01 | [Dev environment (Debian 13)](01-environment-debian13.md) | toolchain | M0 |
| 02 | [MQTT contract + bridge service](02-mqtt-bridge.md) | `bridge/` | M1 |
| 03 | [App scaffold: Kotlin + embedded QML](03-app-scaffold.md) | `app/` skeleton | M2 |
| 04 | [RTSP video + audio in QML](04-rtsp-video-audio.md) | camera view | M3 |
| 05 | [Gate control + live state](05-gate-control-state.md) | MQTT in app | M4 |
| 06 | [Notifications + background strategy](06-notifications.md) | notifications | M5 |
| 07 | [Android Auto](07-android-auto.md) | car screen | M6 |
| 08 | [Geofencing](08-geofencing.md) | auto pop-up | M7 |
| 09 | [VPN + connectivity handling](09-vpn-connectivity.md) | robustness | M8 |
| 10 | [Troubleshooting](10-troubleshooting.md) | (living doc) | — |
| 11 | [Configuration + Play release](11-configuration-and-release.md) | settings, hardening, store | M9 |

## Progress tracker

Update this as you go — it is how future guidance sessions know where you are.

- [ ] **M0** — Qt example app runs on the physical phone (ch. 01)
      <br>*Toolchain proven 2026-07-10: the stock example builds a 65 MB arm64-v8a APK.
      Not ticked — the phone was unplugged, so nothing has rendered on it yet.*
- [ ] **M1** — Bridge live: DB state change visible via `mosquitto_sub`; command topic triggers REST (ch. 02)
      <br>*`bridge/` scaffolded; needs your real `STATE_QUERY` and REST payload.*
- [ ] **M2** — Kotlin app shows embedded QML view on the phone (ch. 03)
      <br>*`app/` scaffolded and compiling; property/signal round-trip unverified on device.*
- [ ] **M3** — RTSP video + audio playing in the app over VPN (ch. 04)
      <br>*Camera **still + audio** run on the phone (SM-G990B2) over the VPN, from **one**
      RTSP session in `RtspFrameSource`: the decoder draws to an offscreen EGL surface and
      `glReadPixels` reads the still back (never `Image.getPlanes()` — that is a native abort
      on this Exynos, ch. 10), while the same session plays audio, gated by the **Camera
      audio** switch with `handleAudioFocus` so nav ducks rather than silences.
      `CameraFrameGrabber` picks `RtspFrameSource` (default) or `HttpFrameSource` (optional
      override). Codec is **AAC**; media3 1.10.1 also covers G.711 A-law/µ-law, Opus, AC-3,
      AMR and raw PCM with no per-codec code. **Caveat found on device:** raw-stream audio is
      **choppy** (media3 renders the camera's irregular RTP timing literally); a **go2rtc
      restream** URL smooths it and stays inside the one-URL model (ch. 10). Still unproven:
      **live video** (QtMultimedia, ch. 04 §2), and **audio during a car session**.*
      <br>*2026-07-24: the phone panel **blanked for a frame on every snapshot** (a QtQuick
      `Image` drops its pixmap the moment its `source` changes and then decodes
      asynchronously). Now double-buffered on both sides — two alternating cache files in
      `MainActivity.writeFrame()`, two stacked `Image`s in `Main.qml` swapping only on
      `Image.Ready`. Untested on device.*
- [ ] **M4** — Gate control + live state working in the phone UI (ch. 05)
      <br>*MQTT gate control **and state** verified on the device against the real broker in
      an R8 release build. `GateRepository` owns the connection (open whenever there is no
      client, symmetric acquire/release across the three owners, watchdog on the invariant)
      and exposes a real `ConnectionState` every surface renders — so "connected but no
      retained state" no longer looks like "cannot reach the broker". The app surfaces the
      bridge's own `hc12/error` rejection reason and normalises topic prefixes. History and
      the bugs fixed on the way are in ch. 10 → MQTT.*
- [ ] **M5** — Notification arrives on state change with the app backgrounded (ch. 06)
- [ ] **M6** — Gate control on the car screen + heads-up notification, tested in DHU (ch. 07)
      <br>*Works in the DHU. The **real-car** smoke test is gated: *Unknown sources* doesn't
      apply to Car App Library apps, so the car needs a Play trusted-source install (Internal
      App Sharing / Internal Test Track) + a Play Console account — ch. 07 §4. Not ticked
      until it shows on the Passat.*
      <br>*2026-07-23: head-unit **camera snapshot** added — still image in a `PaneTemplate`
      when a camera URL is configured; grid as before without one. Untested on DHU and car.
      Also fixed the phone-UI mis-scale after real-car disconnect (`configChanges` on
      MainActivity — see docs/10). The frame source was replaced on 2026-07-24 (see M3);
      the template code and its gating are unchanged apart from which config field they
      read.*
      <br>*2026-07-24, from the first real-car run: the head unit **dimmed and came back**
      every snapshot. Cause was the per-snapshot spinner in the pane's first row — a row
      whose strings change is a new template, not a refresh, so the host played its screen
      transition. Pane is now a fixed single row (status as title, error + distance as its
      two text lines) with the bitmap as the only per-snapshot difference, plus a 150 ms
      debounce on the merged invalidate flow. Same run: the car screen gained a **second
      button** — the state-dependent Open/Close plus an unconditional **Stop** (a Pane takes
      no more than two actions); the arrival notification deliberately keeps one. Icons on
      the car are now `CarColor.DEFAULT`-tinted so the host can recolour them for a light
      theme. **All untested since the change** — the freeze this spinner was covering for may
      come back (ch. 10 → Android Auto).*
- [ ] **M7** — Geofence entry triggers the gate pop-up (ch. 08)
      <br>*2026-07-24: **distance-from-home** readout added on both surfaces — a
      "2.3 km from home" / "At home" line under the gate state in the phone QML view, and a
      second `PaneTemplate` row (or, camera-less, appended to the grid header) on the car.
      New `geo/HomeDistanceTracker` pulls fused-location fixes while a surface is foreground
      and reuses the geofence's own "Allow all the time" grant — it stays silent unless the
      geofence feature is on and located, so no new permission or manifest change. Refresh
      cadence is adaptive: next fix at `0.5 × ETA`, ETA from distance ÷ `max(speed, 50 km/h)`,
      clamped 10 s‥10 min. **Untested on hardware** (phone + DHU) and on the car.*
- [ ] **M8** — Hardened: reconnects, off-VPN behavior, battery (ch. 09)
      <br>*2026-07-23: VPN hardening landed — tri-state bridge availability (fixes the
      false "unreachable" on fresh VPN sessions, docs/10 → MQTT), 10 s connect timeouts +
      30 s max reconnect backoff, and per-topic-class **QoS + keep-alive settings** (new
      "MQTT delivery" category, defaults unchanged: QoS 1, 60 s). All untested on device.
      Bridge-side retained-birth check still to be done (`mosquitto_sub -t
      'hc12/available' -v` from a fresh client).*
- [ ] **M9** — Publishable: nothing configured at build time, security review closed, release
      build signed and minified (ch. 11)
      <br>*2026-07-23: config extraction and the security pass are **done and building** —
      `ConfigStore` + settings screen, real host validator, R8 + signing config, TLS option,
      credentials and coordinates out of the source tree. Verified: release APK contains no
      secrets, all three receivers `exported="false"`, storage permissions gone. Not ticked —
      **nothing has been re-tested on the phone or the DHU since the refactor**, and the Play
      Console work in ch. 11 §5 has not started.*
      <br>*2026-07-24: the launcher icon was a bare white-on-transparent vector, so launchers
      composited it onto a white plate and it disappeared. Replaced with a real
      **adaptive icon** (`mipmap-anydpi-v26`, dark `#1E1E2E` background layer, `<monochrome>`
      for themed icons); the notification small icon deliberately stays the white silhouette.
      A 512 px Play listing icon still has to be produced by hand.*

## Repo layout (target)

```
domofon/
├── CLAUDE.md              # project context for AI-assisted sessions
├── MILESTONES.md          # acceptance criteria per milestone
├── docs/                  # this guide
├── bridge/                # Python bridge service (ch. 02)
│   ├── bridge.py
│   ├── bridge.env.example # copy to bridge.env — gitignored
│   └── domofon-bridge.service
└── app/                   # Android Studio / Gradle root project
    ├── gradle.properties      # qtProjectPath (committed)
    ├── local.properties       # sdk.dir + qtPath — gitignored
    ├── qtquickview/           # QML + CMake project
    │   ├── CMakeLists.txt
    │   └── Main.qml
    └── app/                   # the Android application module
        └── src/main/java/pl/bitforge/domofon/
            ├── MainActivity.kt        # QtQuickView host      (phone QML pipe)
            ├── DomofonApp.kt          # notification channels
            ├── gate/GateRepository.kt # stub; ch. 05 makes it MQTT
            ├── gate/GateNotifier.kt   # CarAppExtender HUN    (AA pop-up pipe)
            └── car/                   # CarAppService + GateScreen (AA app pipe)
```

**Build it:** set up `app/local.properties` from the example, then run
`scripts/build-debug.sh` (debug APK) or `scripts/build-release.sh` (signed Play bundle).
Version is derived from git; see [`../scripts/README.md`](../scripts/README.md).
