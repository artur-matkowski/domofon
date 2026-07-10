# Domofon — project context

Entry-phone Android app for Artur's home gate. **Artur writes the code himself,
following the guide in `docs/`; Claude's job is guidance and troubleshooting, not
implementing the app.** When a session solves a problem, append it to
`docs/10-troubleshooting.md` (Symptom → Cause → Fix) and update the progress tracker
checkboxes in `docs/README.md`.

> **One-off exception, 2026-07-10.** Artur explicitly asked Claude to bootstrap the repo:
> `bridge/` scaffold and the `app/` hello-world for all three UI pipes (phone QML,
> Android Auto app, Android Auto pop-up). That override covered the scaffold only. From
> M3 (RTSP) onward the rule above applies again — do not start writing app code because
> this scaffold exists.

## Where things stand

Check `docs/README.md` → *Progress tracker* for the current milestone. Version facts
re-verified on-machine 2026-07-10: **Qt 6.11.1** (not 6.11.0), NDK **r27c =
27.2.12479018**, `androidx.car.app` **1.7.0** is still the newest stable, Qt Gradle
Plugin **1.4** (Maven Central), AGP **9.0.0**, Gradle **9.4.1**.

## Architecture in one paragraph

Existing home infra (RTSP camera w/ audio, REST gate API, Postgres gate state, MQTT
broker, OpenVPN server) stays untouched except for new broker credentials. A small
Python **bridge** (`bridge/`, ch. 02) is the only component touching Postgres (initial
pull + 1 s polling) and REST (executes commands); it translates everything to MQTT:
`domofon/gate/state` (retained), `domofon/gate/command`, `domofon/bridge/status` (LWT).
The **app** (`app/`, ch. 03+) is one APK: Kotlin host (MQTT via HiveMQ client in a
single `GateRepository`, notifications, Android Auto `CarAppService` with IOT category,
Play Services geofencing) + **QML embedded via `QtQuickView`** (Qt Quick for Android,
Qt Gradle plugin) for the phone UI incl. RTSP playback (QtMultimedia). Phone reaches
home via **OpenVPN for Android** (always-on, per-app).

## Key constraints (don't re-litigate)

- Android Auto renders only Car App Library templates — QML can't appear on the car
  screen; "pop-up while driving" = high-importance notification with `CarAppExtender`.
- App speaks **MQTT only** — never Postgres or REST directly (user decision).
- No 24/7 connection: MQTT connects on app-foreground, AA-session, or geofence-entry
  (15-min foreground service). See ch. 06.

## Decisions confirmed by Artur (2026-07-10)

- Bridge watches Postgres by **polling**, 1 s. Zero changes to the gate database.
  LISTEN/NOTIFY stays documented as an optional upgrade in ch. 02.
- Guide language **English**.
- Repo is public on GitHub: `artur-matkowski/domofon`. Nothing secret may be committed —
  `bridge/bridge.env` and `app/local.properties` are gitignored and have `.example` twins.

## Conventions

- Dev machine: Debian 13. Phone: arm64-v8a. `minSdk 28`.
- Package root: `pl.bitforge.domofon`; only `GateRepository` may own MQTT.
- Secrets (broker/RTSP credentials, `bridge.env`) never committed.
