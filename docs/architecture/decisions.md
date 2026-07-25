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

## D3 — The camera is one RTSP URL and nothing else

**Context (Artur's rule, 2026-07-24):** if it needs a backend or knows a camera brand, it
is a private tool rather than a publishable app. Snapshot endpoints are vendor-specific
and often Digest-only. **Decision:** RTSP is the one required camera setting; stills are
pulled out of the stream. An HTTP JPEG URL exists as an *optional* override, never a
requirement. **Status:** active.

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
(foreground UI, settings screen, car session, arrival pop-up, one-shot command).
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

## D12 — Phone shows three buttons; car shows primary + Stop

**Context:** a car Pane takes at most two actions
(`ACTIONS_CONSTRAINTS_BODY_WITH_PRIMARY_ACTION`); the arrival notification gets exactly
one because a driver reaching for a heads-up needs one target. **Decision (post-b6f18d5):**
the divergence is intentional, not drift — phone: Open/Close/Stop; car:
`GatePolicy.primaryAction` + Stop; notification: primary only. **Status:** active.

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

(`res/xml/network_security_config.xml`, 2026-07-24.) For the *optional* HTTP snapshot
override only. The block cannot be lifted per-host because **every address in this app is
typed by the user at runtime** — the whole point of D6 — so it is lifted globally.

*What is actually exposed:* nothing, on a default install. The camera's own path is RTSP
(a raw socket this policy never governed) and the snapshot field is empty unless the user
fills it. Gate **commands** never travel HTTP — they are MQTT, with its own TLS switch.

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
- **HTTP snapshot as the primary camera source.** Worked, but made the app
  deployment-shaped (vendor paths, Digest auth). Demoted to the optional override by D3.
- **`Build.MODEL`-derived MQTT client id.** Collides for every user on the same phone
  model; two strangers would evict each other from the broker in a loop forever. Replaced
  by a random per-install id.

## Related pages

[overview.md](overview.md) · [security.md](security.md) ·
[mqtt-contract.md](mqtt-contract.md)
