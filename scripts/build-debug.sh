#!/usr/bin/env bash
# Build a debug APK of the Domofon app.
#
# This is the everyday build: run it while working on a feature or chasing a bug. The
# version it stamps comes from git (versionCode = commit count, versionName = `git
# describe`) — see app/app/build.gradle.kts — so nothing here is hand-edited.
#
# Location-independent on purpose: it resolves its own directory, so it runs the same
# in-tree or inside a scratchpad copy of the repo (the agent builds in a copy — the working
# tree is owned by a different user; see docs/10 and the project memory).
#
# Debug output is signed with the shared, gitignored app/domofon-debug.keystore, so every
# machine produces an install-compatible APK and re-installs never trip
# INSTALL_FAILED_UPDATE_INCOMPATIBLE.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
DIST_DIR="$ROOT_DIR/dist"

INSTALL=0
for arg in "$@"; do
    case "$arg" in
        --install) INSTALL=1 ;;
        -h|--help)
            echo "usage: $(basename "$0") [--install]"
            echo "  --install   also install onto a connected device (adb)"
            exit 0 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

cd "$APP_DIR"

if [ "$INSTALL" -eq 1 ]; then
    ./gradlew :app:installDebug
else
    ./gradlew :app:assembleDebug
fi

APK="$APP_DIR/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "expected APK not found: $APK" >&2; exit 1; }

# Name the copy after the version baked into the build, read back from the APK itself.
AAPT="$(ls -1 "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true)"
VNAME="$(git -C "$ROOT_DIR" describe --tags --always --dirty 2>/dev/null || echo dev)"
if [ -n "$AAPT" ]; then
    v="$("$AAPT" dump badging "$APK" 2>/dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    [ -n "$v" ] && VNAME="$v"
fi

mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/domofon-$VNAME-debug.apk"
cp -f "$APK" "$OUT"

echo
echo "debug APK: $OUT"
[ "$INSTALL" -eq 1 ] && echo "installed on the connected device."
