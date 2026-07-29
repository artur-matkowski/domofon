# Decision records

*[Wiki home](../README.md) › architecture › decisions*

Settled decisions with their reasons, accepted residual risks, and recorded dead ends.
**Do not re-litigate these unilaterally** — each entry exists because the alternative was
tried, measured, or priced. Reopen with Artur.

Format: Context / Decision / Consequences / Status.

---

## D1 — The app speaks MQTT only

**Context:** the home network also offers a REST gate API and a Postgres state table.
**Decision (Artur):** the app never touches either; one protocol, one connection, one
reconnect path, one security surface. **Consequences:** anything the app needs must appear
on the broker. **Status:** active.

## D2 — Kotlin host + embedded QML, not a pure Qt app

**Context:** two hard Android constraints. (1) Android Auto renders only Car App Library
templates — QML physically cannot appear on the car display. (2) Apps cannot force-launch
onto the car screen — the closest legal "pop-up while driving" is a high-importance
notification with `CarAppExtender`. **Decision:** Kotlin owns everything except the phone
scene; QML (via `QtQuickView`, first-class since Qt 6.8) draws the phone UI.
**Consequences:** the Qt-once-per-process restart machinery
([modules/ui-phone.md](../modules/ui-phone.md)); the fragile Qt Gradle build
([build-and-release.md](../build-and-release.md)). **Status:** active.

## D3 — RTSP is the camera default; a restreamer path is a peer, not an override

**Context (Artur's rule, 2026-07-24):** if it needs a backend or knows a camera brand, it
is a private tool rather than a publishable app. Snapshot endpoints are vendor-specific
and often Digest-only. **Original decision (2026-07-24):** RTSP is the one required camera
setting; stills are pulled out of the stream. An HTTP JPEG URL exists as an *optional*
override, never a requirement.

**Amended 2026-07-25 (Artur).** The override was the wrong shape for two reasons. Its
selection was *inferred* — a non-blank snapshot URL silently outranked the stream — so a
filled-in field was the only way to express a preference, the two URLs could never both hold
a value, and only one of them could ever be tried. And it carried no audio, which made the
restreamer path strictly worse than RTSP rather than differently good.

**Decision:** the user picks the source explicitly (`camera.source`, a dropdown). Two paths:

| | Picture | Audio |
|---|---|---|
| `RTSP` (default) | stills pulled from the stream | a track of the same session |
| `HTTP` | a JPEG URL polled on the interval | a *separate* audio-only RTSP stream |

**What keeps D3's actual intent intact:** RTSP is still the only path a fresh install can
use, so a stranger with nothing but a camera is unaffected — the HTTP path needs a
restreamer someone deliberately set up. Nothing vendor-specific is compiled in: no URL is
composed in code, no camera brand is named, and every address is still typed at runtime
(D6). **Consequences:** the settings screen shows and hides rows per source (the first use of
`Preference.isVisible` in this app), and the grabber reopens its source when the resolved
`CameraFeed` changes, so switching applies to a live session. **Status:** active. See
[modules/camera.md](../modules/camera.md).

## D3a — Two RTSP sessions are fine; two to the *camera* are not

**Context:** the deleted `RtspAudioPlayer` opened a second RTSP session and knocked the
stills stream off every cycle ([troubleshooting.md](../troubleshooting.md)). The rule that
entry left behind was recorded as "one session carries both", and its own suggested remedy
was "a go2rtc restream — one ingest, many clients — **not** a second direct connection".
**Decision (2026-07-25):** the constraint is *concurrency at the camera*, not session count.
The HTTP path's `RtspAudioSource` is a second session by design, pointed at a restreamer
while the pictures come from Frigate — two different servers, no contention. Aiming both
halves at the camera itself reproduces the original failure exactly, which is why the audio
row's summary says so in as many words. **Status:** active.

## D4 — Decoded video frames are never read on the CPU

**Context:** `ImageReader` + `Image.getPlanes()` on a GPU-only MediaCodec buffer is an
**uncatchable native JNI abort** (`nativeCreatePlanes`), fatal on the test phone (Exynos).
**Decision:** frames come back through an offscreen EGL context and `glReadPixels`
(`OffscreenTextureReader`), which also scales on the GPU for free. **Status:** permanent —
this is a platform behavior, not a preference. See
[modules/camera.md](../modules/camera.md) and
[troubleshooting.md](../troubleshooting.md) → `nativeCreatePlanes`.

## D5 — Connect-on-demand, never 24/7

**Context:** battery, and retained state making a standing connection pointless.
**Decision:** the MQTT connection exists exactly while a `ConnectionLease` is held
(foreground UI, settings screen, car session, arrival pop-up, one-shot command, and the
bounded post-command hold of [D17](#d17--a-command-from-a-notification-holds-the-connection-for-45-s-afterwards)).
**Consequences:** teardown resets gate state so the arrival pop-up can never announce
stale state as fresh. **Status:** active.

## D6 — Nothing deployment-specific is compiled in

**Context:** the repo is public and a published APK is a public artifact; `strings` on it
is not a difficult attack. **Decision:** broker, topics, home coordinates and both camera
URLs live in `ConfigStore`; `BuildConfig` carries no app configuration; the release script
scans the bundle for secret sentinels. **Status:** active — see
[security.md](security.md).

## D7 — No HiveMQ automaticReconnect; rebuild the client from scratch

**Context (measured on device):** after a network transition, every built-in reconnect
attempt was refused with CONNACK NOT_AUTHORIZED — while a cold start with the same
credentials connected instantly. **Decision:** reconnect = build a new client
(`GateService` + `ReconnectPolicy`, 1 s → 30 s doubling). **Status:** active; the full
note lives on `HiveMqTransport`.

## D8 — SharedPreferences, not DataStore

**Context:** `BroadcastReceiver`s need config *now* (~10 s goAsync budget, no coroutine to
suspend in), and `androidx.preference` is synchronous by design. **Decision:** a few
hundred bytes of SharedPreferences, with the two real secrets Keystore-encrypted on top.
**Status:** active.

## D9 — Manual DI (AppContainer), no Hilt/Koin

**Context (refactor, 2026-07-25):** AGP 9's built-in Kotlin has no KSP/kapt wired, and the
Qt Gradle build is fragile; annotation processors and bytecode agents are risk with no
payoff at this size. **Decision:** one hand-written composition root, constructor
injection, one interface at the seam that varies (`MqttTransport`). **Status:** active —
see [modules/app-container.md](../modules/app-container.md).

## D10 — Plain ViewModel classes, not androidx.lifecycle.ViewModel

**Context (refactor, 2026-07-25):** the dominant restart mode is a *process* restart (Qt
loads once per process), which no ViewModelStore survives; a car `Screen` is not a
ViewModelStoreOwner; notifications have no owner at all. Hot state lives in the
container's singletons anyway. **Decision:** `GateViewModel` is a plain class per surface,
on the surface's lifecycle scope. **Status:** active.

## D11 — No mocking framework

**Context (refactor, 2026-07-25):** manual DI makes hand-rolled fakes cheap
(`FakeTransport`, map-backed `RawPrefs`), and a bytecode agent is one more thing the
AGP9/Qt build could trip over. **Decision:** JUnit4 + kotlinx-coroutines-test only.
**Status:** active — see [testing.md](../testing.md).

## D12 — Phone shows three buttons; the car shows two (see D19 for which)

**Context:** a car Pane takes at most two actions
(`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`); the arrival notification gets exactly
one because a driver reaching for a heads-up needs one target. **Decision (post-b6f18d5):**
the divergence is intentional, not drift — phone: Open/Close/Stop; car:
`GatePolicy.primaryAction` + Stop; notification: primary only.

**Amended 2026-07-27.** Two changes, neither touching the shape above.

*The `GridTemplate` is gone; the car is a `PaneTemplate` in every configured state.* The grid
served the camera-less config and could not be made cheap: its refresh comparison covers the
template title and each grid item's title, and ours were `"<status> · <distance>"` and
`"Open gate"`/`"Close gate"`. It is also not a legal *last* template for a task. Camera-less
installs now get the same pane with the gate state in the image slot. See
[modules/ui-car.md](../modules/ui-car.md).

*`primaryAction` widened.* It was `state == "opened"`, so every mid-travel state offered
"Open gate" — an action that does nothing, on the one button the driver has. Now `opened`,
`opening` and `stuck_opening` offer Close; `closed`, `closing`, `stopped`, `stuck_closing`
and `unknown` offer Open. `stopped` stays on Open deliberately: halted mid-travel it is
neither, and Open is the safer default for someone driving up to it.

**Superseded in part, 2026-07-29.** The car's *second* button is no longer Stop — it is the
audio toggle. The rest of this decision stands: the shape (phone three, car two, notification
one) and `primaryAction` are unchanged. See
[D19](#d19--the-cars-second-button-is-the-audio-toggle-not-stop).

**Status:** active, with the second slot reassigned by D19.

## D13 — Two arrival triggers in parallel: Play Services primary, in-app opt-in

**Context (Artur, live testing 2026-07-27):** a 10 km round trip produced no arrival pop-up,
and **nothing in the app could say why**. Play Services owns the geofence in its own process:
it reports no schedule, no liveness and no failure, and `setNotificationResponsiveness` is a
hint that cannot be read back. So "never registered", "registered but never evaluated" and
"delivered into a dropped pop-up" produce one identical symptom with three different fixes.

**Decision:** keep the native fence as the primary trigger — it is the only one that works
with the app dead, which is the normal case — and add a second, **opt-in**, running in
parallel: `home.inAppFence` evaluates `HomeDistanceTracker`'s own readings against the same
radius (`domain/HomeFenceCrossing`). It is not a replacement and must never become the
default.

**Why it is allowed to exist:** it costs no new permission (same coordinates, same "Allow all
the time" grant, which is why the setting depends on `home.enabled`), and it is *observable* —
the app can report its last crossing and its next evaluation, which is the diagnostic the
failing drive could not produce.

**Consequences:** `ArrivalFlow` gains a **persisted** de-duplication guard, because the two will
routinely both notice one approach and the native one delivers into a dead process, so an
in-memory latch would not see the other's pop-up. *(It was a flat 10-minute cooldown until D15
cut it to a 90-second cross-trigger window — ten minutes did this job and also refused real
second arrivals.)* With the switch on, the car session runs the
distance tracker for the whole session rather than only while its screen is visible — it is a
trigger then, and a trigger that stops when the driver opens Maps covers none of the drive.
The camera grabber is deliberately *not* included in that widening.

**Accepted unknown:** an Android Auto session is not a foreground service, so whether the OS
keeps delivering location to a backgrounded projected session is a device question no amount
of code review settles. If it does not, the in-app fence only fires while Domofon is the
visible car screen — which is still strictly more than before.

**Status:** active. See [modules/geo.md](../modules/geo.md).

### D14 — Notifications are silent while a Domofon surface is in front; an arrival is a direction and nothing more

**Context (Artur, live testing 2026-07-28).** Four complaints from one drive: a heads-up
fired over the Domofon car screen itself; the body carried a full ISO-8601 timestamp; a second
arrival pop-up never drew a heads-up over Maps; and "Approaching home" was on the screen of a
car parked on its own driveway. The last two share a root cause — an untapped notification is
cancelled only by a tap, so it sat in the shade overnight, and the next `notify()` onto the
same id was an *update*, which the car host does not draw a heads-up for.

**Decision.**

1. **Suppress while visible, on two surfaces.** The car screen (Android Auto `Session`
   STARTED) and `MainActivity`. Not Settings — it shows configuration, not gate state, so a
   notification is still the only thing telling the user the gate moved. State-change and
   arrival notifications only; the command-failure notification stays, because it answers a
   button the user pressed. Full-suppression rather than "post it without the `CarAppExtender`"
   was Artur's call: while driving, a shade entry nobody will read is not worth having.
2. **Notifications expire** (arrival 30 s since D15, event and failure 10 min) and are cleared when a
   surface comes to the front. The arrival timeout is under the arrival de-duplication window on
   purpose, so the next arrival always lands on an empty id and counts as new. *Not* by
   cancelling before re-posting: `cancel` and `notify` are asynchronous and the notify can be
   swallowed.
3. **The crossing direction is the trigger, and nothing corroborates it.** Artur's rule, in his
   words: *"coming back from outside, to inside of the circle."* `GEOFENCE_TRANSITION_ENTER`
   already **is** that direction, so it is trusted as delivered.

   *First attempt, retracted the same day.* This was implemented as a persisted `FenceSide`
   that refused an ENTER unless the app had *separately* observed a departure. Artur rejected
   it immediately, with the case that settles it: switch the phone off at home, drive away with
   the app dead, switch it on again already on the way back — nothing observed a departure,
   the crossing is still unambiguously inward, and the rule refuses a real arrival. Two further
   reasons it was wrong: the spurious ENTER it guarded against was **never observed** (the
   "Approaching home" on the driveway turned out to be the stale notification of point 2), and
   it made a universal signal conditional on a weaker, intermittent one. Direction needs no
   second opinion.

   What survives is a **readout**. `GEOFENCE_TRANSITION_EXIT` stays registered and every
   confident distance reading still records a side, purely so the Settings row can answer "did
   Play Services see me leave?" after a drive that produced no pop-up — the same
   split-the-failures-apart argument as D13. It gates nothing.

**Consequences.** `SurfacePresence` is a new process-wide singleton in `AppContainer`, and the
notification path acquires its first dependency on UI lifecycle. Play Services wakes the
receiver once more per trip (the EXIT), for diagnosis only. `arrivalRefusal` replaces
`mayAnnounceArrival` and returns the *reason*, so every refusal reaches the status row.

**Also decided (2026-07-28):** the in-app fence claims a side only when the fix's own accuracy
does not span the fence (`sideOf`). This is not a second guard — it is what makes *direction*
trustworthy, since under a bare `meters <= radius` one cold cell-derived fix between two good
ones is itself an outside→inside crossing.

**Rejected:** gating arrivals on Activity Recognition or a car-connection signal. Both add a
permission or a dependency in order to second-guess a direction the fence already reports.

**Status:** active, with point 2's numbers superseded by D15. Point 1 (suppress while a surface
is visible) and point 3 (direction is the trigger) stand unchanged.

## D15 — One pop-up per *crossing*, not one per ten minutes

**Context (Artur, live testing 2026-07-28, same drive as D14).** In the car, head unit
connected: out past the fence and back in → the pop-up appeared, exactly as intended.
Then, immediately and in the same session, out past the fence and back in again → **nothing.**

The cause was D13's consequence: `ArrivalFlow` carried a **persisted ten-minute cooldown**, and
the second crossing landed inside it. The refusal was even recorded honestly —
`Last event ignored: another pop-up was posted minutes ago` — which is the D13 status model
working exactly as designed, reporting a rule that was wrong.

The rule, in Artur's words: *a pop-up on the head unit every time the fence is crossed inwards,
regardless of any other application state, or logic.*

**Decision.** The cooldown never existed to rate-limit arrivals. It had one job — the native
fence and the in-app fence routinely notice **one** approach a few seconds apart, and the
native one delivers into a dead process, so nothing in memory can de-duplicate them. Ten
minutes was roughly twenty times what that job needs, and the surplus ate a real arrival.
So `arrivalRefusal` now refuses on two grounds, neither of which is "too soon":

1. **The other trigger already announced this crossing** — a *different* `source` within
   `CROSS_TRIGGER_WINDOW_MS` (90 s). Same source is a different matter: Play Services does not
   deliver ENTER twice without an EXIT between them, so a second ENTER is a second crossing.
2. **The last pop-up is still on screen** — within `ARRIVAL_POPUP_TTL_MS` (30 s). This is not a
   rule about arrivals at all but about notification id 1002: posting onto a live one is an
   *update*, which the car host draws no heads-up for (D14 point 2). Kept strictly under the
   window in 1, which is D14 point 2's ordering invariant, restated in the new numbers.

Both constants live in `GeofenceStatus`'s companion, adjacent, and `GateNotifier` reads the TTL
from there rather than keeping its own. The ordering is asserted in a unit test outright, so
editing one number alone fails the build rather than the drive.

**Also decided:** the arrival pop-up body carries the crossing time as `HH:mm` — the *detection*
time, not the moment the notification was built, which can be 9.5 s later. It answers "is this
the arrival happening now, or one I already ignored?" at a glance, which is the question a
notification with a 30-second life should never make the driver ask twice.

**Consequences.** An arrival pop-up now lives 30 s instead of 5 min. Event and failure
notifications (ids 1001/1003) keep their 10 minutes and are untouched, as is the gate
state-change path. The hardware check "fire the debug trigger twice inside ten minutes →
exactly one pop-up" **inverts**: two pop-ups is now the correct outcome.

**Status:** active. See [modules/geo.md](../modules/geo.md) invariant 8.

## D16 — A repeating notification alternates between two ids

**Context.** D14 point 2 and D15 both work around the same platform fact: `notify()` onto an id
that still holds a live notification is an **update**, and the Android Auto host draws no
heads-up for an update — it changes the shade entry and says nothing. For the arrival pop-up
that is solved by time (the pop-up expires long before another arrival may be announced).

For **gate state changes** it cannot be, and the reason is structural rather than a matter of
picking a better number. One gate cycle is *two* announcements: press the wall button and the
gate reports `opening`, then `opened` fifteen to twenty-five seconds later. Both are news,
`StateChangeAnnouncer` announces both, and the second landed on the still-live id of the first.
So the heads-up saying the gate had **finished moving** — the one a driver actually wants — was
being dropped on every single cycle, and had been since notifications existed.

**Decision.** The gate event gets a **second id (1004)**, and `domain/freeNotificationSlot`
picks whichever of the pair is not currently in `NotificationManager.getActiveNotifications()`.
The other is cancelled *before* the post, so the two never overlap in the shade. An isolated
event still lands on 1001, so nothing observable changes in the common case.

**Why not the two obvious alternatives.**

- *A shorter `EVENT_TIMEOUT_MS`.* It would have to sit under a gate travel time that is not
  predictable, and guessing low trades a missing heads-up for a notification that disappears
  while the driver is reaching for it. Time cannot solve this one; it only solved the arrival
  because two arrivals are minutes apart by nature.
- *Cancel, then re-post onto one id.* Rejected in D14 point 2 and still rejected: against the
  **same** id, `cancel` and `notify` are asynchronous and the cancel can land after the notify
  and swallow it. Against **different** ids there is no such interaction — cancelling the old
  slot cannot affect the post to the new one in any ordering. That asymmetry is precisely what
  makes two ids a fix and one id a race.

**Consequences.** Four ids for three kinds of notification, which is worth stating plainly
because "one id per kind" was the old invariant. `clearTransient` cancels both event slots.
Both slots live at once is only reachable if a cancel is lost; that posts one update and then
self-heals, and a unit test pins it. The `getActiveNotifications` call is guarded — on a host
where it fails, the primary slot is used and the behaviour is exactly what it was before.

**Not done here:** the **failure** notification (1003) has the same shape — two failed commands
inside its 10-minute timeout give one heads-up and one silent update — and invariant 7 says it
must be loud. Left alone deliberately rather than by oversight; it is a smaller window and a
rarer sequence, and widening this change to it was not asked for.

**Status:** active. See [modules/ui-notifications.md](../modules/ui-notifications.md)
invariant 15.

## D17 — A command from a notification holds the connection for 45 s afterwards

**Context.** Tapping *Open gate* on a heads-up produced no feedback whatsoever: the
notification dismissed itself and nothing else happened for the twenty seconds the gate takes
to move (Artur, live testing 2026-07-29). The suppression rules looked like the culprit and
were not. `sendCommandAwait` wraps the publish in `acquire("command").use { }`, so with no
surface open the app **disconnected within milliseconds of the broker's publish ack** — and the
gate does not report `opening` for another second or two. There was never a state change to
suppress; the app was not listening when it happened.

Worse, the miss is permanent for that cycle. Reconnecting later gets the state as a *retained*
value, and D18's rule refuses to announce those.

**Decision.** `CommandFollowThrough` claims a sixth lease (`command-follow`) for
`HOLD_MS = 45_000`, armed by `GateCommandReceiver` **before** the send. One gate cycle plus
margin. The announcing is not its business — `GateEventNotifier` is already the single
process-wide `gateState` collector and sees the cycle for free.

**Why the lease is taken synchronously.** Acquiring it inside the coroutine would let it land
*after* `sendCommandAwait` released its own. The teardown in between resets `gateState`, and
the reconnect costs a VPN handshake during which the live message is missed — leaving the
window open and empty, which is the failure it exists to prevent wearing a longer timer.
Re-arming takes the new lease before releasing the old one for the same reason.

**Does this break D5?** No. D5 forbids a *standing* connection, not a bounded one: this is
user-initiated, expires on its own, and covers the one situation where a lease would otherwise
be dropped between asking a question and being answered.

**Consequences.** Two heads-ups per notification-driven cycle on the head unit (`opening`, then
`opened`), which is exactly the pair D16's second id exists to make work. The tail of the hold
is **best effort**: `goAsync` guarantees receiver priority for about ten seconds, which covers
the first announcement comfortably; the rest runs in a cached process that Android may reclaim,
costing the second notification and nothing else. Guaranteeing it needs a foreground service,
whose permanent notification is not worth a 45-second window.

**Status:** active. See [modules/gate.md](../modules/gate.md).

## D18 — Retained is never news; your own tap is, unless you are looking at it

**Context.** D14 gave `StateChangeAnnouncer` three rules: *learning is not news* (a transition
out of `unknown`), *your own tap is not news* (one silenced change per command), and *nothing
is news to someone already reading it*. The middle one is what swallowed the feedback in D17's
symptom, and the first one was weaker than it looked.

**Decision.** Two rules, not three.

1. **Retained is never news.** `GateState` carries `live`, set from `!signal.retained` in
   `GateStateReducer`. A retained message is the broker replaying its memory.
2. **Nothing is news to someone already reading it.** Unchanged, and checked last so a
   suppressed change still updates `lastSeen`.

The own-tap silence, `GateService.lastCommandAtMs`, and the 20-second window are **deleted**.

**Why rule 1 got stronger.** "A transition out of `unknown` is learning" only covered the
*first* move of the retained burst. The burst is last-value-per-signal in arbitrary order, so it
routinely moves the state twice — and the second move was indistinguishable from a real one.
Keying on `retained` covers the whole burst by construction, and makes the arrival flow's own
`acquire("arrival")` structurally incapable of announcing anything.

**Why the own-tap silence went.** Every command path either has a screen in front of the user —
the QML button, the car pane — in which case rule 2 already silences the echo, or it is the
button *inside a notification*, which dismisses itself on the tap. In that second case the echo
is the only feedback there is. A rule whose one remaining condition was `surfaceVisible` is
rule 2 wearing a clock.

**Consequences.** Tapping Open in the phone app and then locking the phone now announces the
`opened` that follows, where it used to be silent. That is the same behaviour as backgrounding
the car app mid-cycle, and it is wanted: you walked away, so the notification is the only
channel left.

**Status:** active. See [modules/ui-notifications.md](../modules/ui-notifications.md).

## D19 — The car's second button is the audio toggle, not Stop

**Context.** D12 gave the car pane the primary action plus Stop, "unconditional because a gate
you want halted is a gate you want halted". A `Pane` takes at most two actions
(`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`), so a third button is not available. Gate audio
takes the car stereo for as long as Domofon is open and there was no way to silence it from the
head unit, which made the choice gate-or-music for the whole drive (Artur, live testing
2026-07-29).

**Decision.** Stop moves off the car screen; the second slot becomes a mute toggle writing the
global `camera.audioEnabled`. Stop remains one of the phone's three buttons.

**Why Stop lost.** It is the answer to a gate misbehaving, which is a stop-the-car problem
rather than a glance-and-tap one. Muting is the opposite: wanted while moving, wanted at once,
and worthless anywhere but here.

**Why not the header action strip.** `PaneTemplate.setActionStrip` is free and would have held
both. Rejected as a *layout* answer to a *priority* question — the strip is the smaller, less
reachable target, so it would only have moved the problem to whichever button went there.

**Why the global setting rather than a car-local mute.** One lever. The car and the phone
cannot end up disagreeing about whether the gate is audible, and the Settings switch stays the
same control it always was. The cost is a camera-session reopen per toggle: `audioEnabled` is
part of `CameraFeed` identity, so the RTSP handshake runs again (1-3 s over the VPN, last still
left on screen throughout). Accepted — toggling is rare and the alternative is a second source
of truth.

**Play IT-1.** IT-1 bars *configuration* from the car — brokers, credentials, locations. A mute
button is a playback control, and sits beside the one-touch device control IT-1 explicitly
allows.

**Also here: gate audio ducks, and never pauses.** Both players requested `AUDIOFOCUS_GAIN`
(`USAGE_MEDIA` + ExoPlayer's `handleAudioFocus = true`) — *permanent* focus, the request an app
makes when it is the thing the user chose to listen to. The system answers it by stopping
everything else, permanently, and a permanent loss is not something the loser retries. Hence
Spotify stopping and never coming back. `GateAudioFocus` now requests
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` by hand.

**Why by hand.** There is no one-line version. media3's `AudioFocusManager.setAudioAttributes`
ends with `checkArgument(focusGain == AUDIOFOCUS_GAIN || focusGain == 0, "Automatic handling of
audio focus is only available for USAGE_MEDIA and USAGE_GAME.")`, and that method is only
reached when `handleAudioFocus` is true. Changing the usage to one that maps to a ducking
request while leaving automatic handling on is an `IllegalArgumentException` at the first frame.

Owning the request means owning the losses, and every one of them is a **volume change**: the
stream is live, and a paused live stream accumulates a backlog it can only shed by seeking, so
pausing for a navigation prompt would mean returning a prompt's worth of time behind the gate.

**Status:** active, **untested on hardware**. If gate audio proves inaudible under ducked music,
drop the focus request entirely — audio then mixes on top and nothing else is disturbed. If a
head unit refuses to route audio held without focus, revert to `handleAudioFocus = true` and
rely on the toggle alone. See [modules/ui-car.md](../modules/ui-car.md) and
[modules/camera.md](../modules/camera.md).

---

## Accepted residual risks

Knowingly left open, because closing them would cost the feature they protect. Recorded so
a future session does not "discover" them and quietly break the product to fix them.

### R1 — A notification listener can fire the gate command

The heads-up notification carries a `PendingIntent` to `GateCommandReceiver`. Any app
granted *notification access* (routinely handed to smartwatch companions, automation apps,
notification-history apps) can read `sbn.notification.actions[0].actionIntent` and
`send()` it verbatim. `FLAG_IMMUTABLE` does not help — the attacker replays our intent
rather than editing it, so a nonce would be replayed with it.
`Action.setAuthenticationRequired(true)` does not help either — SystemUI enforces it only
for taps it renders.

*Mitigated, not closed:* `GateCommandReceiver.refuseWhileLocked` blocks it whenever the
device is locked and secured — the phone-in-a-pocket case.

*Why not closed:* the only complete fix is a confirmation screen, which deletes the
one-tap-from-Maps behavior the notification exists for. **Decision (Artur, 2026-07-23):
keep one-tap, accept the residual.** Exploiting it requires the user to have installed
something malicious *and* granted it notification access.

### R2 — Cleartext HTTP is permitted app-wide

(`res/xml/network_security_config.xml`, 2026-07-24.) For the HTTP camera path only. The
block cannot be lifted per-host because **every address in this app is typed by the user at
runtime** — the whole point of D6 — so it is lifted globally.

*What is actually exposed:* nothing, on a default install. The default source is RTSP (a raw
socket this policy never governed) and the image field is empty unless the user fills it.
Gate **commands** never travel HTTP — they are MQTT, with its own TLS switch.

*Wider than it was (2026-07-25, D3):* the HTTP path is now something a user selects rather
than a rarely-set override, so on an install that picks it, cleartext frames are the norm
rather than the exception. This does not change the assessment — the traffic is a gate still
on a home LAN or inside the VPN, and the alternative is excluding every consumer camera and
homelab restreamer — but it is no longer a corner case.

*Why not closed:* requiring `https://` would exclude essentially every consumer IP camera
and most homelab restreamers; a pinned host list would put a deployment address back in
the APK, undoing D6. The config permits cleartext; it does not require it.

### R3 — One binder-level click on the car screen moves the gate

The grid/pane click listener sends the command with no confirmation, so a host-validator
misconfiguration would be immediately exploitable rather than merely dangerous. The
validator ([security.md](security.md)) is the real control and is correct; a confirmation
template would be defence in depth. Car quality rule IT-1 explicitly permits one-touch
control while driving, so the confirmation is a genuine trade, not an obvious win. Parked
in the [troubleshooting.md](../troubleshooting.md) backlog.

---

## Recorded dead ends

Designs that were documented or sketched and **never shipped** — kept so nobody re-derives
or resurrects them by accident.

- **The Python `bridge/`** (original chapter 02). A polling Postgres→MQTT bridge,
  scaffolded and then dropped (`0901e2e`); the deployed reality is hc12-web-service and
  the [hc12 contract](mqtt-contract.md). Design points worth keeping: state topic
  retained; LWT `online`/`offline` published on every reconnect; command actions
  allowlisted. A LISTEN/NOTIFY upgrade was sketched and never needed.
- **QtMultimedia RTSP video** (original chapter 04 §2). Live video in QML via Qt's FFmpeg
  backend — CMake link, QML and lifecycle were sketched; none of it ever met a device. The
  shipped path is media3 stills + audio ([modules/camera.md](../modules/camera.md)).
  Latency fallbacks from that chapter (camera substream, go2rtc restream) remain valid
  operational advice — go2rtc in particular fixes the choppy-audio caveat.
- **The geofence foreground service** (original chapter 06). A 15-minute
  location-foreground-service on geofence entry. Play removed geofencing as an approved
  location-FGS use case (August 2026), and retained topics make the arrival pop-up work
  without it. If a foreground service is ever added, its honest type is `connectedDevice`
  — see the note in [build-and-release.md](../build-and-release.md).
- **HTTP snapshot as the primary camera source — *straight off the camera*.** Worked, but
  made the app deployment-shaped (vendor paths, Digest auth). Demoted to an optional override
  by D3, and that override is itself gone. **Note the amendment (2026-07-25):** HTTP images
  *are* now a selectable path, because pointing them at a restreamer answers the original
  objection rather than ignoring it — Frigate or go2rtc terminates the vendor path and the
  Digest auth, so the app needs to know neither. What stays dead is the app itself reaching
  into a camera's firmware-specific snapshot endpoint. See D3.
- **`Build.MODEL`-derived MQTT client id.** Collides for every user on the same phone
  model; two strangers would evict each other from the broker in a loop forever. Replaced
  by a random per-install id.

## Related pages

[overview.md](overview.md) · [security.md](security.md) ·
[mqtt-contract.md](mqtt-contract.md)
