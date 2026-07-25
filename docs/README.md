# Domofon wiki

The living documentation for **Domofon**, an entry-phone Android app for a home gate:
camera stills + gate audio from an RTSP camera, gate control and live state over MQTT, an
Android Auto car screen, and a geofence-triggered arrival pop-up — all over OpenVPN.

This wiki is written for **agents and humans changing the code**. Every page states
invariants with their reasons; several record constraints that are invisible in the code
itself. Read the page before changing the code it describes.

## Reading paths

| Changing… | Read first |
|---|---|
| Anything, first time here | [architecture/overview.md](architecture/overview.md) → [conventions.md](conventions.md) |
| MQTT, gate state, commands | [architecture/mqtt-contract.md](architecture/mqtt-contract.md) → [modules/gate.md](modules/gate.md) |
| Settings, config fields | [modules/config.md](modules/config.md) |
| Camera / frames / audio | [modules/camera.md](modules/camera.md) |
| The phone QML UI | [modules/ui-phone.md](modules/ui-phone.md) → [modules/ui-qml-contract.md](modules/ui-qml-contract.md) |
| The Android Auto screen | [modules/ui-car.md](modules/ui-car.md) |
| Notifications | [modules/ui-notifications.md](modules/ui-notifications.md) |
| Geofence / location | [modules/geo.md](modules/geo.md) |
| Wiring / dependency injection | [modules/app-container.md](modules/app-container.md) |
| Build, R8, release, Play | [build-and-release.md](build-and-release.md) |
| Writing or running tests | [testing.md](testing.md) |
| Something broke | [troubleshooting.md](troubleshooting.md) — check here **first**; then append your fix |
| A decision you want to revisit | [architecture/decisions.md](architecture/decisions.md) — read *before* re-litigating |

## Page map

- **[architecture/](architecture/overview.md)**
  - [overview.md](architecture/overview.md) — the real system, component map, the three presentation surfaces
  - [mqtt-contract.md](architecture/mqtt-contract.md) — the hc12 topic contract the app speaks
  - [decisions.md](architecture/decisions.md) — decision records, accepted residual risks, recorded dead ends
  - [security.md](architecture/security.md) — the security model and every deliberate guard
- **[modules/](modules/gate.md)** — one page per package: responsibility, public API, numbered invariants, gotchas
  - [gate.md](modules/gate.md) · [config.md](modules/config.md) · [camera.md](modules/camera.md) ·
    [geo.md](modules/geo.md) · [ui-phone.md](modules/ui-phone.md) ·
    [ui-qml-contract.md](modules/ui-qml-contract.md) · [ui-car.md](modules/ui-car.md) ·
    [ui-notifications.md](modules/ui-notifications.md) · [app-container.md](modules/app-container.md)
- [conventions.md](conventions.md) — layering rules, RAII/lease idioms, commits, versioning, page template
- [build-and-release.md](build-and-release.md) — environment, Qt Gradle traps, memguard, R8, release runbook
- [testing.md](testing.md) — JVM tests, fakes, and the hardware acceptance checklist
- [troubleshooting.md](troubleshooting.md) — the append-only Symptom → Cause → Fix log (+ Backlog)
- [history/milestones.md](history/milestones.md) — the original milestone plan, kept as history

## Ground rules (the short version)

1. **Only Artur can test on hardware** — the phone (SM-G990B2), the DHU, the Passat.
   Nothing is "done" until it ran on a device; say plainly what is untested.
2. **When a session solves a problem**, append it to
   [troubleshooting.md](troubleshooting.md) as Symptom → Cause → Fix.
3. **Agents build with `scripts/build-debug.sh` in a scratchpad copy of the repo** —
   never in-tree. `scripts/build-release.sh` is Artur's.
4. Decisions in [architecture/decisions.md](architecture/decisions.md) are settled;
   reopen them with Artur, not unilaterally.
