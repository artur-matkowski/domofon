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
| `domain/camera/JpegDataUriTest` | the QML bridge's frame encoding: prefix, round-trip, uniqueness, no line breaks |
| `domain/config/DomofonConfigParserTest` | trims, clamps, prefix normalisation, tls-port default, wire equality, toString redaction, **camera source + `CameraFeed` resolution** |
| `domain/config/CameraSettingsRowsTest` | which camera rows each source shows, and that `ALL` covers every one |
| `data/mqtt/ConnectionErrorMessagesTest` | cause-chain walk (bounded), name-matched shaded timeouts |
| `data/mqtt/GateServiceTest` | lease lifecycle, stale-handle silence, wire rebuild, watchdog, backoff, teardown semantics, command paths, awaitFreshState settle |
| `ui/shared/GateViewModelTest` | derivation + eager initial value, independent picture/audio health |

`data/camera` still has no direct coverage — nothing in it is JVM-testable. What *is* now
tested is the part that decides its behaviour: which feed a configuration resolves to, and
therefore which source the grabber opens and when it reopens.

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
- [ ] Two-step location flow: disclosure dialog *before* each system prompt
- [ ] `adb shell run-as pl.bitforge.domofon` shows the stored password **and all three camera
      URLs** as ciphertext

**Qt process machinery**
- [ ] During a car session: swipe the app from recents, reopen → fresh process, no JNI
      abort, no restart loop; screen-off launch recovers via the watchdog

**Car (DHU via `scripts/dhu.sh`)**
- [ ] Grid (no camera) and pane (camera) templates render; snapshot refresh does **not**
      dim/blink the head unit
- [ ] Both camera sources render on the pane, and audio plays during a car session (this was
      already unproven before the second path existed — see the troubleshooting backlog)
- [ ] Change the camera source on the phone **while the car session is live** → the pane's
      picture swaps in place, the last frame stays up until the new source delivers, and the
      head unit does not dim
- [ ] Primary button flips Open⇄Close with state; Stop always present; unconfigured
      message points at the phone; icons visible on a light host theme

**Notifications**
- [ ] State change → heads-up on phone and over the DHU; action button sends
- [ ] Locked phone + requireUnlock → toast refusal; body hidden on the lock screen
- [ ] Broker unreachable → failure notification after the action
- [ ] **Exactly one** notification per state change with phone *and* car open
- [ ] No "Gate watcher" channel remains in the app's notification settings

**Geofence**
- [ ] `DebugGeofenceTrigger` → arrival pop-up with fresh state within ~7 s
- [ ] Broker unreachable → "Gate unreachable — tap to retry", no action button
- [ ] Reboot → fence re-registered (trigger works without opening the app)

**Release build** (mandatory after class moves/renames — R8 + reflective Qt/Netty)
- [ ] `scripts/build-release.sh --dry-run` artifact side-loaded: the whole phone list above
- [ ] The DHU **refuses** the release build (host validator live) while debug works

## Related pages

[build-and-release.md](build-and-release.md) · [modules/gate.md](modules/gate.md) ·
[troubleshooting.md](troubleshooting.md)
