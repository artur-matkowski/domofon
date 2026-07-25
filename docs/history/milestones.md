# Milestones (historical)

*[Wiki home](../README.md) › history › milestones*

**Historical record.** This was the build plan the project was written against, milestone
by milestone, with a progress tracker that only ticked after a hardware test. The project
has since been built; the chapters the table references were restructured into this wiki
on 2026-07-25 (see [decisions.md](../architecture/decisions.md) for what shipped versus
what was sketched — notably M1's `bridge/` was replaced by the external hc12-web-service,
and M3's live-video half never shipped). Kept because the acceptance criteria still
describe what the finished product must do; the living version of those checks is
[testing.md](../testing.md).

## The plan as written

| # | Milestone | Acceptance criterion | Where it landed |
|---|-----------|----------------------|-----------------|
| M0 | Toolchain proven | Stock Qt QML example runs on the physical phone | [build-and-release](../build-and-release.md) |
| M1 | Bridge live | DB state change → `mosquitto_sub` ≤ 1 s; command → REST; LWT flips | Superseded: hc12-web-service, [mqtt-contract](../architecture/mqtt-contract.md) |
| M2 | App skeleton | Kotlin activity renders embedded QML; property/signal round-trip | [ui-phone](../modules/ui-phone.md), [ui-qml-contract](../modules/ui-qml-contract.md) |
| M3 | Live camera | RTSP video **+ audio** over VPN; survives rotate/pause/resume | Stills + audio shipped, live video did not: [camera](../modules/camera.md) |
| M4 | Core loop | Live state; buttons drive the real gate; unreachable banner; self-healing reconnect | [gate](../modules/gate.md) |
| M5 | Notifications | State change → heads-up, app backgrounded, screen locked | [ui-notifications](../modules/ui-notifications.md) |
| M6 | Android Auto | Gate control on the car screen (DHU + real car); HUN over Maps | [ui-car](../modules/ui-car.md) |
| M7 | Geofence | "Approaching home — gate: …" before reaching the gate, app untouched | [geo](../modules/geo.md) |
| M8 | Hardened | Failure drill passes; a normal leave-and-return day needs zero fiddling | [gate](../modules/gate.md), [testing](../testing.md) |
| M9 | Publishable | Nothing configured at build time; security pass closed; signed + minified | [security](../architecture/security.md), [build-and-release](../build-and-release.md) |

## Status at the time of the wiki cutover (2026-07-25)

Verified on hardware: gate control + state against the real broker in an R8 release build
(M4); camera still + audio on the phone over VPN (M3, stills half); the car app in the
DHU (M6, DHU half). Still outstanding then: real-car install (needs a Play trusted-source
track), the Play Console work (M9 §5), a demo mode, and re-testing everything after the
2026-07-25 architectural refactor — the current list lives in
[testing.md](../testing.md).

## Requirements → coverage map (from the original plan)

| Requirement | Covered by |
|---|---|
| See + hear the RTSP stream | camera stills + audio |
| Manipulate gate + see current state | gate service + phone/car UI |
| Part of the app in QML | the whole phone scene |
| Android Auto pop-up on state change (over VPN) | CarAppExtender heads-up |
| Steer the gate from Android Auto | car templates |
| Auto pop-up on GPS fence | geofence + arrival flow |
| Guide in .md + live troubleshooting | this wiki + [troubleshooting](../troubleshooting.md) |
