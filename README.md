# Domofon

An entry-phone Android app for a home gate: camera stills **and live gate audio** from the
RTSP gate camera, gate control and live state over MQTT, an Android Auto car screen, and a
geofence-triggered arrival pop-up — all reachable from anywhere over OpenVPN.

This repository carries its own **wiki** alongside the code: architecture, per-module
invariants, decision records and a living troubleshooting log live in
[`docs/`](docs/README.md). Read the page before changing the code it describes.

## Architecture in one paragraph

The gate hangs off an AVR node on a 433 MHz HC-12 radio, bridged to MQTT by
`hc12-web-service` (a separate repo). This app is just another broker client — one APK:
a Kotlin host (MQTT via a single gate service, notifications, an `androidx.car.app`
service for Android Auto, geofencing) with the phone UI written in **QML, embedded via
`QtQuickView`**, all wired through a hand-rolled composition root. The camera is one RTSP
URL; stills are pulled out of the stream on the GPU.

```
hc12/rx/<Signal>   radio → app   retained   {"idSender":4,"idTarget":255,"ts":"…"}
hc12/tx/<Signal>   app → radio   NEVER retained   {"idTarget":4}
hc12/error         rejections               {"topic":"…","reason":"…"}
hc12/available     service LWT   retained   "online" / "offline"
```

The app speaks **only MQTT**. The rx topics being retained is what makes the UX work: the
moment the app or a car session connects, the broker replays the current state. Full
contract: [`docs/architecture/mqtt-contract.md`](docs/architecture/mqtt-contract.md).

## Two constraints that shape everything

1. **Android Auto renders only Car App Library templates.** QML cannot appear on a car
   screen, so the car UI is Kotlin templates. That is why the app is a Kotlin host with
   embedded QML rather than a pure Qt app.
2. **An app cannot force itself onto the car screen.** "Pop up while I'm driving" is
   therefore a high-importance notification carrying a `CarAppExtender`, which overlays
   whatever the car is showing — navigation included.

## Status

Working on real hardware: gate control + live state (verified in a release build against
the real broker), camera still + audio over VPN, the car app in the DHU. Outstanding:
real-car install (needs a Play test track), the Play Console runbook, and re-verifying the
2026-07-25 architectural refactor on device — see
[`docs/testing.md`](docs/testing.md).

## Build

```bash
cd app && cp local.properties.example local.properties   # then set sdk.dir and qtPath
./scripts/build-debug.sh              # debug APK  -> dist/
./scripts/build-release.sh            # signed Play bundle -> dist/ (see scripts/README.md)
```

The version is derived from git — nothing is hand-edited. Requires Qt 6.11.1 for Android
(arm64-v8a), NDK r27c (`27.2.12479018`), a real JDK 21, and `platforms;android-36`.
[`docs/build-and-release.md`](docs/build-and-release.md) covers the setup and every Qt/R8
trap; [`docs/troubleshooting.md`](docs/troubleshooting.md) collects everything that has
already cost someone an afternoon.

## Licence

Personal project, no licence granted. Read it, learn from it, don't expect support.
