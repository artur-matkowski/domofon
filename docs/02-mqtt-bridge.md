# 02 — MQTT topic contract + bridge service (milestone M1)

> ## ⚠️ This chapter describes a bridge that was never deployed
>
> It documents a Python service publishing `domofon/gate/state` / `domofon/gate/command` /
> `domofon/bridge/status`. **The app has not spoken that contract since `0901e2e`**, and
> `bridge/` was dropped from the repo in that same commit. The real deployment is
> **`hc12-web-service`** (C++/Poco, on `rpi-d`), whose MQTT bridge speaks:
>
> | Topic | Direction | Retained | Payload |
> |---|---|---|---|
> | `hc12/rx/<MessageName>` | radio → MQTT | yes | `{"idSender":4,"idTarget":255,"ts":"…Z"}` (no `value` for signals) |
> | `hc12/tx/<MessageName>` | MQTT → radio | **must not be** | `{"idTarget":4}` — `idTarget` required, `idSender` forced to 0 |
> | `hc12/error` | rejections | no | `{"topic":…,"reason":…,"ts":…}` |
> | `hc12/available` | LWT | yes | `online` / `offline` |
>
> Message names (`OpenGate`, `GateOpened`, …) come from the shared
> `hc12-message-definitions` repo; the topic *is* the message name, so an unknown name is
> rejected rather than transmitted. The authoritative write-up is the wiki page
> **`infra/hc12-web-service`**, and the app's side of it is `GateRepository`
> (`SIGNAL_TO_STATE`) plus `DomofonConfig.Defaults`.
>
> Keep the rest of this chapter for the reasoning it records — the retained-state rule, the
> LWT rule, and why the app never touches Postgres or REST — all of which still hold.

The bridge is the only component that talks to Postgres and the REST API. Everything
else — phone UI, Android Auto, notifications — sees only MQTT. Get this chapter right
and every later chapter becomes UI work.

## 1. Topic contract

| Topic | Publisher | QoS | Retained | Payload |
|---|---|---|---|---|
| `domofon/gate/state` | bridge | 1 | **yes** | see below |
| `domofon/gate/command` | app | 1 | no | see below |
| `domofon/bridge/status` | bridge (LWT) | 1 | yes | `online` / `offline` |

**`domofon/gate/state`** — published whenever the state in Postgres changes, *and* once
at bridge startup (initial pull). Retained, so every new subscriber instantly receives
the current state.

```json
{"state": "opening", "changed_at": "2026-07-05T18:21:07+02:00"}
```

Use your exact state strings from the DB (`opened`, `opening`, `closing`, `closed`, …) —
the app treats them as opaque labels plus a small mapping for icons/notifications.

**`domofon/gate/command`** — published by the app (phone button or car screen action):

```json
{"action": "open", "request_id": "b4f1c2", "ts": "2026-07-05T18:21:03+02:00"}
```

`action` ∈ `open | close | stop` (allowlist — adapt to what your REST API supports).
`request_id` is a random short string; it lets you correlate logs when debugging.
Command *results* are not a separate topic: the observable result **is** the state
change on `domofon/gate/state`.

**`domofon/bridge/status`** — MQTT Last-Will-and-Testament. The broker itself flips it
to `offline` if the bridge dies. The app shows "gate system unreachable" when this is
not `online` — that's how you distinguish "gate closed" from "I have no idea".

## 2. Broker preparation

Your broker already runs; add credentials for the two new clients (Mosquitto syntax —
adapt if yours differs):

```bash
mosquitto_passwd /etc/mosquitto/passwd bridge
mosquitto_passwd /etc/mosquitto/passwd phone
```

Optional but recommended if the broker serves other things — ACL:

```
user bridge
topic readwrite domofon/#

user phone
topic read domofon/gate/state
topic read domofon/bridge/status
topic write domofon/gate/command
```

The broker must listen on an address reachable from the VPN subnet (check
`listener`/`bind_address`). No TLS needed if it is VPN-only.

## 3. The bridge service

Python, ~120 lines, three jobs: initial pull → publish retained; poll for changes →
publish; subscribe commands → POST to REST.

This one is already scaffolded in [`bridge/`](../bridge/README.md); the two spots you must
adapt to your own schema and API carry `TODO(artur)` markers.

```bash
mkdir -p bridge && cd bridge
python3 -m venv .venv && . .venv/bin/activate
pip install "paho-mqtt>=2.0" "psycopg[binary]>=3.1" requests
```

`bridge/bridge.py`:

```python
#!/usr/bin/env python3
"""Domofon bridge: Postgres gate state -> MQTT, MQTT commands -> REST."""
import json, logging, os, signal, time
from datetime import datetime, timezone

import psycopg
import requests
import paho.mqtt.client as mqtt

log = logging.getLogger("bridge")

# --- config via environment -------------------------------------------------
PG_DSN        = os.environ["PG_DSN"]          # postgresql://user:pass@host/db
STATE_QUERY   = os.environ["STATE_QUERY"]     # must return (state, changed_at)
MQTT_HOST     = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT     = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_USER     = os.environ.get("MQTT_USER", "bridge")
MQTT_PASS     = os.environ["MQTT_PASS"]
REST_URL      = os.environ["REST_URL"]        # your gate endpoint
POLL_SECONDS  = float(os.environ.get("POLL_SECONDS", "1.0"))

T_STATE, T_CMD, T_STATUS = ("domofon/gate/state",
                            "domofon/gate/command",
                            "domofon/bridge/status")
ALLOWED_ACTIONS = {"open", "close", "stop"}

# --- helpers -----------------------------------------------------------------
def fetch_state(conn):
    with conn.cursor() as cur:
        cur.execute(STATE_QUERY)
        state, changed_at = cur.fetchone()
    if isinstance(changed_at, datetime):
        changed_at = changed_at.astimezone(timezone.utc).isoformat()
    return str(state), changed_at

def publish_state(client, state, changed_at):
    payload = json.dumps({"state": state, "changed_at": changed_at})
    client.publish(T_STATE, payload, qos=1, retain=True)
    log.info("state -> %s", payload)

def on_connect(client, userdata, flags, reason_code, properties=None):
    # Subscribe and re-announce liveness on EVERY (re)connect, not just the first.
    # After a broker restart the subscription is gone, and a bridge that still looks
    # "online" but silently ignores commands is the worst failure mode there is.
    if reason_code != 0:
        log.error("mqtt connect failed: %s", reason_code)
        return
    client.subscribe(T_CMD, qos=1)
    client.publish(T_STATUS, "online", qos=1, retain=True)

def on_command(client, userdata, msg):
    try:
        cmd = json.loads(msg.payload)
        action = cmd.get("action")
        if action not in ALLOWED_ACTIONS:
            log.warning("rejected action %r", action)
            return
        # >>> Adapt this payload to your REST API's contract <<<
        r = requests.post(REST_URL, json={"action": action}, timeout=5)
        log.info("command %s (id=%s) -> REST %s",
                 action, cmd.get("request_id"), r.status_code)
    except Exception:
        log.exception("command handling failed")

# --- main --------------------------------------------------------------------
def main():
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="domofon-bridge")
    client.username_pw_set(MQTT_USER, MQTT_PASS)
    client.will_set(T_STATUS, "offline", qos=1, retain=True)
    client.on_connect = on_connect          # subscribes + publishes "online"
    client.on_message = on_command
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
    client.loop_start()

    running = True
    def stop(*_):
        nonlocal running
        running = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    last = None
    while running:
        try:
            with psycopg.connect(PG_DSN, connect_timeout=5) as conn:
                while running:
                    current = fetch_state(conn)          # initial pull + polling
                    if current != last:
                        publish_state(client, *current)
                        last = current
                    time.sleep(POLL_SECONDS)
        except Exception:
            log.exception("postgres loop failed; retrying in 5s")
            time.sleep(5)

    client.publish(T_STATUS, "offline", qos=1, retain=True).wait_for_publish(3)
    client.loop_stop()

if __name__ == "__main__":
    main()
```

Two lines you must adapt:
- **`STATE_QUERY`** — e.g. `SELECT state, changed_at FROM gate_state ORDER BY changed_at DESC LIMIT 1`
  (whatever matches your schema; it must return exactly one row of `(state, timestamp)`).
- **the REST payload** in `on_command` — match your API's JSON contract.

`bridge/bridge.env` (never commit real credentials — add to `.gitignore`):

```bash
PG_DSN=postgresql://readonly_user:secret@127.0.0.1/gatedb
STATE_QUERY=SELECT state, changed_at FROM gate_state ORDER BY changed_at DESC LIMIT 1
MQTT_HOST=127.0.0.1
MQTT_PASS=secret
REST_URL=http://127.0.0.1:8080/api/gate
POLL_SECONDS=1.0
```

## 4. Run it as a service

`/etc/systemd/system/domofon-bridge.service` on the home server:

```ini
[Unit]
Description=Domofon MQTT bridge
After=network-online.target postgresql.service mosquitto.service
Wants=network-online.target

[Service]
User=domofon
EnvironmentFile=/opt/domofon/bridge/bridge.env
ExecStart=/opt/domofon/bridge/.venv/bin/python /opt/domofon/bridge/bridge.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now domofon-bridge
journalctl -u domofon-bridge -f
```

## 5. Acceptance test — milestone M1

From your Debian machine (on the home LAN or connected to the VPN):

```bash
# Terminal 1 — watch state (the retained message must arrive IMMEDIATELY):
mosquitto_sub -h <broker> -u phone -P <pass> -t 'domofon/#' -v

# Terminal 2 — flip the state in Postgres by hand (or use the real gate):
psql gatedb -c "UPDATE gate_state SET state='opening', changed_at=now()"
# → within ~1s terminal 1 prints the new domofon/gate/state

# Terminal 3 — send a command and watch the REST API get called:
mosquitto_pub -h <broker> -u phone -P <pass> -t domofon/gate/command \
  -m '{"action":"open","request_id":"test1"}'
journalctl -u domofon-bridge -n 5    # shows "command open ... -> REST 200"
```

Also verify LWT: `sudo systemctl stop domofon-bridge` → subscriber receives
`domofon/bridge/status offline`.

✅ **M1 passes when all three checks work over the VPN** (test from your phone's
hotspot with OpenVPN active on a laptop, or just trust the broker binding for now and
re-verify in ch. 09).

## Optional upgrade: LISTEN/NOTIFY instead of polling

Instant push, no polling. One trigger in Postgres:

```sql
CREATE OR REPLACE FUNCTION notify_gate_state() RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('gate_state', row_to_json(NEW)::text);
  RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER gate_state_notify
AFTER INSERT OR UPDATE ON gate_state
FOR EACH ROW EXECUTE FUNCTION notify_gate_state();
```

In the bridge, replace the polling `while` loop body with:

```python
conn.execute("LISTEN gate_state")
gen = conn.notifies()                 # psycopg3 blocking generator
for notify in gen:
    data = json.loads(notify.payload)
    publish_state(client, str(data["state"]), data["changed_at"])
```

Keep the initial `fetch_state` publish at connect time — retained state must be correct
even if no change ever fires. Do this upgrade only if the ~1 s polling delay actually
bothers you.
