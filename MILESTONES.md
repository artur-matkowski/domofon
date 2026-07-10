# Milestones

Build order with acceptance criteria. Details + tests live in the linked chapters;
track progress in [docs/README.md](docs/README.md).

| # | Milestone | Acceptance criterion | Chapter |
|---|-----------|----------------------|---------|
| M0 | Toolchain proven | Stock Qt QML example runs on the physical phone from Android Studio | [01](docs/01-environment-debian13.md) |
| M1 | Bridge live | DB state change → `mosquitto_sub` within ~1 s; `mosquitto_pub` command → REST API called; LWT flips on bridge stop | [02](docs/02-mqtt-bridge.md) |
| M2 | App skeleton | Kotlin activity renders embedded QML; property (K→QML) and signal (QML→K) round-trip proven | [03](docs/03-app-scaffold.md) |
| M3 | Live camera | RTSP video **+ audio** in the app over mobile data + VPN; survives rotate/pause/resume | [04](docs/04-rtsp-video-audio.md) |
| M4 | Core loop | Live state label; buttons drive the real gate; unreachable-bridge banner; self-healing reconnect | [05](docs/05-gate-control-state.md) |
| M5 | Notifications | State change → heads-up notification with app backgrounded, screen locked | [06](docs/06-notifications.md) |
| M6 | Android Auto | Gate grid on car screen (DHU + real car); HUN pops over Google Maps on state change | [07](docs/07-android-auto.md) |
| M7 | Geofence | Returning home pops "Approaching home — gate: …" before reaching the gate, app untouched | [08](docs/08-geofencing.md) |
| M8 | Hardened | Failure drill table passes; a normal leave-and-return day needs zero manual VPN/app fiddling | [09](docs/09-vpn-connectivity.md) |

## Requirements → coverage map

| Requirement | Covered by |
|---|---|
| See + hear RTSP stream | M3 |
| Manipulate gate + see current state | M1, M4 |
| Part of app in QML | M2 (QtQuickView), M3–M4 (UI in QML) |
| Android Auto pop-up on state change (over VPN) | M5 + M6 (CarAppExtender HUN) |
| Steer gate from Android Auto | M6 |
| Auto pop-up on GPS fence / radius from home | M7 |
| Guide in .md + live troubleshooting | docs/ + [10-troubleshooting](docs/10-troubleshooting.md) |
