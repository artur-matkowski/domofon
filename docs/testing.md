# Testing

*[Wiki home](README.md) › testing*

Two tiers: JVM unit tests (agents run these) and the hardware checklist (only Artur can —
the phone, the DHU, the Passat). **Nothing is "done" until it has run on a device.**

## JVM unit tests

```bash
cd <scratchpad-copy>/app && ./gradlew --no-daemon :app:testDebugUnitTest
```

The test compile rides the same `QtBuildTask` wiring as the main compile, so it works
wherever `build-debug.sh` works. If a test-only invocation ever misbehaves around the Qt
task, run `:app:assembleDebug` first to populate the AAR.

Ground rules:

- **No mocking framework** ([decision D11](architecture/decisions.md)) — hand-rolled
  fakes plus real objects over them.
- Tests never *instantiate* anything touching `org.qtproject.*` (compile-time references
  are fine). `QmlGateBinder` is exercised through `GateUiState` construction instead.
- `unitTests.isReturnDefaultValues = true` stubs `android.util.Log`;
  `testImplementation("org.json:json")` shadows the android.jar org.json stub, which would
  otherwise return null for every parse.
- Timestamps in test payloads must be **safely in the past** — the reducer disbelieves
  stamps more than 300 s ahead of the real clock.

### Fakes catalog (src/test)

| Fake | For | Drives |
|---|---|---|
| `data/mqtt/FakeTransport` | `MqttTransport` | `connectComplete()`, `connectFailed()`, `dropConnection()`, `deliver(topic,payload,retained)`, `refuseSubscription()`, `ackPublishes`; records subscriptions/publishes/closes. Never calls back from inside `connect()` — the transport contract. |
| map-backed `RawPrefs` | the config parser | plain `Map<String,String>` / `Map<String,Boolean>` |
| `MutableStateFlow`s | `GateViewModel` inputs | config/camera-status/frame/distance |

### Suite map

| Suite | Pins |
|---|---|
| `domain/GateProtocolTest` | signal↔state maps, decode/attribution, encode, tx-never-retained |
| `domain/GateStateReducerTest` | out-of-order retained burst, live-wins-ties, never-backwards, future-ts non-poisoning, reset |
| `domain/GatePolicyTest` | primaryAction + the full status-line matrix (incl. DEGRADED ≡ CONNECTED), audio-notice wording |
| `domain/ReconnectPolicyTest` | 1 s→30 s doubling, reset |
| `domain/HomeDistanceFormatTest` | zone bands vs radius, rounding, locale-pinned dot |
| `domain/HomeFenceCrossingTest` | the in-app fence rule: first reading never fires, inward-only, re-arms on leaving, `reset()`; `sideOf`'s accuracy margin, incl. "a coarse fix cannot manufacture a crossing between two good ones" |
| `domain/GeofenceStatusTest` | the Settings row's three-failures split, rejection-newer-than-delivery; and the arrival guard — cross-trigger dedup, **a second crossing minutes later still announces**, pop-up-still-on-screen, TTL < window ordering, side never refuses |
| `domain/StateChangeAnnouncerTest` | the whole notification "whether" table: learning is not news, own-tap silence consumed by one change, surface-visible suppression |
| `domain/GateTimestampTest` | `HH:mm` from a wire `ts` and from an observed epoch; 24-hour always; the two wire forms and the raw-string fallback |
| `domain/camera/JpegDataUriTest` | the QML bridge's frame encoding: prefix, round-trip, uniqueness, no line breaks |
| `domain/config/DomofonConfigParserTest` | trims, clamps, prefix normalisation, tls-port default, wire equality, toString redaction, **camera source + `CameraFeed` resolution** |
| `domain/config/CameraSettingsRowsTest` | which camera rows each source shows, and that `ALL` covers every one |
| `data/mqtt/ConnectionErrorMessagesTest` | cause-chain walk (bounded), name-matched shaded timeouts |
| `data/mqtt/GateServiceTest` | lease lifecycle, stale-handle silence, wire rebuild, watchdog, backoff, teardown semantics, command paths, awaitFreshState settle |
| `data/camera/CameraFrameGrabberTest` | session lifecycle: **close completes before the next open**, reopen on a feed change, *no* reopen on an interval change, retired sources cannot report, `stop()`+`start()` never overlap |
| `ui/shared/GateViewModelTest` | derivation + eager initial value, independent picture/audio health |

What is still untested in `data/camera` is everything that needs a device — the players, the
EGL readback, the HTTP fetch. The *lifecycle* is now covered: `CameraFrameGrabber` takes flows
and a source factory rather than a `ConfigStore` and a `Context`, so a fake `FrameSource`
drives the whole open/close sequence on the JVM. That suite is a regression net for the
2026-07-25 "changing a camera setting does nothing until you force-stop" bug
([troubleshooting](troubleshooting.md)) — its fake fails the run outright if a second session
is opened while one is still live, which is the camera's own rule expressed as a test.

When touching `GateService` semantics, add the scenario *before* changing the code —
the suite is the regression net this codebase went years without.

## Hardware acceptance checklist

Debug build unless stated. Run the full list after anything structural; the short loop for
small changes is phone-cold-start + one command + one DHU look.

**Phone**
- [ ] Cold start → QML renders; status line live ("Gate — connecting…" → real state)
- [ ] Open/Close/Stop move the gate; `lastError` appears on a refused/undeliverable
      command (airplane-mode test) and clears after ~20 s
- [ ] **The panel renders at all — check this first after any change to the frame path.**
      Frames cross as base64 `data:` URIs, which Qt Quick's `Image` should load through
      `QNetworkAccessManager`, but this app has never done it before. A blank panel with a
      healthy `cameraStatus` is that failure; the fallback is restoring the file-based store
      behind the same call site.
- [ ] Camera source `RTSP`: still + audio; **no one-frame blank** on refresh
- [ ] Camera source `HTTP`: the JPEG endpoint renders; go2rtc audio plays; audio ducks under
      a navigation prompt
- [ ] Kill the audio stream (stop it at go2rtc) while stills keep arriving → "Gate audio
      unavailable" appears, the picture keeps updating, the status does **not** go to ERROR
- [ ] Distance line shows when the geofence is on and located
- [ ] Background → foreground reconnects; rotation survives; gear → Settings
- [ ] Wiped install: first run lands in Settings, not a dead QML screen
- [ ] `adb shell run-as pl.bitforge.domofon ls cache` → **no `camera-frame-*.jpg`**, including
      after a process kill mid-session

**Settings**
- [ ] Status row is a live credential test (wrong password → named error while the screen
      is open; corrected → connects without leaving the screen)
- [ ] Topic-prefix edit takes effect without a relaunch
- [ ] Camera source dropdown hides the other path's URL rows *immediately*, before leaving the
      screen — and the right rows are showing when the screen is first opened
- [ ] Switching source with a camera view live (see the car item below) swaps the picture in
      place; the interval field does **not** restart the session
- [ ] **Edit the RTSP address, come back, and the picture is from the new address** — no
      force-stop. Repeat four or five times in one app session: the races this checks for
      (EGL re-setup, handler publication) need repetition to show. Same for the gate audio
      switch and the source dropdown, both of which reopen the session.
- [ ] Point the RTSP address at something dead: within ~15 s the panel shows "Camera
      unreachable" **over the stale picture**, and within ~30 s of pointing it back at a good
      address it recovers on its own. A stale picture that says nothing is the 2026-07-25 bug
      returning ([troubleshooting](troubleshooting.md)).
- [ ] Two-step location flow: disclosure dialog *before* each system prompt
- [ ] `adb shell run-as pl.bitforge.domofon` shows the stored password **and all three camera
      URLs** as ciphertext

**Qt process machinery**
- [ ] During a car session: swipe the app from recents, reopen → fresh process, no JNI
      abort, no restart loop; screen-off launch recovers via the watchdog

**Car (DHU via `scripts/dhu.sh`)**
- [ ] Pane renders with and without a camera (camera-less shows the gate-state picture, and
      the picture changes with state); snapshot refresh does **not** dim/blink the head unit
- [ ] **Put the DHU in *driving* mode and leave Domofon in front for several minutes**,
      across at least six status/distance changes: the screen keeps updating and is **not**
      closed with "this action is not allowed while driving". This restriction does not
      apply when parked, so a parked test proves nothing about it.
- [ ] Both camera sources render on the pane, and audio plays during a car session (this was
      already unproven before the second path existed — see the troubleshooting backlog)
- [ ] Change the camera source on the phone **while the car session is live** → the pane's
      picture swaps in place, the last frame stays up until the new source delivers, and the
      head unit does not dim
- [ ] Primary button flips Open⇄Close with state — **including mid-travel**: `opening` must
      read "Close gate", `closing` must read "Open gate". Stop always present; unconfigured
      message points at the phone; icons visible on a light host theme

**Notifications**
- [ ] Open the phone app, then the car app, then Settings → **no** heads-up from any of them
- [ ] Move the gate from the wall button → heads-up on phone and over the DHU; action sends
- [ ] Tap Open in the app → **no** pop-up for `opening`, pop-up for `opened`
- [ ] Locked phone + requireUnlock → toast refusal; body hidden on the lock screen
- [ ] Broker unreachable → failure notification after the action
- [ ] **Exactly one** notification per state change with phone *and* car open
- [ ] No "Gate watcher" channel remains in the app's notification settings
- [ ] Notification body reads `Changed at HH:MM` — no date, no offset, 24-hour
- [ ] **Domofon in front on the head unit** → move the gate from the wall button → **no**
      heads-up at all. Switch to Maps, move it again → heads-up appears. Same on the phone
      with the app in front vs. backgrounded
- [ ] Settings open (phone) is **not** a suppressing surface → a wall-button move still
      notifies
- [ ] Broker unreachable, Domofon in front on the head unit → the **failure** notification
      still appears (it answers a button you pressed)
- [ ] Leave a notification untapped → it is gone from the shade within 10 min (**30 s** for an
      arrival); opening the app clears the event and arrival ones immediately

**Geofence**
- [ ] `DebugGeofenceTrigger` → arrival pop-up with fresh state within ~7 s, and Settings →
      "Arrival trigger status" shows the delivery
- [ ] Fire it **twice, ~3 minutes apart** → **two** pop-ups. Two crossings are two arrivals;
      this line used to read "twice inside 10 minutes → exactly one pop-up" and that cooldown
      is precisely the defect D15 removed
- [ ] Fire it twice **within ~30 s** → one pop-up, status row says `the last pop-up is still on
      screen`. Not a rate limit on arrivals — a repost onto a live notification id would land
      silently in the shade instead of as a heads-up
- [ ] Arrival pop-up body reads `HH:MM · Tap to control`, and the time is when the fence was
      crossed — not up to 9.5 s later, when the notification was actually built
- [ ] Broker unreachable → "HH:MM · Gate unreachable — tap to retry", no action button
- [ ] Revoke background location → status reads `NOT registered — needs "Allow all the time"`;
      re-grant → `Registered <ts>`
- [ ] Grant **Approximate** rather than Precise → the app says precise location is needed
      instead of failing silently
- [ ] Reboot → fence re-registered (trigger works without opening the app); reinstall over
      the top → still registered (`MY_PACKAGE_REPLACED`)
- [ ] **The real drive**, in-app fence on: out past the radius and back with the head unit
      connected. A pop-up on the way in; afterwards, the status row says whether the native
      fence delivered. Native missing + pop-up present = Play Services is the broken half,
      which is the diagnosis the failing drive could not produce.
- [ ] **…then immediately out and back a second time, same session** → a **second** pop-up.
      This is the D15 defect verbatim; if the status row instead reads
      `the other trigger just announced this arrival`, the two triggers collapsed one crossing,
      which is the window doing its job — but on a real out-and-back it should not appear
- [ ] On the car screen during that drive: the distance line ticks down and carries its
      `next ≤Ns` cadence
- [ ] **Fire the debug trigger twice ~1 minute apart with Domofon backgrounded** → the second
      one draws a *fresh* heads-up over the DHU, not just a shade entry. This is the "second
      pop-up never appeared" defect; a heads-up on the first and silence on the second means
      the notification id was still occupied
- [ ] **Get into the car at home** with the app freshly started → **no** "Approaching home"
      left over from the last drive. If one appears anyway, read `Last pop-up` in the status
      row: hours old means the shade never cleared, fresh means a trigger really fired
- [ ] On the real drive, **on the way out**: the status row flips to `Last seen: outside the
      fence` — the `GEOFENCE_TRANSITION_EXIT` arriving. Diagnostic only; it must **never** be
      a precondition for the pop-up on the way in
- [ ] **The phone-was-off case**: switch the phone off at home, leave, switch it on once
      already outside the radius, drive home with it in a pocket → the pop-up still arrives.
      Nothing witnessed the departure, and the inward crossing alone must be enough
- [ ] Arrive with Domofon in front on the head unit → no pop-up, status row says
      `Last event ignored: a Domofon screen was in front`, and the *next* arrival is still
      allowed (a suppressed pop-up must not consume the de-duplication window)
- [ ] Android Auto → Settings → Notifications: Domofon is permitted. Not an app-side setting;
      a disabled toggle here looks exactly like a broken pop-up

**Release build** (mandatory after class moves/renames — R8 + reflective Qt/Netty)
- [ ] `scripts/build-release.sh --dry-run` artifact side-loaded: the whole phone list above
- [ ] The DHU **refuses** the release build (host validator live) while debug works

## Related pages

[build-and-release.md](build-and-release.md) · [modules/gate.md](modules/gate.md) ·
[troubleshooting.md](troubleshooting.md)
