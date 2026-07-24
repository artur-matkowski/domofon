#!/usr/bin/env bash
# Find a JPEG snapshot endpoint on a camera, for the app's "Snapshot URL" setting.
#
# The app draws the gate picture with a plain HTTP GET that must return one JPEG — stills
# cannot be decoded from the video stream on the device (that is a native crash, not a
# preference; see docs/10 -> nativeCreatePlanes). This script does the boring part: try the
# paths the common vendors use, say which one answered with an actual image, and which auth
# scheme it demanded.
#
# Usage:
#   scripts/find-snapshot-url.sh <camera-host> [user] [password]
#   scripts/find-snapshot-url.sh 192.168.1.60 admin              # prompts for the password
#   scripts/find-snapshot-url.sh 192.168.1.60                    # no auth at all
#   scripts/find-snapshot-url.sh --src gate 192.168.1.10 -p 1984 # a go2rtc/Frigate restream
#
# Options:
#   -p, --port <n>     default 80
#   -s, --scheme <s>   http (default) or https
#       --src <name>   also try go2rtc/Frigate restream paths for that source name
#       --path <p>     also try this path (repeatable) — for a vendor not listed below
#   -t, --timeout <n>  per-request timeout in seconds, default 5
#
# Passing the password as an argument puts it in `ps` output and your shell history. Omit
# it and the script prompts instead.
set -euo pipefail

PORT=80
SCHEME=http
TIMEOUT=5
SRC=""
EXTRA_PATHS=()
POSITIONAL=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--port)    PORT="$2"; shift 2 ;;
    -s|--scheme)  SCHEME="$2"; shift 2 ;;
    -t|--timeout) TIMEOUT="$2"; shift 2 ;;
    --src)        SRC="$2"; shift 2 ;;
    --path)       EXTRA_PATHS+=("$2"); shift 2 ;;
    -h|--help)    sed -n '2,26p' "$0"; exit 0 ;;
    -*)           echo "unknown option: $1" >&2; exit 2 ;;
    *)            POSITIONAL+=("$1"); shift ;;
  esac
done

HOST="${POSITIONAL[0]:-}"
USER_="${POSITIONAL[1]:-}"
PASS="${POSITIONAL[2]:-}"

if [[ -z "$HOST" ]]; then
  echo "usage: $(basename "$0") <camera-host> [user] [password]" >&2
  exit 2
fi

# A username with no password is almost always a typo rather than an intent, so ask rather
# than fire off a dozen requests that will all 401.
if [[ -n "$USER_" && -z "$PASS" ]]; then
  read -rsp "password for $USER_@$HOST: " PASS
  echo
fi

BASE="$SCHEME://$HOST:$PORT"

# --anyauth makes curl negotiate whatever the camera asks for, Basic or Digest. That is
# deliberately more permissive than the app, which speaks Basic only — so a path can pass
# here and still not work in the app. That is exactly what the auth-scheme check below is
# for, and why a Digest-only camera needs go2rtc in front of it.
AUTH=()
[[ -n "$USER_" ]] && AUTH=(--anyauth -u "$USER_:$PASS")

# One entry per vendor family. Reolink's takes credentials in the query string instead of
# an auth header, which is why it is built here rather than listed as a constant.
PATHS=(
  "/ISAPI/Streaming/channels/101/picture"      # Hikvision
  "/cgi-bin/snapshot.cgi?channel=1"            # Dahua / Amcrest
  "/cgi-bin/snapshot.cgi"                      # Dahua, older firmware
  "/tmpfs/auto.jpg"                            # Dahua / Amcrest, unauthenticated cache
  "/onvif-http/snapshot?Profile_1"             # ONVIF profile S, common OEM
  "/onvif/snapshot"
  "/axis-cgi/jpg/image.cgi"                    # Axis
  "/cgi-bin/hi3510/snap.cgi?&-getstream"       # HiSilicon OEM boards
  "/snapshot.jpg"
  "/snap.jpg"
  "/jpg/image.jpg"
  "/image/jpeg.cgi"
  "/cgi-bin/currentpic.cgi"
)
[[ -n "$USER_" ]] && PATHS+=("/cgi-bin/api.cgi?cmd=Snap&channel=0&user=$USER_&password=$PASS")  # Reolink
[[ -n "$SRC"   ]] && PATHS+=("/api/frame.jpeg?src=$SRC" "/api/$SRC/latest.jpg")                  # go2rtc / Frigate
PATHS+=("${EXTRA_PATHS[@]+"${EXTRA_PATHS[@]}"}")

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Is it a JPEG? Magic bytes, not `file`, and not the Content-Type header: cameras lie about
# the header, and a 200 OK carrying an HTML error page is the single most common near-miss.
is_jpeg() {
  [[ -s "$1" ]] && [[ "$(head -c2 "$1" | od -An -tx1 | tr -d ' \n')" == "ffd8" ]]
}

echo "Probing $BASE  (${#PATHS[@]} paths, ${TIMEOUT}s timeout)"
[[ -z "$USER_" ]] && echo "No credentials given — anything needing auth will show 401."
echo

WINNERS=()
printf '%-6s %-8s %-7s %s\n' "CODE" "BYTES" "TIME" "PATH"
for path in "${PATHS[@]}"; do
  # Truncate first: curl leaves -o untouched when a request times out with nothing
  # downloaded, so a reused file still holds the *previous* path's body — which showed up
  # as a timed-out path cheerfully reported as a JPEG.
  body="$TMP/body"
  : > "$body"
  read -r code size time_total < <(
    curl -s -o "$body" -w '%{http_code} %{size_download} %{time_total}\n' \
         "${AUTH[@]}" -m "$TIMEOUT" "$BASE$path" 2>/dev/null || echo "000 0 0"
  )
  if is_jpeg "$body"; then
    mark="JPEG"
    WINNERS+=("$path")
  elif [[ "$code" == "000" ]]; then
    mark="no answer"
  else
    mark="not an image"
  fi
  printf '%-6s %-8s %-7s %-45s %s\n' "$code" "$size" "$time_total" "$path" "$mark"
done

echo
if [[ ${#WINNERS[@]} -eq 0 ]]; then
  cat <<EOF
Nothing returned a JPEG.

  * Wrong port? Many cameras serve their web UI on 8000/8080: -p 8080
  * HTTPS only? -s https
  * Vendor not covered? Find the snapshot URL in the camera's own web UI (right-click the
    live image -> copy image address) and re-run with --path '/that/path'
  * No HTTP snapshot at all? Put go2rtc in front of the RTSP stream and probe that instead:
      $(basename "$0") --src gate <go2rtc-host> -p 1984
    That is also the fix for a camera that only speaks Digest — see docs/04 §1.1.
EOF
  exit 1
fi

echo "Returned a real JPEG:"
for w in "${WINNERS[@]}"; do echo "  $w"; done
echo

BEST="${WINNERS[0]}"
CHALLENGE="$(curl -s -D- -o /dev/null -m "$TIMEOUT" "$BASE$BEST" 2>/dev/null \
             | grep -i '^www-authenticate:' | head -1 | tr -d '\r' || true)"

if [[ -z "$CHALLENGE" ]]; then
  echo "Auth: none required. Paste this into Settings -> Camera -> Snapshot URL:"
  echo
  echo "    $BASE$BEST"
elif grep -qi 'digest' <<<"$CHALLENGE"; then
  cat <<EOF
Auth: Digest ($CHALLENGE)

The app speaks Basic only, so this endpoint will NOT work directly — the credentials are
correct and the picture still never arrives. Put go2rtc in front of the camera and point
the setting at its frame endpoint instead:

    http://<go2rtc-host>:1984/api/frame.jpeg?src=gate

Verify it with:  $(basename "$0") --src gate <go2rtc-host> -p 1984
EOF
  exit 1
else
  echo "Auth: Basic. Paste this into Settings -> Camera -> Snapshot URL:"
  echo
  echo "    $SCHEME://$USER_:$PASS@$HOST:$PORT$BEST"
  echo
  echo "(Credentials inline is how the app expects it — it moves them into an Authorization"
  echo " header itself, because HttpURLConnection ignores userinfo in a URL.)"
fi

cat <<EOF

Before trusting it: re-run this from a laptop on the phone hotspot with the VPN up. The app
makes this request every few seconds across that tunnel, and a snapshot that only works on
the LAN is a snapshot that only works at home.
EOF
