# Architecture overview

*[Wiki home](../README.md) › architecture › overview*

What the system actually is — as deployed, not as first sketched. (The original design
included a Python `bridge/` and QtMultimedia video; neither shipped — see the dead ends in
[decisions.md](decisions.md).)

## Components

| Component | Where | Role |
|---|---|---|
| Gate controller | AVR node on a 433 MHz HC-12 radio | Physically moves the gate |
| **hc12-web-service** | `rpi-d`, separate repo (C++/Poco) | Bridges the radio to MQTT — publishes gate signals, executes commands, rejects bad ones on a shared error topic |
| MQTT broker | Home network | The only protocol the app speaks (see [mqtt-contract.md](mqtt-contract.md)) |
| RTSP camera | At the gate | One RTSP URL carrying video + audio; the app pulls stills out of the stream |
| OpenVPN server + OpenVPN for Android | Home ↔ phone | Always-on, per-app tunnel; every byte to home rides it |
| **Domofon app** | This repo, `app/` | One APK, three presentation surfaces (below) |

```
HOME NETWORK                                   PHONE (over OpenVPN for Android)
┌─────────────────────────────────┐            ┌───────────── One APK ─────────────────┐
│ gate ⇄ HC-12 radio              │            │ AppContainer (composition root)       │
│          ⇅                      │            │  ├ GateService ── MqttTransport ──────┼─╮
│ hc12-web-service (rpi-d)        │            │  ├ ConfigStore (all settings)         │ │
│   pub hc12/rx/<Signal> retained │            │  ├ CameraFrameGrabber (per surface)   │ │
│   sub hc12/tx/<Signal>          │◀──MQTT────▶│  └ GeofenceManager / DistanceTracker  │ │
│   pub hc12/error, hc12/available│            │ Surfaces (each via GateViewModel):    │ │
│                                 │            │  ├ phone: QtQuickView + QML (binder)  │ │
│ RTSP camera ────────────────────┼──RTSP─────▶│  ├ car: CarAppService templates       │ │
└─────────────────────────────────┘            │  └ notifications (CarAppExtender HUN) │ │
                                               └───────────────────────────────────────┘ │
                                                      broker connection ◀────────────────╯
```

## Layers (enforced by package, single Gradle module)

- **`domain/`** — pure Kotlin, no `android.*` imports; the unit-test target. The hc12
  codec ([GateProtocol]), staleness rules ([GateStateReducer]), shared wording/button
  policy ([GatePolicy]), backoff schedule, config schema + parser.
- **`data/`** — talks to the world: `data/mqtt` ([modules/gate.md](../modules/gate.md)),
  `data/config` ([modules/config.md](../modules/config.md)), `data/camera`
  ([modules/camera.md](../modules/camera.md)), `data/location`
  ([modules/geo.md](../modules/geo.md)).
- **`ui/`** — the three surfaces over one shared `GateUiState` derivation
  (`ui/shared/GateViewModel`): `ui/phone`, `ui/car`, `ui/notifications`, `ui/settings`.
- **`receivers/`** — thin framework entry points that delegate to the container.
- Root package — the app entry points that cannot move (`MainActivity`,
  `QtRestartActivity`, `DomofonApp`) and the `AppContainer`
  ([modules/app-container.md](../modules/app-container.md)).

## The three presentation surfaces

1. **Phone** — QML via `QtQuickView`; Kotlin renders into it through a typed property
   bridge ([modules/ui-qml-contract.md](../modules/ui-qml-contract.md)).
2. **Car** — Android Auto renders *only* Car App Library templates; QML physically cannot
   appear on the car display ([modules/ui-car.md](../modules/ui-car.md)).
3. **Notifications** — high-importance notifications with `CarAppExtender` are the only
   way to "pop up while driving"; apps cannot force-launch onto the car screen
   ([modules/ui-notifications.md](../modules/ui-notifications.md)).

All three consume the same derived state (`GateUiState`), so wording and button logic
cannot drift between them.

## Connectivity model

There is **no 24/7 connection**. The MQTT connection exists exactly while somebody holds a
`ConnectionLease`: the phone UI in the foreground, the settings screen, a live car
session, the arrival pop-up's few seconds, or a one-shot command. Every rx topic is
retained, so a fresh connection has current state within a second or two — a long-lived
connection buys nothing. The camera stream follows the same rule: it runs only while a
surface is visible.

## Related pages

[mqtt-contract.md](mqtt-contract.md) · [decisions.md](decisions.md) ·
[security.md](security.md) · [module pages](../README.md#page-map)
