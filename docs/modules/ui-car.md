# Module: ui-car (ui/car)

*[Wiki home](../README.md) › modules › ui-car*

## Responsibility

The Android Auto surface: `DomofonCarAppService` (validator + session factory),
`CarGateSession` (session-scoped resources), `GateScreen` (templates). No MQTT, no
parsing, no policy here — everything renders `GateViewModel`'s derived state.

## Behavior

- `CarGateSession.onCreateScreen` builds the session's grabber, tracker and ViewModel from
  the container, pairs a `ConnectionLease("car-session")` with a lifecycle observer
  (observer registered *before* acquiring, so a session the host tears down mid-setup
  cannot leak the lease), and starts/stops the grabber+tracker with the session.
- `GateScreen` invalidates on `merge(uiState, frame)` with a 150 ms debounce (the host
  counts non-refresh templates against a quota and animates between them; a snapshot and a
  state change landing together used to push two templates back to back).
- Templates: `MessageTemplate` when unconfigured (points at the phone), `GridTemplate`
  (two buttons) without a camera, `PaneTemplate` (still + one row + two actions) with one.

## Invariants

1. **The host validator is the entire security boundary** — `ALLOW_ALL` under
   `FLAG_DEBUGGABLE` only, permanently ([security](../architecture/security.md)). A
   release build refusing the DHU is the proof it is live.
2. **Which template is built depends only on *configuration*, never on fetch health** —
   the type cannot flip mid-session; an unreachable camera degrades to the last good frame
   or a placeholder icon inside the same pane.
3. **The pane has exactly one row, always, and its strings must stay stable across
   snapshots.** The host only updates a pane *in place* when the new template counts as a
   refresh; change the row count or strings and you get a screen transition — the whole
   head unit dims and comes back (the observed per-snapshot "blink"). The row: status as
   title, error + distance as its two text lines, the bitmap the only per-snapshot
   difference.
4. **Two actions maximum** (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`):
   `primaryAction` (Open ⇄ Close) + unconditional Stop — a gate you want halted is a gate
   you want halted whatever it thinks it is doing. [Decision D12](../architecture/decisions.md).
5. **Icons are `CarColor.DEFAULT`-tinted** so the host recolors the white silhouettes for
   a light theme; never tint the camera still (it would flatten to a monochrome smear).
   `IMAGE_TYPE_ICON` on grid items because only icons are tinted.
6. **No setup reachable from the car** (Play IT-1): unconfigured, the screen says so and
   points at the phone. It does not offer to fix it.
7. **A refused command outranks the status/distance in the header** — the driver needs to
   know their tap did nothing more than anything else the header could say.

## Gotchas

- The frame bitmap crosses the binder inside the template bundle — the 960 px cap in
  [camera](camera.md) is what keeps it under `TransactionTooLarge`.
- Real-car installs need a Play trusted source (Internal App Sharing / test track) —
  "Unknown sources" does not apply to Car App Library apps. The DHU
  (`scripts/dhu.sh`, Passat-profile ini) is the everyday test rig.
- The car app gets a separate manual Play review (IOT, Tier 2 "Car Optimized") — see the
  runbook in [build-and-release](../build-and-release.md).

## Related pages

[ui-notifications](ui-notifications.md) (the heads-up path) ·
[camera](camera.md) · [security](../architecture/security.md) ·
[testing](../testing.md) (DHU checklist)
