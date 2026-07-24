# Domofon — project context

Entry-phone Android app for Artur's home gate. **Claude writes the code. Artur reviews it
and is the only one who can test on real hardware** — the phone, the DHU, the Passat.
Nothing is "done" until it has run on a device; say plainly what is untested rather than
implying it works. When a session solves a problem, append it to
`docs/10-troubleshooting.md` (Symptom → Cause → Fix) and update the progress tracker
checkboxes in `docs/README.md`.

> **Changed 2026-07-24.** This replaces the original "Artur writes the code, Claude
> guides" model, which had already been suspended by three separate one-off exceptions
> (the repo bootstrap, the Play-publication security pass, the post-real-car fixes) in
> two weeks. Those exception notes are gone; there is no longer a rule for them to carve
> holes in. The `docs/` chapters are now the design record and the acceptance tests —
> not a type-it-yourself tutorial.

## Where things stand

Check `docs/README.md` → *Progress tracker* for the current milestone. Version facts
re-verified on-machine 2026-07-10: **Qt 6.11.1** (not 6.11.0), NDK **r27c =
27.2.12479018**, `androidx.car.app` **1.7.0** is still the newest stable, Qt Gradle
Plugin **1.4** (Maven Central), AGP **9.0.0**, Gradle **9.4.1**.

## Architecture in one paragraph

Existing home infra (RTSP camera w/ audio, REST gate API, Postgres gate state, MQTT
broker, OpenVPN server) stays untouched except for new broker credentials. A small
Python **bridge** (`bridge/`, ch. 02) is the only component touching Postgres (initial
pull + 1 s polling) and REST (executes commands); it translates everything to MQTT:
`domofon/gate/state` (retained), `domofon/gate/command`, `domofon/bridge/status` (LWT).
The **app** (`app/`, ch. 03+) is one APK: Kotlin host (MQTT via HiveMQ client in a
single `GateRepository`, notifications, Android Auto `CarAppService` with IOT category,
Play Services geofencing) + **QML embedded via `QtQuickView`** (Qt Quick for Android,
Qt Gradle plugin) for the phone UI incl. RTSP playback (QtMultimedia). Phone reaches
home via **OpenVPN for Android** (always-on, per-app).

## Key constraints (don't re-litigate)

- Android Auto renders only Car App Library templates — QML can't appear on the car
  screen; "pop-up while driving" = high-importance notification with `CarAppExtender`.
- App speaks **MQTT only** — never Postgres or REST directly (user decision).
- No 24/7 connection: MQTT connects on app-foreground, AA-session, or geofence-entry.
  See ch. 06 — but note the foreground service sketched there must **not** use
  `foregroundServiceType="location"`: Play dropped geofencing as an approved location-FGS
  use case in August 2026. Use `connectedDevice` (it is keeping a network connection to an
  external device alive, which is what that type is for).
- **Nothing deployment-specific may be compiled in.** Broker, topics, home coordinates and
  both camera URLs live in `ConfigStore` (ch. 11). A published APK is a public artifact;
  `strings` on it is not a difficult attack. `BuildConfig` carries no app configuration.
- **The camera is one RTSP URL and nothing else.** No snapshot endpoint, no restreamer, no
  vendor path required — those are per-camera and often Digest-only, and an app that needs
  them only works for people who know their own firmware. Stills are pulled out of the
  stream (`RtspFrameSource`); an HTTP JPEG URL exists as an *optional* override, never a
  requirement. Artur's rule, 2026-07-24: if it needs a backend or knows a camera brand, it
  is a private tool rather than a publishable app.
- **Decoded video frames are never read on the CPU.** `ImageReader` + `Image.getPlanes()`
  is a native JNI abort on a GPU-only MediaCodec buffer — uncatchable, and fatal on the
  test phone (Exynos). Frames come back through an offscreen EGL context and `glReadPixels`
  (`OffscreenTextureReader`), which is safe on those buffers and scales on the GPU for free.
  See ch. 04 §1 and the `nativeCreatePlanes` entry in ch. 10.
- `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` is debug-only, permanently. `CarAppService` is
  exported and unguarded by permission, so the validator is the entire boundary between an
  arbitrary installed app and the gate opening.

## Decisions confirmed by Artur (2026-07-10)

- Bridge watches Postgres by **polling**, 1 s. Zero changes to the gate database.
  LISTEN/NOTIFY stays documented as an optional upgrade in ch. 02.
- Guide language **English**.
- Repo is public on GitHub: `artur-matkowski/domofon`. Nothing secret may be committed —
  `bridge/bridge.env` and `app/local.properties` are gitignored and have `.example` twins.

## Conventions

- Dev machine: Debian 13. Phone: arm64-v8a. `minSdk 28`.
- Package root: `pl.bitforge.domofon`; only `GateRepository` may own MQTT, and only
  `ConfigStore` may own settings.
- Secrets (broker/RTSP credentials, `bridge.env`, `keystore.properties`, `*.jks`) never
  committed.
- Release builds must be re-tested on device. Qt and the shaded Netty both resolve classes
  reflectively, so R8 working in debug proves nothing. Resource shrinking stays **off**
  until `res/raw/keep.xml` exists — see ch. 11 §4.
- **Building.** Claude builds with `scripts/build-debug.sh`, and only in a scratchpad copy
  of the repo — never in-tree (the tree is `artur`-owned; see the build-ownership memory).
  `scripts/build-release.sh` is Artur's: it needs the gitignored signing key and produces
  the real Play bundle. Version is derived from git — never hand-edit `versionCode`/`Name`.
- **Commit convention: Conventional Commits.** `feat:`, `fix:`, `docs:`, `chore:`,
  `refactor:`, `test:`, `build:` (optional `(scope)`); `feat!:` or a `BREAKING CHANGE:`
  footer marks a breaking change. The release script derives the semver from these, so the
  type is load-bearing, not cosmetic. Still end commit messages with the
  `Co-Authored-By: Claude` trailer. Commits Artur authors himself are exempt.
