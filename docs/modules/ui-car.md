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
  animates between templates; a snapshot and a state change landing together used to push
  two back to back).
- Templates: `MessageTemplate` when unconfigured (points at the phone), `PaneTemplate`
  otherwise — image + one row + two actions. **One configured layout, camera or not**; with
  no camera the image slot carries the gate state as a picture
  (`ic_gate_state_*`) instead of a still. `GridTemplate` is gone; see invariant 3.

## The refresh rule — read this before touching a template

The host allows **five template pushes per task**, and then, in its own words, *"displays an
error message to the user before closing the app"* — on the head unit that reads as **"this
action is not allowed while driving"** followed by Domofon disappearing. Backgrounding to
Maps and returning clears it, because re-entering from the launcher resets the quota. The
limit does not apply when parked, which is why it never showed up in testing.

A push is free only when it counts as a **refresh**, and for `PaneTemplate` the AndroidX
javadoc defines that exactly:

> the template title has not changed, and the number of rows and **the title** (not counting
> spans) of each row have not changed.

So the compared set is: **template title, row count, row titles.** Row `addText(…)` lines,
the pane image and the action titles are **not compared at all** — they may change on every
push forever.

That is the whole design of `GateScreen`: template title is `app_name`, the row title is the
constant `"Gate"`, and everything that moves (status, error, distance, the still, the
Open⇄Close label) lives in a slot the host ignores.

## Invariants

1. **The host validator is the entire security boundary** — `ALLOW_ALL` under
   `FLAG_DEBUGGABLE` only, permanently ([security](../architecture/security.md)). A
   release build refusing the DHU is the proof it is live.
2. **Only "is it configured at all" selects a template type.** Not fetch health, not the
   camera setting. This used to be *claimed* here and was not true: `cameraConfigured` chose
   between `GridTemplate` and `PaneTemplate`, so changing the camera source on the phone
   flipped the type under a live car session — and a type change is never a refresh. With one
   configured layout it is now true by construction. An unreachable camera degrades to the
   last good frame or a placeholder inside the same pane.
3. **The pane has exactly one row, always, and its title is a constant.** Not "its strings" —
   that wording was here, and it was wrong in the expensive direction. Row *texts* are not
   compared (see the refresh rule above), so what the previous version protected was cheap
   while what it neglected was not: `Row.setTitle(statusText)` put the single most volatile
   value in the single compared slot, and five status changes into a drive the host closed the
   app (Artur, 2026-07-27). The row today: constant title, status on text line 1, error-else-
   distance on text line 2.

   `GridTemplate` was removed in the same change and must not come back for this screen. Its
   refresh rule compares the template title *and each grid item's title*, and ours were
   `"<status> · <distance>"` and `"Open gate"`/`"Close gate"` — every push a step. It is also
   absent from the list of types the host accepts as a task's **last** template
   (`NavigationTemplate`, `PaneTemplate`, `MessageTemplate`, `MediaPlaybackTemplate`,
   `SignInTemplate`, `LongMessageTemplate`), so the camera-less path had no safe landing
   either.
4. **Two actions maximum** (`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`):
   `primaryAction` (Open ⇄ Close) + unconditional Stop — a gate you want halted is a gate
   you want halted whatever it thinks it is doing. [Decision D12](../architecture/decisions.md).
5. **Icons are `CarColor.DEFAULT`-tinted** so the host recolors the white silhouettes for
   a light theme; never tint the camera still (it would flatten to a monochrome smear).
   That applies to the `ic_gate_state_*` pictures too — they are white silhouettes for the
   same reason.
6. **State pictures and action arrows are different visual families.** `ic_gate_state_*` is a
   barred panel between two posts ("this is where the gate is"); `ic_gate_open`/`ic_gate_close`
   are arrows on a button ("this is what the button will do"). They appear on screen together,
   and an arrow in both places reads as the picture agreeing or arguing with the button.
7. **No setup reachable from the car** (Play IT-1): unconfigured, the screen says so and
   points at the phone. It does not offer to fix it.
8. **A refused command outranks the distance on the row's second text line** — the driver
   needs to know their tap did nothing more than they need to know how far out they are. The
   status keeps line 1 either way; it only had to compete for one slot back when the header
   was the only place text could live.

## Gotchas

- The frame bitmap (`CameraFrame.bitmap`) crosses the binder inside the template bundle — the
  960 px cap in [camera](camera.md) is what keeps it under `TransactionTooLarge`. The frame's
  JPEG half is never touched here: it is encoded lazily, so a car session never pays for the
  phone bridge's copy.
- The car surface deliberately ignores `GateUiState.audioNotice`. Not for refresh reasons —
  text lines are free — but because the row has two of them and a refused command and the
  distance are both worth more to a driver than a note about the audio track.
- Switching the camera source on the phone swaps the source under a live car session. The last
  frame survives the swap by design, so the pane keeps a picture and its shape throughout.
- `CarGateSession.onCreateScreen` calls `geofenceManager.sync()`. A car-only user never starts
  an activity, and an activity start was the only thing repairing a dropped fence — see
  [geo](geo.md) invariant 7.
- With `home.inAppFence` on, the session's `HomeDistanceTracker` runs for the whole session
  rather than only while the screen is visible: it is an arrival trigger then, and a trigger
  that stops when the driver switches to Maps covers none of the drive. The camera grabber is
  never included in that — stills cross the VPN and are worth nothing to a screen nobody is
  looking at.
- Real-car installs need a Play trusted source (Internal App Sharing / test track) —
  "Unknown sources" does not apply to Car App Library apps. The DHU
  (`scripts/dhu.sh`, Passat-profile ini) is the everyday test rig.
- The car app gets a separate manual Play review (IOT, Tier 2 "Car Optimized") — see the
  runbook in [build-and-release](../build-and-release.md).

## Related pages

[ui-notifications](ui-notifications.md) (the heads-up path) ·
[camera](camera.md) · [security](../architecture/security.md) ·
[testing](../testing.md) (DHU checklist)
