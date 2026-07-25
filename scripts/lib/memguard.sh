# shellcheck shell=bash
#
# memguard — keep a Gradle build from taking the desktop down with it.
#
# Why this exists. On 2026-07-24 at 21:34 a release build froze this machine solid: black
# screen, mouse at ~0.5 fps, nine minutes, hard reboot. The build was not the cause. The
# Gradle daemon log (~/.gradle/daemon/<ver>/daemon-<pid>.out.log) records system-wide
# MemAvailable every 5 s, and it shows the machine had been under 3 GB available for almost
# three hours beforehand — down to 0.29 GB at 19:46 — with a 3.1 GiB Gradle worker daemon
# squatting the whole time (Gradle logged "0 released" 3474 times trying to expire it).
# The build then asked for a few more GB of a machine that had 0.58 GB left.
#
# Nothing on the host could break the deadlock: 3.7 GB of swap, no zram, and neither
# earlyoom nor systemd-oomd installed. With nothing to kill and nowhere to swap, the kernel
# reclaim-thrashed instead. That is what a frozen desktop with a live mouse pointer is.
#
# So there are two defences here, and they cover different failures:
#
#   preflight_memory   refuses to *start* a build into a machine that is already starved,
#                      and names what to close. Necessary because a cgroup cap limits, it
#                      does not reserve — capping the build does nothing if Firefox, Steam
#                      and Docker have already eaten 30 GB.
#
#   run_capped         runs the build inside a transient systemd scope with MemoryMax set,
#                      so a runaway build is OOM-killed *inside its own cgroup* and the
#                      session survives. Verified on this machine: exit 137, desktop
#                      untouched.
#
# One measured caveat that shapes how callers use this: a process that detaches inside a
# scope does NOT die when the scope's main command exits. It keeps running and stays in
# that scope's cgroup, so the scope unit lingers too. A Gradle daemon left alive that way
# would make the *next* build's cap meaningless — the client would join the old daemon and
# all the real work would happen in the previous scope's cgroup. Both build scripts
# therefore pass --no-daemon, so one scope means one build and nothing outlives it.
#
# Host-level hardening (earlyoom, zram, moving the agent's tmpfs scratchpad off RAM) is
# deliberately NOT done here — see docs/10 "Build machine / host resources".

# --- knobs (env overrides, all in MB unless noted) -------------------------------------
#   DOMOFON_SKIP_MEM_CHECK=1   skip the pre-flight entirely
#   DOMOFON_MEM_REQUIRED=<MB>  override the pre-flight threshold
#   DOMOFON_MEM_MAX=<size>     override MemoryMax   (systemd size, e.g. 12G)
#   DOMOFON_MEM_HIGH=<size>    override MemoryHigh
#   DOMOFON_MEM_SWAP=<size>    override MemorySwapMax
#   DOMOFON_NO_CAP=1           skip the cgroup cap entirely

mem_available_mb() {
    awk '/^MemAvailable:/ { print int($2 / 1024); exit }' /proc/meminfo
}

# The ten fattest processes, folded by command name — a build refusal is only useful if it
# says what to close.
mem_top_consumers() {
    ps -eo rss=,comm= --sort=-rss 2>/dev/null \
        | awk '{ rss[$2] += $1 } END { for (c in rss) printf "%8d MB  %s\n", rss[c]/1024, c }' \
        | sort -rn | head -10
}

# preflight_memory <required_mb> <label> [gradlew_dir]
#
# Refuses to start when the machine is already starved. If a gradlew directory is given and
# memory is short, stale Gradle daemons are stopped first and the check retried — on the
# night of the incident that alone would have returned ~3 GB.
preflight_memory() {
    local required="${DOMOFON_MEM_REQUIRED:-$1}" label="$2" gradlew_dir="${3:-}"
    local avail

    if [ "${DOMOFON_SKIP_MEM_CHECK:-0}" = "1" ]; then
        echo "memguard: pre-flight skipped (DOMOFON_SKIP_MEM_CHECK=1)." >&2
        return 0
    fi

    avail="$(mem_available_mb)"
    if [ -z "$avail" ]; then
        echo "memguard: cannot read MemAvailable from /proc/meminfo — skipping pre-flight." >&2
        return 0
    fi

    if [ "$avail" -lt "$required" ] && [ -n "$gradlew_dir" ] && [ -x "$gradlew_dir/gradlew" ]; then
        echo "memguard: ${avail} MB available, need ${required} MB — stopping stale Gradle daemons first." >&2
        ( cd "$gradlew_dir" && ./gradlew --stop >/dev/null 2>&1 ) || true
        sleep 2
        avail="$(mem_available_mb)"
        echo "memguard: ${avail} MB available after stopping daemons." >&2
    fi

    if [ "$avail" -lt "$required" ]; then
        cat >&2 <<EOF

memguard: refusing to start the $label build.

  available: ${avail} MB
  required:  ${required} MB   (short by $((required - avail)) MB)

Starting a build into this little memory is what froze the machine on 2026-07-24. Close
something and try again — the biggest resident processes right now are:

$(mem_top_consumers)

Override with DOMOFON_SKIP_MEM_CHECK=1 if you know better, or lower the bar with
DOMOFON_MEM_REQUIRED=<MB>.
EOF
        return 1
    fi

    echo "memguard: ${avail} MB available (need ${required}) — ok."
    return 0
}

# True when a transient systemd scope with a memory cap can actually be created here.
_memguard_can_cap() {
    [ "${DOMOFON_NO_CAP:-0}" != "1" ] || return 1
    command -v systemd-run >/dev/null 2>&1 || return 1
    [ "$(stat -fc %T /sys/fs/cgroup 2>/dev/null)" = "cgroup2fs" ] || return 1
    [ -n "${XDG_RUNTIME_DIR:-}" ] && [ -S "${XDG_RUNTIME_DIR}/bus" ] || return 1
    # The memory controller must be delegated to this user's slice, or MemoryMax is ignored.
    grep -qw memory "/sys/fs/cgroup/user.slice/user-$(id -u).slice/cgroup.controllers" 2>/dev/null
}

# Runs inside the scope: execute the command, then report the cgroup's own peak usage and
# whether the kernel OOM-killed anything in it. Read from inside because --collect removes
# the unit as soon as it exits, so there is nothing left to inspect afterwards.
# Single-quoted on purpose — no interpolation; the command arrives as "$@".
_MEMGUARD_INNER='
cg="/sys/fs/cgroup$(sed -n "s|^0::||p" /proc/self/cgroup)"
"$@"
rc=$?
peak=$(cat "$cg/memory.peak" 2>/dev/null || echo 0)
oom=$(grep -m1 "^oom_kill " "$cg/memory.events" 2>/dev/null | cut -d" " -f2)
printf "\nmemguard: peak memory in the build cgroup: %s MB (oom_kill=%s)\n" \
    "$((peak / 1048576))" "${oom:-?}" >&2
if [ "${oom:-0}" != "0" ] && [ -n "${oom:-}" ]; then
    printf "memguard: the build was KILLED BY ITS MEMORY CAP, not by a compile error.\n" >&2
    printf "memguard: raise it with DOMOFON_MEM_MAX=<size> if the cap is genuinely too low.\n" >&2
fi
# Reap anything the build left running. --no-daemon covers the outer Gradle, but the Qt
# Gradle Plugin runs a *nested* Gradle build out of build/qt_generated/.../android-build-*
# with its own wrapper (9.3.1 vs the outer 9.4.1) and its own gradle.properties carrying
# another -Xmx3200m and org.gradle.parallel=true. That daemon is not covered by the outer
# --no-daemon and squats for Gradle default idle timeout of three hours. On 2026-07-24
# both stacks were alive and both logged the death spiral.
# Only this build put processes in this cgroup, so killing what is left here is precise.
leftover=$(grep -vx "$$" "$cg/cgroup.procs" 2>/dev/null)
if [ -n "$leftover" ]; then
    printf "memguard: reaping %s process(es) the build left behind (Qt nested daemon).\n" \
        "$(printf "%s\n" "$leftover" | wc -l)" >&2
    printf "%s\n" "$leftover" | xargs -r kill 2>/dev/null
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        sleep 0.5
        leftover=$(grep -vx "$$" "$cg/cgroup.procs" 2>/dev/null)
        [ -z "$leftover" ] && break
    done
    [ -n "$leftover" ] && printf "%s\n" "$leftover" | xargs -r kill -9 2>/dev/null
fi
exit $rc
'

# run_capped <high> <max> <swapmax> <cmd> [args...]
run_capped() {
    local high="${DOMOFON_MEM_HIGH:-$1}" max="${DOMOFON_MEM_MAX:-$2}" swap="${DOMOFON_MEM_SWAP:-$3}"
    shift 3

    if ! _memguard_can_cap; then
        echo "memguard: WARNING — no cgroup memory cap available (systemd-run/cgroup2/user" >&2
        echo "memguard: delegation missing, or DOMOFON_NO_CAP=1). Running UNCAPPED: if this" >&2
        echo "memguard: build runs away it can freeze the desktop again." >&2
        "$@"
        return $?
    fi

    echo "memguard: running under MemoryHigh=$high MemoryMax=$max MemorySwapMax=$swap."
    systemd-run --user --scope --collect --quiet \
        --unit="domofon-build-$$-${RANDOM}" \
        -p MemoryHigh="$high" -p MemoryMax="$max" -p MemorySwapMax="$swap" \
        -- bash -c "$_MEMGUARD_INNER" memguard "$@"
}
