# Module: gate (data/mqtt + domain gate pieces)

*[Wiki home](../README.md) › modules › gate*

## Responsibility

Everything between the broker socket and the four `StateFlow`s the UI renders. Split:

| Class | Owns |
|---|---|
| `data/mqtt/GateService` | *When* a connection exists: lease accounting, watchdog, reconnect, teardown semantics, the shared flows, command sending |
| `data/mqtt/MqttTransport` (interface) + `HiveMqTransport` | *How* the wire works — the only file importing `com.hivemq.*` |
| `data/mqtt/ConnectionLease` | One holder's claim; idempotent `AutoCloseable` |
| `data/mqtt/ConnectionErrorMessages` | Throwable → one safe on-screen line |
| `domain/GateProtocol` | The hc12 vocabulary and framing ([mqtt-contract](../architecture/mqtt-contract.md)) |
| `domain/GateStateReducer` | Per-connection staleness rules |
| `domain/GatePolicy` | Shared wording (`gateStatusLine`) and the button rule (`primaryAction`) |
| `domain/ReconnectPolicy` | The 1 s → 30 s doubling backoff as a value |

## Public API (the parts callers touch)

```kotlin
class GateService(transport, currentConfig: () -> DomofonConfig, scope, reconnectPolicy) {
    val gateState: StateFlow<GateState>          // resets to "unknown" on teardown
    val bridgeStatus: StateFlow<BridgeStatus>    // UNKNOWN / ONLINE / OFFLINE
    val connection: StateFlow<ConnectionState>   // DISCONNECTED/CONNECTING/CONNECTED/DEGRADED/FAILED + reason
    val lastError: StateFlow<String>             // "" = none; 20 s TTL; survives teardown

    fun acquire(tag: String): ConnectionLease    // opens on first lease; rebuilds on wire change
    fun refresh()                                 // settings screen: re-apply edited credentials live
    fun sendCommand(action: String)               // fire-and-forget
    suspend fun sendCommandAwait(action, timeoutMs = 8_000): Boolean
    suspend fun awaitFreshState(timeoutMs, settleMs = 750): GateState?
}
```

Lease tags in use: `phone-ui`, `settings`, `car-session`, `arrival`, `command`.

## Invariants (numbered, with why)

1. **The connection exists iff at least one lease is held.** The watchdog (15 s tick)
   repairs the one direction that self-heals — leases held but no connection — because its
   predecessor shipped a state where the app sat foreground, "owned", and socketless for
   over an hour with no retry and no error. It deliberately does *not* race
   `scheduleReconnect` for a connection that exists and is failing.
2. **A lease closes idempotently and releases exactly itself.** The previous `owners: Int`
   drifted twice in production (double release tore down a connection in use; a leaked
   slot pinned a dead one open). Structural rule: hold the lease in a `val`, close it in
   the paired lifecycle callback, or `acquire(tag).use { }` for one-shot work.
3. **Handle identity is the staleness token.** Every transport callback carries its
   `Handle`; anything not `===` the current one is a retired connection completing into
   silence. This *is* the old epoch counter — retiring the field is bumping the epoch.
   Without it, a slow VPN connect that timed out completes a second later and reports a
   healthy connection against no client.
4. **`teardown()` resets `gateState` to `unknown` — load-bearing.** `awaitFreshState`
   means *fresh from this connection*: when state survived disconnects it returned
   instantly from memory and the arrival pop-up announced hours-old state with total
   confidence, having reached neither the VPN nor the broker.
5. **`lastError` survives teardown, on purpose.** A command sent with the app closed
   reports its failure and immediately releases the connection; clearing the error with
   the client would erase the report before anything rendered it. It self-expires (20 s).
6. **The pinned config (`active` + `protocol`) is the one the connection subscribed
   with.** A message arriving mid-edit must match the prefixes actually subscribed, not
   half-typed new ones. A wire change (broker/topics/mqtt — `DomofonConfig.wire`) rebuilds
   on the next `acquire`/`refresh`; a camera or home edit must *not* reconnect.
7. **Reconnect = a new client, never a resumed one** — decision D7 in
   [decisions.md](../architecture/decisions.md); the measured reason lives on
   `HiveMqTransport`.
8. **DEGRADED (refused subscription) still allows publishing.** A subscription ACL says
   nothing about publish rights, and refusing to send would be a worse failure than trying
   and reporting the broker's answer.
9. **`sendCommandAwait` runs on one deadline budget** for connect-wait + ack-wait: two
   sequential 8 s waits could burn 16 s inside a receiver that has ~10 s of goAsync.
10. **Transport contract:** `connect()` never throws (bad config arrives via
    `onConnectFailed`) and never invokes callbacks synchronously from inside `connect()`.
    `FakeTransport` in tests honors both.

## Gotchas

- `GateService`'s callbacks are `@Synchronized` on the service monitor; never block inside
  it waiting for a transport callback.
- `awaitFreshState`'s 750 ms settle window exists because retained signals arrive as an
  unordered burst — returning on the first one produced a pop-up reading "opening" for a
  gate closed two minutes earlier.
- QoS codes are clamped at the parser *and* again at the transport (`MqttQos.fromCode`
  returns null; a crash in the subscribe path would take the connection with it).

## Related pages

[mqtt-contract](../architecture/mqtt-contract.md) ·
[app-container](app-container.md) · [testing](../testing.md) ·
[decisions D5/D7](../architecture/decisions.md)
