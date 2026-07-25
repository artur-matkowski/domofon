# scripts

Helper scripts for building and testing Domofon. All are location-independent — they resolve
their own directory, so they run the same in-tree or from a copy of the repo.

| Script | What it does | Output |
|---|---|---|
| `build-debug.sh [--install]` | Build a debug APK (git-derived version). `--install` also pushes it to a connected phone. | `dist/domofon-<version>-debug.apk` |
| `build-release.sh [--dry-run] [--force] [--no-tag]` | Build a signed, upload-ready Play bundle: computes the next version from Conventional Commits, builds `.aab` + a testable release `.apk`, scans for leaked secrets, tags `v<version>`, prints the Play checklist. | `dist/domofon-<version>-release.{aab,apk}` |
| `dhu.sh` | Launch the Android Auto Desktop Head Unit against the connected phone. | — |
| `find-snapshot-url.sh` | Probe a camera for a still-image (snapshot) URL. | — |
| `lib/memguard.sh` | Sourced, not run. Memory pre-flight + cgroup cap for both build scripts. | — |

## Builds cannot freeze the machine any more

A release build locked this desktop solid on 2026-07-24 (nine minutes, hard reboot). Both
build scripts now source `lib/memguard.sh`, which:

- **refuses to start** below ~10 GB free (release) or ~6 GB (debug), after first trying
  `./gradlew --stop`, and prints the biggest processes so the refusal tells you what to
  close. In `build-release.sh` this runs *before* the version math, so a refusal can never
  leave a stray tag;
- **caps the build** inside a transient `systemd-run --user --scope` (`MemoryHigh`,
  `MemoryMax`, `MemorySwapMax`), so a runaway build is OOM-killed in its own cgroup while
  the session keeps running, and reports the cgroup's peak usage when it finishes;
- passes **`--no-daemon`**, because a Gradle daemon that survives a scope keeps running
  inside the *old* cgroup and would make the next build's cap meaningless;
- **reaps whatever the build leaves running** in its own cgroup. `--no-daemon` only governs
  the outer build, and the Qt Gradle Plugin runs a *nested* Gradle build (its own wrapper,
  9.3.1 against the outer 9.4.1, its own `-Xmx3200m` and `org.gradle.parallel=true`) whose
  daemon would otherwise squat for Gradle's three-hour idle timeout.

Overrides: `DOMOFON_SKIP_MEM_CHECK=1`, `DOMOFON_MEM_REQUIRED=<MB>`, `DOMOFON_MEM_MAX=<size>`,
`DOMOFON_MEM_HIGH`, `DOMOFON_MEM_SWAP`, `DOMOFON_NO_CAP=1`. Full write-up: `docs/10` →
*Build machine / host resources*.

## Versioning

Version is derived from git, never hand-edited (see `app/app/build.gradle.kts` and
`docs/11 §4`):

- **versionCode** = commit count (`git rev-list --count HEAD`) — monotonic, which is what
  Play requires of every upload.
- **versionName** = `git describe` for debug/dev builds; the release script overrides it with
  the clean semver it computes from Conventional Commits since the last `v*` tag
  (`feat!`/`BREAKING CHANGE` → major, `feat` → minor, otherwise patch).

## Releasing (Play)

`build-release.sh` is **run in-tree, by the tree owner** — it needs the gitignored signing
key (`app/keystore.properties` + the upload `.jks`) and the local
`scripts/secret-sentinels.txt` (copy from the `.example`). The coding agent never runs it:
it builds as a different user and must not touch the signing secrets — it builds **debug**
only, in a scratchpad copy of the repo (see `docs/10` and the project memory). Always test
the release APK on the phone before uploading the bundle — R8 resolves classes reflectively,
so a working debug build proves nothing.
