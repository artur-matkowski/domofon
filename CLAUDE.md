# Domofon — project context

Entry-phone Android app for Artur's home gate: camera stills + gate audio (RTSP), gate
control and live state (MQTT), an Android Auto screen, and a geofence arrival pop-up —
over OpenVPN. **Claude writes the code. Artur reviews it and is the only one who can test
on real hardware** — the phone, the DHU, the Passat. Nothing is "done" until it has run on
a device; say plainly what is untested.

**The documentation lives in the wiki: start at [docs/README.md](docs/README.md).** It has
per-topic reading paths; read the relevant page before changing the code it describes.
Settled decisions and accepted risks are in
[docs/architecture/decisions.md](docs/architecture/decisions.md) — don't re-litigate them
unilaterally. When a session solves a problem, append Symptom → Cause → Fix to
[docs/troubleshooting.md](docs/troubleshooting.md).

## Hard constraints (one screen)

- **App speaks MQTT only** — never Postgres/REST. The backend is `hc12-web-service`
  (external repo, on `rpi-d`); the wire contract is
  [docs/architecture/mqtt-contract.md](docs/architecture/mqtt-contract.md). Commands are
  **never retained**.
- **Android Auto renders templates only** — QML cannot appear on the car screen; "pop-up
  while driving" = heads-up notification with `CarAppExtender`.
- **Nothing deployment-specific is compiled in** — all config is on-device
  (`ConfigStore`); the repo is public (`artur-matkowski/domofon`) and secrets are never
  committed (`local.properties`, keystores, `secret-sentinels.txt`).
- **Decoded video frames are never read on the CPU** — `Image.getPlanes()` is a fatal
  native abort here; frames go through the offscreen EGL reader. Permanent.
- **`ALLOW_ALL_HOSTS_VALIDATOR` is debug-only, permanently** — it is the entire security
  boundary of the exported car service.
- **The camera is one RTSP URL** — the HTTP snapshot URL is an optional override, never a
  requirement.
- No 24/7 connection: MQTT exists only while a `ConnectionLease` is held.
- Build stack is fragile: AGP 9 built-in Kotlin (no KSP/kapt/Compose), Qt Gradle Plugin
  1.4 nested build, pinned NDK r27c, CMake target `domofon` + URI `DomofonQml` frozen.
  Details: [docs/build-and-release.md](docs/build-and-release.md).

## Working rules

- **Building:** Claude builds with `scripts/build-debug.sh`, only in a scratchpad copy of
  the repo — never in-tree (the tree is `artur`-owned). `scripts/build-release.sh` is
  Artur's. Version comes from git — never hand-edit `versionCode`/`versionName`.
- **Tests:** `./gradlew --no-daemon :app:testDebugUnitTest` in the copy; write the
  regression test before changing `GateService` semantics. See
  [docs/testing.md](docs/testing.md) (includes the hardware checklist).
- **Commits:** Conventional Commits; the type is load-bearing (the release script derives
  semver from it). End Claude-authored commits with the `Co-Authored-By: Claude` trailer.
- Release builds must be re-tested on device — R8 plus reflective Qt/Netty means debug
  proves nothing.
