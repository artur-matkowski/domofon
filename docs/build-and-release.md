# Build and release

*[Wiki home](README.md) › build-and-release*

## Environment (as verified on the dev machine)

Debian 13 · Android Studio + SDK (compileSdk 36) · **NDK r27c = 27.2.12479018, pinned** —
the exact NDK Qt 6.11 is built against; the RC NDK sdkmanager also offers shows up as an
`UnsatisfiedLinkError` at app start, not a build error · **Qt 6.11.1** for Android
(arm64_v8a) · Qt Gradle Plugin **1.4** · AGP **9.0.0** (built-in Kotlin — applying
`org.jetbrains.kotlin.android` is a hard error; no KSP/kapt, no Compose) · Gradle 9.4.1.
Phone: SM-G990B2 (arm64-v8a, minSdk 28 is Qt's floor).

Per-checkout, gitignored: `app/local.properties` (`sdk.dir`, `qtPath`, optional
`qtNinjaPath`) from its `.example`; `app/domofon-debug.keystore` (pinned debug key so
every machine/user signs identically — without it, cross-identity installs fail
`INSTALL_FAILED_UPDATE_INCOMPATIBLE` and the forced uninstall wipes the
Keystore-encrypted settings).

## Everyday builds

```bash
scripts/build-debug.sh [--install]     # debug APK → dist/; the script agents run
scripts/build-release.sh               # Artur only: signed .aab+.apk, semver, tag
scripts/dhu.sh                         # Desktop Head Unit, Passat-B8 profile
```

Agents build **only in a scratchpad copy of the repo, never in-tree** (the tree is
`artur`-owned; the agent runs as `bitforge`). Copy the repo plus the two gitignored files
above.

**memguard** (`scripts/lib/memguard.sh`, sourced by both build scripts): a free-RAM
pre-flight that can refuse to start, plus a `systemd-run` cgroup cap around Gradle with
cgroup reaping afterwards. It exists because a release build froze the desktop for nine
minutes on 2026-07-24; the reaping specifically catches the **nested Qt Gradle daemon**
that `--no-daemon` does not cover. Overrides: `DOMOFON_SKIP_MEM_CHECK=1`,
`DOMOFON_MEM_REQUIRED/MAX/HIGH/SWAP`, `DOMOFON_NO_CAP`. Forensics:
[troubleshooting.md](troubleshooting.md) → *Build machine / host resources*.

## Qt Gradle Plugin traps (each cost a session)

1. **`QtBuildTask` runs a nested Gradle build** (its own wrapper, its own JVM args) that
   CMake/ninja-builds `qtquickview/` → `libdomofon_arm64-v8a.so` + the generated wrapper
   classes → `qt_generated/aars/domofon.aar`, which AGP consumes.
2. **The plugin wires the AAR into *debug* compiles only.** `app/app/build.gradle.kts`
   manually `dependsOn("QtBuildTask")` for `compileReleaseKotlin`/`JavaWithJavac` —
   without it a clean release build fails with unresolved `org.qtproject.*`.
3. `qtProjectPath` in `app/gradle.properties` is resolved from the **root** project dir.
4. The generated wrapper package derives from the CMake target + module URI — both frozen
   ([ui-qml-contract](modules/ui-qml-contract.md)).

## R8 / release (all load-bearing; see `app/app/proguard-rules.pro`)

- **Resource shrinking is OFF and must stay off** until a `res/raw/keep.xml` exists:
  `QtLoader` resolves its bootstrap arrays *by name* via `Resources.getIdentifier()`; the
  shrinker strips them and the app dies at launch with `UnsatisfiedLinkError`. Keep rules
  do not help — only keep.xml does.
- **HiveMQ keep rules use the shaded prefix**
  (`com.hivemq.client.internal.shaded.io.netty.**`). HiveMQ's own docs give `io.netty.**`,
  which is a silent no-op against the `-shaded` artifact this project uses.
- `androidx.car.app` needs no rules from us (its AARs ship consumer rules for the
  binder-by-name serialization); a blanket keep would only defeat shrinking.
- **Always re-test release on device** — Qt and shaded Netty both resolve classes
  reflectively; debug working proves nothing. This applies doubly after class
  moves/renames.

### Manifest hygiene

The Qt AAR declares `WRITE_EXTERNAL_STORAGE`, and the merger *implies* READ from it; both
are removed with `tools:node="remove"` (removing WRITE alone leaves READ). Re-check after
every Qt upgrade; the expected final permission set is: `INTERNET`, `POST_NOTIFICATIONS`,
`ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `RECEIVE_BOOT_COMPLETED`,
`ACCESS_NETWORK_STATE`, `HIDE_OVERLAY_WINDOWS`. Nothing else.

## Release runbook

One-time: `cp app/keystore.properties.example app/keystore.properties`, generate
`upload.jks` (`keytool -genkeypair … -alias domofon-upload`). **Back up `upload.jks` off
the machine** — with Play App Signing the upload key is the unrecoverable one.

Every release: `scripts/build-release.sh` — memory pre-flight, semver from Conventional
Commits since the last `v*` tag (`feat!`/BREAKING → major, `feat` → minor, else patch),
build, **no-secrets scan** of the bundle against `scripts/secret-sentinels.txt` (aborts on
any hit), artifacts to `dist/`, local `v<semver>` tag (never pushed automatically).

## Play Console runbook (ordered by what blocks what)

1. **Account type**: personal accounts (post-Nov 2023) must run a closed test with **12
   testers for 14 continuous days** before production access — the critical path, start it
   first.
2. **Privacy policy**: active URL (GitHub Pages works), linked in listing *and* app.
3. **Data safety**: location and credentials never leave the device — declare *not
   collected, not shared* (Google's "collect" means transmitted off-device).
4. **App access**: a reviewer has neither the broker nor the VPN, so the app looks broken
   — provide a reachable test broker + exact steps. A demo mode would remove this failure
   class entirely (highest-value backlog item).
5. **Background location declaration**: form + ≤30 s video showing disclosure →
   prompt → feature; frame it around approaching the gate while driving; the geofencing
   API requiring background location is the counter to "why not foreground-only".
6. **Android Auto form factor**: separate manual review (IOT, Tier 2); IT-1 forbids
   setup from the car — [ui-car](modules/ui-car.md) complies deliberately.
7. Ads (none) · content rating · target audience (adults only — children + background
   location fails Families policy).

**If a foreground service is ever added**: not `foregroundServiceType="location"` (Play
dropped geofencing as a use case, Aug 2026); the honest type is `connectedDevice`, which
needs one of `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`/`NFC`/`TRANSMIT_IR` plus its own
declaration + video.

## Related pages

[testing.md](testing.md) · [troubleshooting.md](troubleshooting.md) ·
[modules/ui-qml-contract.md](modules/ui-qml-contract.md) ·
[architecture/security.md](architecture/security.md)
