# Domofon — Build Guide

A step-by-step guide for building the **Domofon** entry-phone Android app yourself:
RTSP gate camera (video + audio) in a QML view, gate control and live state over MQTT,
Android Auto integration, and a geofence-triggered pop-up — all reachable from anywhere
through OpenVPN.

## How to use this guide

1. Read [00-architecture.md](00-architecture.md) first — it explains every component and
   **why** it is designed this way (including two hard Android Auto constraints).
2. Work through the chapters in order. Each chapter ends with an **acceptance test** —
   do not move on until it passes. The chapters map 1:1 to the milestones in
   [../MILESTONES.md](../MILESTONES.md).
3. When something breaks, check [10-troubleshooting.md](10-troubleshooting.md) first,
   then ask Claude in a session inside this repo — the project context (CLAUDE.md,
   these docs, your progress below) is picked up automatically. Resolved problems get
   appended to the troubleshooting chapter so they are never solved twice.

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
- [ ] **M4** — Gate control + live state working in the phone UI (ch. 05)
- [ ] **M5** — Notification arrives on state change with the app backgrounded (ch. 06)
- [ ] **M6** — Gate control on the car screen + heads-up notification, tested in DHU (ch. 07)
      <br>*Works in the DHU. The **real-car** smoke test is gated: *Unknown sources* doesn't
      apply to Car App Library apps, so the car needs a Play trusted-source install (Internal
      App Sharing / Internal Test Track) + a Play Console account — ch. 07 §4. Not ticked
      until it shows on the Passat.*
- [ ] **M7** — Geofence entry triggers the gate pop-up (ch. 08)
- [ ] **M8** — Hardened: reconnects, off-VPN behavior, battery (ch. 09)
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
