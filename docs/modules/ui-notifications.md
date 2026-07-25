# Module: ui-notifications (ui/notifications + receivers/GateCommandReceiver)

*[Wiki home](../README.md) › modules › ui-notifications*

## Responsibility

Heads-up notifications — the third presentation surface, and the only legal "pop up while
driving": Android Auto cannot be forced to open an app, but a high-importance notification
carrying a `CarAppExtender` draws over whatever the car screen shows, Maps included.

| Class | Owns |
|---|---|
| `GateEventNotifier` | *When*: the **single process-wide** collector of state changes, started once by `DomofonApp` |
| `GateNotifier` | *How*: a stateless renderer — state-change, arrival, and command-failure notifications |
| `receivers/GateCommandReceiver` | The action button's backend: keyguard re-check, dismiss-as-ack, `sendCommandAwait`, failure notification |

## Invariants

1. **Exactly one state-change collector, application-wide.** Its predecessor was an
   `observe()` launched by both MainActivity and the car session — two collectors, every
   notification posted twice whenever phone and car were open together. Being permanent is
   free: `gateState` only moves while some lease holds the connection, and `drop(1)` still
   skips the state a surface connects *into* (not news).
2. **Three fixed notification ids** (event 1001 / arrival 1002 / failure 1003): an arrival
   pop-up must not silently replace an event, and a failure report must never overwrite
   the thing that failed.
3. **The action button carries `GatePolicy.primaryAction`** — the same call every surface
   makes, so the notification can never contradict the car screen. The arrival
   notification gets an action only when state is known; with no state, offering the wrong
   action at 60 km/h is worse than offering none.
4. **Both extenders get the action**: the `CarAppExtender` draws the button on the head
   unit, the plain builder on the phone — separate action lists.
5. **Dismiss-on-tap is the acknowledgement; failure must therefore be loud.** The receiver
   cancels the notification immediately (the gate takes seconds; a lingering notification
   reads as "nothing happened"), so a send that fails *must* post the failure notification
   — otherwise a command that never left the phone looks exactly like one that worked.
6. **`refuseWhileLocked` is the last line of defence** and must stay in the receiver —
   it is the only place both the SystemUI tap path and a direct `PendingIntent.send()`
   funnel through ([residual risk R1](../architecture/decisions.md)). It only refuses when
   the device is both secured and locked — with no lock configured there is nothing to
   unlock, and refusing would break the feature for no safety gain.
7. **`VISIBILITY_PRIVATE` + redacted public version** — see
   [security](../architecture/security.md).
8. **Distinct PendingIntent request codes per action+notification** — otherwise the extras
   of whichever intent was built first get silently reused for both buttons.
9. **Receiver work is bounded (9.5 s) on the container's appScope** — inside goAsync's
   ~10 s budget, no ad-hoc scopes.

## Channels

One channel: `gate_events` (IMPORTANCE_HIGH — that is what makes a heads-up). The old
`gate_service` channel belonged to a foreground service that was never built and is
actively deleted at startup ([dead ends](../architecture/decisions.md#recorded-dead-ends)).

## Related pages

[gate](gate.md) (`sendCommandAwait`) · [geo](geo.md) (the arrival flow) ·
[security](../architecture/security.md) · [ui-car](ui-car.md)
