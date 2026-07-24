# Domofon

An entry-phone Android app for a home gate: the RTSP gate camera (video **and** audio) in
a QML view, gate control and live state over MQTT, an Android Auto car screen, and a
geofence-triggered pop-up — all reachable from anywhere over OpenVPN.

This repository carries its own **design record** alongside the code: the reasoning, the
constraints and the acceptance test for every milestone live in [`docs/`](docs/README.md),
one chapter each. Read the chapter before changing the code it describes.

## Architecture in one paragraph

Existing home infrastructure (RTSP camera, gate REST API, Postgres gate state, MQTT
broker, OpenVPN) stays untouched. A small Python [`bridge/`](bridge/README.md) is the only
component that talks to Postgres and REST; it translates both into three MQTT topics. The
[`app/`](app) is a single APK: a Kotlin host (MQTT, notifications, an `androidx.car.app`
service for Android Auto, geofencing) with the phone UI written in **QML, embedded via
`QtQuickView`**.

```
domofon/gate/state     bridge → app   retained   {"state":"opening","changed_at":"…"}
domofon/gate/command   app → bridge              {"action":"open","request_id":"…"}
domofon/bridge/status  bridge LWT     retained   "online" / "offline"
```

The app speaks **only MQTT**. `gate/state` being retained is what makes the UX work: the
moment the app or an Android Auto session connects, the broker replays the current state.

## Two constraints that shape everything

1. **Android Auto renders only Car App Library templates.** QML cannot appear on a car
   screen, so the car UI is a Kotlin `GridTemplate`. That is why the app is a Kotlin host
   with embedded QML rather than a pure Qt app.
2. **An app cannot force itself onto the car screen.** "Pop up while I'm driving" is
   therefore a high-importance notification carrying a `CarAppExtender`, which overlays
   whatever the car is showing — navigation included.

## Status

In progress. See the progress tracker in [`docs/README.md`](docs/README.md). MQTT gate
control/state and the RTSP camera still **and audio** run on the phone; Android Auto works
in the DHU. Not yet proven on hardware: live video, geofencing, and audio during a car
session. Nothing is "done" until it has run on a device.

## Build

```bash
cd app && cp local.properties.example local.properties   # then set sdk.dir and qtPath
./scripts/build-debug.sh              # debug APK  -> dist/
./scripts/build-release.sh            # signed Play bundle -> dist/ (see scripts/README.md)
```

The version is derived from git — nothing is hand-edited (`scripts/README.md`). Requires
Qt 6.11.1 for Android (arm64-v8a), NDK r27c (`27.2.12479018`), a real JDK 21, and
`platforms;android-36`. Chapter 01 covers the setup; chapter 10 collects every trap that
has already cost someone an afternoon.

## Licence

Personal project, no licence granted. Read it, learn from it, don't expect support.
