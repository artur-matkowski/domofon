# Module: ui-notifications (ui/notifications + receivers/GateCommandReceiver)

*[Wiki home](../README.md) › modules › ui-notifications*

## Responsibility

Heads-up notifications — the third presentation surface, and the only legal "pop up while
driving": Android Auto cannot be forced to open an app, but a high-importance notification
carrying a `CarAppExtender` draws over whatever the car screen shows, Maps included.

| Class | Owns |
|---|---|
| `GateEventNotifier` | *When*: the **single process-wide** collector of state changes, started once by `DomofonApp` |
| `domain/StateChangeAnnouncer` | *Whether*: the rule deciding which movements are worth a notification |
| `GateNotifier` | *How*: a stateless renderer — state-change, arrival, and command-failure notifications |
| `receivers/GateCommandReceiver` | The action button's backend: keyguard re-check, dismiss-as-ack, `sendCommandAwait`, failure notification |

## Invariants

1. **Exactly one state-change collector, application-wide.** Its predecessor was an
   `observe()` launched by both MainActivity and the car session — two collectors, every
   notification posted twice whenever phone and car were open together. Being permanent is
   free: `gateState` only moves while some lease holds the connection.

2. **Learning a state is not a state change**, and the filter for that is
   `StateChangeAnnouncer`, not a flow operator.

   This invariant used to read "`drop(1)` still skips the state a surface connects *into*
   (not news)", **which was false** — `drop(1)` dropped exactly one value in the life of the
   process, the initial `unknown`. Every teardown resets `gateState` to `unknown`
   ([gate](gate.md) invariant 4) and the next connection learns the real state from the
   retained topics, so `unknown → closed` looked like a change. Opening the car app posted a
   heads-up over the screen the user had just opened; so did opening the phone app, opening
   Settings, and every reconnect. Worst of all, `ArrivalFlow`'s own `acquire("arrival")` fired
   a state-change pop-up ~750 ms *before* the arrival pop-up it existed to deliver, so the
   headline feature shipped double. (Artur, live testing 2026-07-27.)

3. **A command silences the next state change, and only the next one.** Tapping Open hides
   the `opening` you caused and still announces the `opened` you were waiting for. The
   silence is *consumed* by the first change rather than expiring on a clock — Artur's rule,
   and the reason `StateChangeAnnouncer` compares command timestamps instead of counting down
   a deadline. `GateService.lastCommandAtMs` is a plain volatile read, not a flow, so it
   cannot lose a race with `gateState` and let the notification through anyway.
4. **Three fixed notification ids** (event 1001 / arrival 1002 / failure 1003): an arrival
   pop-up must not silently replace an event, and a failure report must never overwrite
   the thing that failed.
5. **The action button carries `GatePolicy.primaryAction`** — the same call every surface
   makes, so the notification can never contradict the car screen. The arrival
   notification gets an action only when state is known; with no state, offering the wrong
   action at 60 km/h is worse than offering none.
6. **Both extenders get the action**: the `CarAppExtender` draws the button on the head
   unit, the plain builder on the phone — separate action lists.
7. **Dismiss-on-tap is the acknowledgement; failure must therefore be loud.** The receiver
   cancels the notification immediately (the gate takes seconds; a lingering notification
   reads as "nothing happened"), so a send that fails *must* post the failure notification
   — otherwise a command that never left the phone looks exactly like one that worked.
8. **`refuseWhileLocked` is the last line of defence** and must stay in the receiver —
   it is the only place both the SystemUI tap path and a direct `PendingIntent.send()`
   funnel through ([residual risk R1](../architecture/decisions.md)). It only refuses when
   the device is both secured and locked — with no lock configured there is nothing to
   unlock, and refusing would break the feature for no safety gain.
9. **`VISIBILITY_PRIVATE` + redacted public version** — see
   [security](../architecture/security.md).
10. **Distinct PendingIntent request codes per action+notification** — otherwise the extras
   of whichever intent was built first get silently reused for both buttons.
11. **Receiver work is bounded (9.5 s) on the container's appScope** — inside goAsync's
   ~10 s budget, no ad-hoc scopes.

## Channels

One channel: `gate_events` (IMPORTANCE_HIGH — that is what makes a heads-up). The old
`gate_service` channel belonged to a foreground service that was never built and is
actively deleted at startup ([dead ends](../architecture/decisions.md#recorded-dead-ends)).

## Related pages

[gate](gate.md) (`sendCommandAwait`) · [geo](geo.md) (the arrival flow) ·
[security](../architecture/security.md) · [ui-car](ui-car.md)
