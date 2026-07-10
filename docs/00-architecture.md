# 00 — Architecture

## What already exists (untouched by this project)

| Component | Role | Notes |
|---|---|---|
| RTSP camera | Gate video + audio | App consumes the stream directly over VPN |
| REST API | Executes gate open/close (JSON POST) | Only the bridge calls it; the app never does |
| PostgreSQL | Stores gate state (`opened`, `opening`, …) | Only the bridge reads it (read-only pulls) |
| MQTT broker | Message bus | Already running; this project defines its first topics |
| OpenVPN server + **OpenVPN for Android** | Phone ↔ home network tunnel | All app traffic to home goes through it |

## What you will build

1. **`bridge/`** — a small Python service on the home server. It is the *only* thing that
   touches Postgres and the REST API. It translates both into MQTT.
2. **`app/`** — one Android APK containing:
   - a **Kotlin layer**: MainActivity, MQTT client, notifications, Android Auto
     `CarAppService`, geofencing;
   - a **QML layer** embedded via `QtQuickView` (Qt Quick for Android): the camera view
     (RTSP video + audio) and the gate control UI on the phone screen.

## The big picture

```
HOME NETWORK                                      PHONE (over OpenVPN for Android)
┌────────────────────────────────────┐            ┌────────────── One APK ──────────────┐
│ RTSP camera (video+audio) ─────────┼──RTSP─────▶│ Kotlin layer                        │
│                                    │            │  ├ CarAppService (Android Auto, IOT)│
│ Postgres ◀─initial pull + polling─┐│            │  ├ Geofencing (Play Services)       │
│ REST API ◀─execute gate commands─┐││            │  ├ MQTT client + notifications (HUN)│
│                                  │││            │  └ MainActivity                     │
│            bridge service ───────┘┘│            │      └ QtQuickView → QML:           │
│                 │ pub        ▲ sub │            │          RTSP video (QtMultimedia), │
│                 ▼            │     │            │          gate buttons, live state   │
│   EXISTING MQTT BROKER ──────┼─────┼───MQTT────▶│                                     │
│     …/gate/state (retained)──┘     │            └─────────────────────────────────────┘
│     …/gate/command                 │
└────────────────────────────────────┘
```

## The MQTT topic contract (the heart of the system)

The app speaks **only MQTT** (plus raw RTSP for the stream). Full contract in
[02-mqtt-bridge.md](02-mqtt-bridge.md); summary:

| Topic | Direction | Retained | Payload |
|---|---|---|---|
| `domofon/gate/state` | bridge → app | **yes** | `{"state":"opening","changed_at":"…"}` |
| `domofon/gate/command` | app → bridge | no | `{"action":"open","request_id":"…"}` |
| `domofon/bridge/status` | bridge LWT | yes | `"online"` / `"offline"` |

`gate/state` being **retained** is what makes the whole UX work: the moment the app (or
the Android Auto session) connects, the broker replays the current state instantly —
no query, no REST call, no race.

## Key design decisions and why

**Why a bridge instead of the app calling Postgres/REST directly?**
One protocol in the app means one connection to manage, one reconnect path, one security
surface. Push (MQTT) beats polling for driving notifications. Postgres and REST stay
exactly as they are; if either changes someday, only the bridge changes.

**Why does the bridge poll Postgres instead of LISTEN/NOTIFY?**
Polling every second on a LAN is negligible load and requires **zero changes to your
database**. `LISTEN/NOTIFY` (instant, no polling) is documented as an optional upgrade in
ch. 02 — one trigger, one line changed in the bridge.

**Why Kotlin host + embedded QML, not a pure Qt app?**
Two hard Android constraints force the Kotlin layer:

1. **Android Auto cannot render custom UI.** Car screens only allow Google's templated
   *Car App Library* (`androidx.car.app`, Kotlin/Java). QML physically cannot appear on
   the car display. So the car part must be Kotlin — and geofencing, notifications, and
   foreground services are also far more natural there. QML does what it's best at: the
   rich phone UI with the video view. Since Qt 6.8, `QtQuickView` makes embedding QML in
   a normal Android app a first-class, documented workflow.
2. **Apps cannot force-launch themselves onto the car screen.** The "pop-up while
   driving" is implemented as a **heads-up notification (HUN)** via `CarAppExtender`: it
   overlays whatever is on the car screen (e.g. navigation) with "Gate is opening…", and
   one tap opens the gate control car app. Same mechanism for the geofence trigger. This
   is the closest Android Auto allows to an auto pop-up — and in practice it feels right:
   you see the alert immediately without the car UI being hijacked.

**Why QtMultimedia for RTSP?**
Qt 6's FFmpeg media backend plays RTSP including audio with a 5-line QML snippet. If its
latency disappoints, ch. 04 documents fallbacks (camera substream, go2rtc restream,
libVLC) — decide only after measuring.

**Connectivity model (ch. 06 & 09).** The app does *not* hold a 24/7 connection. MQTT
connects in exactly three situations: app in foreground, Android Auto session active,
or geofence recently entered (short foreground service). This keeps battery sane and
still covers every scenario in the requirements. OpenVPN for Android runs as always-on
VPN so the tunnel exists whenever the app needs it.

## Security model (personal-use scale)

- Nothing is exposed to the internet; everything rides the VPN.
- Broker: per-client username/password (`bridge`, `phone`), ACL limiting both to
  `domofon/#` if the broker is shared with other things.
- The bridge validates commands against an allowlist (`open`/`close`/`stop`) — a rogue
  MQTT message cannot make it call arbitrary REST endpoints.
- RTSP credentials live in the app's local settings, not in code.
