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

# Memory pre-flight + cgroup cap — see scripts/lib/memguard.sh. This is the script the
# agent runs, and it runs it in a /tmp scratchpad copy, which on this machine is a tmpfs:
# every byte of that build tree is unreclaimable RAM until it is deleted or the box is
# rebooted. So this is the build that most needs a ceiling, not the rarer release one.
# shellcheck source=lib/memguard.sh
. "$SCRIPT_DIR/lib/memguard.sh"
MEM_REQUIRED_MB=6144
MEM_HIGH=6G
MEM_MAX=9G
MEM_SWAP=1G

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

preflight_memory "$MEM_REQUIRED_MB" debug "$APP_DIR"

# --no-daemon, and the reason is measured rather than assumed. A Gradle daemon forked
# inside a transient scope does NOT die with it — it keeps running and stays in that
# scope's cgroup, which therefore also stays alive. The next build would then open a fresh
# scope, connect to the surviving daemon, and do all its actual work in the *old* cgroup:
# the new cap would be governing an idle client. That is both confusing and the squatting
# daemon of 2026-07-24 coming straight back. One scope per build, nothing outliving it.
# Cost: a cold Gradle start (~10-20 s) on every debug build. Worth it.
if [ "$INSTALL" -eq 1 ]; then
    run_capped "$MEM_HIGH" "$MEM_MAX" "$MEM_SWAP" ./gradlew --no-daemon :app:installDebug
else
    run_capped "$MEM_HIGH" "$MEM_MAX" "$MEM_SWAP" ./gradlew --no-daemon :app:assembleDebug
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
# `[ … ] && echo` as the final statement made the script exit 1 on every successful build
# without --install: set -e exempts the left side of an && list, so the script simply ran
# off the end carrying that 1. Harmless when nobody checked, actively misleading now that a
# non-zero exit is supposed to mean "the build failed, possibly killed by its memory cap".
if [ "$INSTALL" -eq 1 ]; then
    echo "installed on the connected device."
fi
