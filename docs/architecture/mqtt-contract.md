# The MQTT topic contract

*[Wiki home](../README.md) › architecture › mqtt-contract*

The wire contract between the app and **hc12-web-service** (the C++/Poco bridge on
`rpi-d`, separate repo — its own authoritative doc is the `infra/hc12-web-service` wiki
page). The app is just another broker client: it never touches the radio, a database or
HTTP. **This is a hard interface — do not change the app's side without the service's.**

All topic *prefixes* below are the defaults and are user-configurable in Settings
([modules/config.md](../modules/config.md)); the signal names themselves are protocol
vocabulary and are not.

| Topic | Direction | Retained | Payload |
|---|---|---|---|
| `hc12/rx/<Signal>` | radio → app | **yes** | `{"idSender":4,"idTarget":255,"ts":"…Z"}` |
| `hc12/tx/<Signal>` | app → radio | **must NOT be** | `{"idTarget":4}` |
| `hc12/available` | service LWT | yes | `online` / `offline` |

## Signals

Inbound (`rx`), each retained last-value-per-signal:
`GateOpened, GateClosed, GateOpening, GateClosing, GateStopped, GateStuckOpening,
GateStuckClosing` — mapped to UI states in `domain/GateProtocol.SIGNAL_TO_STATE`.

Outbound (`tx`): `OpenGate, CloseGate, StopGate` — mapped from actions
`open / close / stop` in `ACTION_TO_SIGNAL`. The payload carries the gate node's radio id
under a configurable key (default `{"idTarget": 4}`).

## Load-bearing properties

1. **`rx` topics are retained** — the whole UX depends on it. A fresh connection replays
   current state instantly; no query, no race. It is also why the app uses clean sessions:
   a queued backlog would only replay what retention hands over anyway.
2. **`tx` must never be retained.** hc12-web-service drops retained tx outright (its
   replay guard) — a retained command would be *silently ignored*; without that guard it
   would re-key the transmitter on every service restart. The invariant is encoded in
   `GateProtocol.encodeCommand`, which does not even expose a retain flag.
3. **Retained signals arrive as a burst in arbitrary order** (observed: `GateClosed`
    11:40:10 before `GateOpening` 11:40:05). `GateStateReducer` applies newest-`ts`-wins;
   a live message wins timestamp ties, and may never move state backwards. Future stamps
   beyond 300 s are disbelieved without poisoning the memory.
4. **A live (non-retained) signal is proof the service is alive**, even if `hc12/available`
   never spoke on this connection (the birth message may not be retained broker-side).
   Retained messages prove nothing — brokers replay them for years.
5. **Availability is three-valued** (`UNKNOWN`/`ONLINE`/`OFFLINE`), never a boolean: a
   boolean defaulted to "offline" and presented the default as a fact on every fresh VPN
   session.

## Where the contract lives in code

- `domain/GateProtocol.kt` — topic building, decode/encode
- `domain/GateStateReducer.kt` — the staleness rules
- `data/mqtt/GateService.kt` — connection lifecycle ([modules/gate.md](../modules/gate.md))
- `domain/config/DomofonConfig.kt` — the configurable prefixes and their defaults

## Related pages

[modules/gate.md](../modules/gate.md) · [overview.md](overview.md) ·
[decisions.md](decisions.md)
