#!/usr/bin/env bash
# Launch the Android Auto Desktop Head Unit against the connected phone.
#
# Two things this handles that the raw binary does not:
#   * The DHU is built against LLVM's libc++, which Debian does not install by
#     default. Rather than pulling in libc++1/libc++abi1, borrow the host copies
#     the Android NDK already ships.
#   * The DHU talks to the phone's head-unit server over localhost:5277, which
#     only exists once `adb forward` is in place.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
DHU_DIR="$SDK/extras/google/auto"
ADB="$SDK/platform-tools/adb"
PORT=5277

[[ -x "$DHU_DIR/desktop-head-unit" ]] || {
  echo "DHU not found at $DHU_DIR — install it with:" >&2
  echo "  sdkmanager 'extras;google;auto'" >&2
  exit 1
}

# Newest NDK that ships a host x86_64 libc++.so.1.
LIBCXX=""
for d in "$SDK"/ndk/*/toolchains/llvm/prebuilt/linux-x86_64/lib/x86_64-unknown-linux-gnu; do
  [[ -f "$d/libc++.so.1" ]] && LIBCXX="$d"
done
[[ -n "$LIBCXX" ]] || {
  echo "No NDK host libc++ found under $SDK/ndk/*." >&2
  echo "Either install an NDK, or: sudo apt install libc++1 libc++abi1" >&2
  exit 1
}

"$ADB" get-state >/dev/null 2>&1 || { echo "No adb device. Plug the phone in." >&2; exit 1; }

# Deliberately no liveness probe on port 5277. Two reasons, both learned the hard way:
#
#   * It cannot work. `adb forward` accepts the local connection before it knows
#     whether anything listens on the phone, so a dead port looks alive. Reading
#     /proc/net/tcp on the phone fails too -- Android hides other UIDs' sockets
#     from the `shell` user, so Android Auto's socket is never visible.
#   * It is destructive. The head unit server gives its session to the FIRST
#     connection it accepts and does not recover. A probe consumes that session,
#     and every later DHU run then hangs at "connected." forever.
#
# So: the DHU must be the first thing to connect after you start the server.
"$ADB" forward "tcp:$PORT" "tcp:$PORT" >/dev/null

[[ -t 0 ]] || echo "warning: no tty on stdin; the DHU reads console commands and will exit at EOF." >&2

echo "Forwarded tcp:$PORT; starting DHU (libc++ from ${LIBCXX##*/ndk/})."
echo "If it sits at 'Waiting for phone...', restart the head unit server on the"
echo "phone (dev settings -> stop, then start) and run this again."

cd "$DHU_DIR"
exec env LD_LIBRARY_PATH="$LIBCXX:$DHU_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
  ./desktop-head-unit "$@"
