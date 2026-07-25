#!/usr/bin/env bash
# Run the JVM unit tests.
#
# The suites are pure-JVM (domain rules, the MQTT service over a fake transport, the config
# parser, the ViewModel) — see docs/testing.md. They compile against the Qt-generated
# classes but never instantiate them, so this works anywhere build-debug.sh works.
#
# Same location-independence and same memory guard as the build scripts: the test compile
# pulls in the whole main source set and the nested Qt build, so it costs what a debug build
# costs and deserves the same ceiling.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$ROOT_DIR/app"

# shellcheck source=lib/memguard.sh
. "$SCRIPT_DIR/lib/memguard.sh"
MEM_REQUIRED_MB=6144
MEM_HIGH=6G
MEM_MAX=9G
MEM_SWAP=1G

FILTER=""
for arg in "$@"; do
    case "$arg" in
        -h|--help)
            echo "usage: $(basename "$0") [--tests <pattern>]"
            echo "  --tests <pattern>   Gradle test filter, e.g. '*GateServiceTest'"
            exit 0 ;;
        --tests) FILTER="PENDING" ;;
        *)
            if [ "$FILTER" = "PENDING" ]; then FILTER="$arg"
            else echo "unknown argument: $arg" >&2; exit 2
            fi ;;
    esac
done
[ "$FILTER" = "PENDING" ] && { echo "--tests needs a pattern" >&2; exit 2; }

cd "$APP_DIR"

preflight_memory "$MEM_REQUIRED_MB" test "$APP_DIR"

# --no-daemon for the same reason as the build scripts: a daemon surviving the transient
# scope would leave the next run's cap governing an idle client.
if [ -n "$FILTER" ]; then
    run_capped "$MEM_HIGH" "$MEM_MAX" "$MEM_SWAP" \
        ./gradlew --no-daemon :app:testDebugUnitTest --tests "$FILTER"
else
    run_capped "$MEM_HIGH" "$MEM_MAX" "$MEM_SWAP" ./gradlew --no-daemon :app:testDebugUnitTest
fi

REPORT="$APP_DIR/app/build/reports/tests/testDebugUnitTest/index.html"
echo
echo "tests passed. report: $REPORT"
