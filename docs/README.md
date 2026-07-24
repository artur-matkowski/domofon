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
      <br>*2026-07-23: interim **phone-side snapshot** added — the phone QML view shows the
      same still as the head unit (a Bitmap can't cross the QtQuickView bridge, so frames
      pass as a cache `file://` URL), and `camera.snapshotSecs` made the interval
      configurable for both. Then, tested on device: **switched OFF**, because reading
      `Image.getPlanes()` on ExoPlayer's decoder output is a native JNI abort on Exynos
      (Samsung SM-G990B2) that killed the app ~3 s after every launch.*
      <br>*2026-07-24, morning: rebuilt as an HTTP GET returning a JPEG. It worked, but the
      probe against the real camera settled the design question the other way — the gate
      camera answers snapshots only at a Hikvision-specific path and only with Digest auth,
      so an app that needed one would only ever work for people who know their camera's
      firmware. **Artur's call: RTSP is the one address the app may require.** A backend or a
      brand table makes it a private tool, not publishable software.*
      <br>*2026-07-24, afternoon: **stills come from the RTSP stream again, safely this
      time.** `OffscreenTextureReader` gives the decoder a `SurfaceTexture` on an offscreen
      EGL context and reads pixels back with `glReadPixels` — GPU-only buffers are fine when
      the GPU does the reading, and the scale to 640 px happens free in the draw. media3 is
      back (release APK 59.4 MB). `CameraFrameGrabber` is now just the contract and picks
      between `RtspFrameSource` (default) and `HttpFrameSource` (optional override, still
      there for go2rtc/Frigate). Settings leads with the camera's RTSP address; the snapshot
      URL is marked optional. Both builds pass; **nothing has run on the phone, the DHU or
      the car yet** — and the GL path has two failure modes to look for first: an upside-down
      picture, or a green strip down one edge (ch. 10). Full M3 (live video + audio) remains
      unwritten; ch. 04 §2 notes audio is unaffected by the crash that shaped all of this.*
      <br>*2026-07-24, later: **live audio from the gate now works on the phone.** First built
      as a separate audio-only player, which surfaced a hardware fact: this camera refuses a
      second concurrent RTSP session — audio played but the stills stream IO-errored every
      reconnect. So audio was folded into `RtspFrameSource`: **one session** decodes video to
      the GL surface and audio to the speaker, gated by a new **Camera audio** switch (on by
      default), with `handleAudioFocus` so a nav prompt ducks rather than silences. Tested on
      the phone (SM-G990B2) over the VPN: codec is **AAC** (`audio/mp4a-latm`), plays natively.
      media3 1.10.1's RTSP stack also covers G.711 A-law/µ-law, Opus, AC-3, AMR and raw PCM
      with no per-codec code, so the old "ExoPlayer won't play G.711" worry is stale (ch. 10).
      **Caveat found on device:** raw-stream audio from this camera is **choppy** (media3
      renders its irregular RTP timing literally); a **go2rtc restream** URL smooths it, and
      stays inside the one-URL model — no required back-end (ch. 10). Still unproven: audio
      during an **Android Auto** session, and nav-ducking in the car. Live video via
      QtMultimedia (ch. 04 §2) remains the only unbuilt part of M3.*
- [ ] **M4** — Gate control + live state working in the phone UI (ch. 05)
      <br>*2026-07-23: MQTT connectivity is now **verified on a real device against the real
      broker**, in an R8 release build. `GateRepository` exposes a real `ConnectionState`
      and every surface renders it, so "connected but the broker has no retained state" no
      longer looks identical to "cannot reach the broker" — which is what a whole debugging
      session went into. Two live bugs fixed alongside: HiveMQ's `automaticReconnect` could
      never re-authenticate after a network flap (the app was permanently mute until
      force-stopped), and the settings screen crashed on open. Gate **state** is still
      unconfirmed: the broker holds no retained `hc12/rx/Gate*`, so nothing has exercised
      the state path end-to-end yet.*
      <br>*2026-07-24: that last sentence was wrong, and finding out why took the session.
      The broker **does** hold fresh retained `hc12/rx/Gate*` and `hc12/available online`;
      the bridge is connected and subscribed. The app was reaching none of it — it sat in
      the foreground holding owner slots with **no client and no socket at all**, a state
      `ad92c4a` made reachable and nothing recovered from. Fixed in `GateRepository`
      (`connect()` opens whenever there is no client, symmetric acquire/release at the
      call sites, plus a watchdog on the invariant). Alongside it, three things that made
      this take a whole session are now impossible: the app subscribes to `hc12/error` and
      shows the bridge's own rejection reason, a command that never left the phone says so,
      "waiting for the gate service" no longer renders as "no gate state reported yet", and
      topic prefixes are normalised so a missing trailing slash cannot silently break
      everything. See ch. 10 → MQTT. **Not yet re-tested on the device.***
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
- [ ] **M7** — Geofence entry triggers the gate pop-up (ch. 08)
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

**Build it:** `cd app && cp local.properties.example local.properties && ./gradlew installDebug`
(after editing the paths in `local.properties`).
