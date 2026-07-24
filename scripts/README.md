# scripts

Helper scripts for building and testing Domofon. All are location-independent — they resolve
their own directory, so they run the same in-tree or from a copy of the repo.

| Script | What it does | Output |
|---|---|---|
| `build-debug.sh [--install]` | Build a debug APK (git-derived version). `--install` also pushes it to a connected phone. | `dist/domofon-<version>-debug.apk` |
| `build-release.sh [--dry-run] [--force] [--no-tag]` | Build a signed, upload-ready Play bundle: computes the next version from Conventional Commits, builds `.aab` + a testable release `.apk`, scans for leaked secrets, tags `v<version>`, prints the Play checklist. | `dist/domofon-<version>-release.{aab,apk}` |
| `dhu.sh` | Launch the Android Auto Desktop Head Unit against the connected phone. | — |
| `find-snapshot-url.sh` | Probe a camera for a still-image (snapshot) URL. | — |

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
