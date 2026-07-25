#!/usr/bin/env bash
# Build a signed, upload-ready Play bundle (.aab) of the Domofon app.
#
# Run this in-tree, on the machine that holds the (gitignored) signing key — it needs
# app/keystore.properties + the upload .jks. The agent must NOT run it: it builds as a
# different user than the tree owner, and it must not touch the real signing secrets.
#
# What it does, in order:
#   1. computes the next version from Conventional Commits since the last v* tag
#      (feat! / BREAKING CHANGE -> major, feat -> minor, otherwise patch);
#   2. builds the .aab (for Play) and a release-signed .apk (for the on-device release test
#      that R8 makes mandatory — debug proves nothing about a minified build);
#   3. scans the bundle for known secrets (scripts/secret-sentinels.txt, gitignored);
#   4. copies both to dist/domofon-<version>-release.{aab,apk};
#   5. tags v<version> locally (never pushed);
#   6. prints the Play Console checklist.
#
# versionCode is the git commit count (see app/app/build.gradle.kts) — monotonic, which is
# what Play requires. Version is derived from git alone; nothing here is hand-edited.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DIST_DIR="$ROOT_DIR/dist"

# Memory pre-flight + cgroup cap. A release build froze this machine on 2026-07-24; see
# scripts/lib/memguard.sh and docs/10 "Build machine / host resources".
# shellcheck source=lib/memguard.sh
. "$SCRIPT_DIR/lib/memguard.sh"
MEM_REQUIRED_MB=10240      # refuse to start below this
MEM_HIGH=8G                # reclaim pressure starts here
MEM_MAX=12G                # hard ceiling: exceed it and the build dies, not the desktop
MEM_SWAP=2G                # of a 3.7 GB swap partition — leave the desktop some

DRY_RUN=0 FORCE=0 NO_TAG=0
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --force)   FORCE=1 ;;
        --no-tag)  NO_TAG=1 ;;
        -h|--help)
            echo "usage: $(basename "$0") [--dry-run] [--force] [--no-tag]"
            echo "  --dry-run   compute the version and print the plan; build nothing"
            echo "  --force     release even if HEAD is already tagged / no new commits"
            echo "  --no-tag    do not create the v<version> git tag"
            exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

git() { command git -C "$ROOT_DIR" "$@"; }

# --- 0. Memory pre-flight --------------------------------------------------------------
# Before the version math, so a refusal costs nothing and can never leave a tag behind.
# --dry-run builds nothing, so it needs no headroom.
if [ "$DRY_RUN" -eq 0 ]; then
    preflight_memory "$MEM_REQUIRED_MB" release "$APP_DIR"
fi

# --- 1. Compute the next version from Conventional Commits -----------------------------
last="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
if [ -n "$last" ]; then
    range="${last}..HEAD"          # commits since the last release
else
    range="HEAD"                   # no tag yet: the whole history
    last="v0.0.0"                  # baseline for the version math only
fi

n="$(git rev-list --count $range 2>/dev/null || echo 0)"
if [ "$n" -eq 0 ] && [ "$FORCE" -eq 0 ]; then
    echo "No commits since $last — nothing new to release. Use --force to rebuild it." >&2
    exit 1
fi

msgs="$(git log --format='%B' $range 2>/dev/null || true)"
bump=patch
if grep -qE '(^|[[:space:]])BREAKING CHANGE' <<<"$msgs" \
   || grep -qE '^[a-z]+(\([^)]*\))?!:' <<<"$msgs"; then
    bump=major
elif grep -qE '^feat(\([^)]*\))?:' <<<"$msgs"; then
    bump=minor
fi

ver="${last#v}"
IFS='.' read -r MA MI PA <<<"$ver"
MA="${MA:-0}"; MI="${MI:-0}"; PA="${PA:-0}"
PA="${PA%%[!0-9]*}"   # drop any pre-release suffix (e.g. 0.2.0-rc1)
case "$bump" in
    major) MA=$((MA + 1)); MI=0; PA=0 ;;
    minor) MI=$((MI + 1)); PA=0 ;;
    patch) PA=$((PA + 1)) ;;
esac
NEXT="$MA.$MI.$PA"
CODE="$(git rev-list --count HEAD)"

echo "last tag:     $last"
echo "commits since: $n  ->  $bump bump"
echo "next version:  $NEXT  (versionCode $CODE)"

if [ "$DRY_RUN" -eq 1 ]; then
    echo
    echo "[dry-run] would build dist/domofon-$NEXT-release.{aab,apk}"
    [ "$NO_TAG" -eq 0 ] && echo "[dry-run] would tag v$NEXT (local)"
    exit 0
fi

# --- 2. Guard the signing material -----------------------------------------------------
KS_PROPS="$APP_DIR/keystore.properties"
[ -f "$KS_PROPS" ] || {
    echo "Missing $KS_PROPS — copy keystore.properties.example and fill it in." >&2
    exit 1
}
storeFile="$(sed -n 's/^storeFile=//p' "$KS_PROPS" | head -1)"
case "$storeFile" in /*) jks="$storeFile" ;; *) jks="$APP_DIR/$storeFile" ;; esac
[ -f "$jks" ] || { echo "Signing keystore not found: $jks" >&2; exit 1; }

# --- 3. Build --------------------------------------------------------------------------
# --no-daemon on purpose. A release is infrequent, so daemon reuse buys nothing, and it
# guarantees every JVM the build spawns (Gradle, Kotlin, R8 workers) lives inside the
# scope and dies with it. That removes the exact failure of 2026-07-24, where a 3.1 GiB
# worker daemon outlived its build by nearly three hours.
( cd "$APP_DIR" && run_capped "$MEM_HIGH" "$MEM_MAX" "$MEM_SWAP" \
    ./gradlew --no-daemon :app:bundleRelease :app:assembleRelease -PversionName="$NEXT" )

AAB="$APP_DIR/app/build/outputs/bundle/release/app-release.aab"
APK="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
[ -f "$AAB" ] || { echo "expected bundle not found: $AAB" >&2; exit 1; }

# --- 4. No-secrets scan ----------------------------------------------------------------
SENT="$SCRIPT_DIR/secret-sentinels.txt"
if [ -f "$SENT" ]; then
    patterns="$(grep -vE '^\s*(#|$)' "$SENT" || true)"
    if [ -n "$patterns" ]; then
        hits="$(unzip -p "$AAB" | strings -a | grep -aiF -f <(echo "$patterns") || true)"
        if [ -n "$hits" ]; then
            echo "SECRET LEAK: the bundle contains a sentinel string. Aborting." >&2
            echo "$hits" | sort -u | sed 's/^/  /' >&2
            exit 1
        fi
        echo "no-secrets scan: clean (${SENT##*/})"
    fi
else
    echo "note: no ${SENT##*/} — skipping the no-secrets scan (see ${SENT##*/}.example)."
fi

# --- 5. Publish artifacts to dist/ -----------------------------------------------------
mkdir -p "$DIST_DIR"
cp -f "$AAB" "$DIST_DIR/domofon-$NEXT-release.aab"
cp -f "$APK" "$DIST_DIR/domofon-$NEXT-release.apk"

# --- 6. Tag ----------------------------------------------------------------------------
if [ "$NO_TAG" -eq 0 ]; then
    if git rev-parse "v$NEXT" >/dev/null 2>&1; then
        echo "tag v$NEXT already exists — not re-tagging."
    else
        git tag -a "v$NEXT" -m "Release $NEXT"
        echo "tagged v$NEXT (local only — not pushed)."
    fi
fi

# --- 7. Checklist ----------------------------------------------------------------------
cat <<EOF

Built:
  dist/domofon-$NEXT-release.aab   -> upload to Play
  dist/domofon-$NEXT-release.apk   -> install and TEST ON THE PHONE first (R8 build)

Play Console (see docs/11 §5):
  * Test the release APK on the device before uploading — R8 resolves classes reflectively.
  * Upload the .aab under the track you want.
  * First publish only: closed test, 12 testers, 14 continuous days before production.
  * App access: give the reviewer a reachable test broker + steps, or they see a dead app.
  * Data Safety: declare "not collected, not shared" (config never leaves the device).
  * Android Auto gets a separate manual review (IOT, Tier 2).
EOF
