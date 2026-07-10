# bridge — Postgres → MQTT → REST

The only component that touches Postgres and the gate REST API. See
[../docs/02-mqtt-bridge.md](../docs/02-mqtt-bridge.md) for the topic contract and the
reasoning.

## Two things you must adapt before this runs

Both are marked `TODO(artur)` in the source:

1. **`STATE_QUERY`** in `bridge.env` — must return exactly one row of `(state, changed_at)`
   against your real gate schema.
2. **The REST payload** in `on_command()` in `bridge.py` — currently `{"action": "open"}`,
   which is a guess at your API's contract.

## Local run

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
cp bridge.env.example bridge.env   # then edit it
set -a && . ./bridge.env && set +a
python bridge.py
```

## Deploy on the home server

```bash
sudo install -d -o domofon -g domofon /opt/domofon
sudo cp -r . /opt/domofon/bridge
sudo cp domofon-bridge.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now domofon-bridge
journalctl -u domofon-bridge -f
```

## Acceptance test (milestone M1)

```bash
# 1. retained state must arrive the instant you subscribe
mosquitto_sub -h <broker> -u phone -P <pass> -t 'domofon/#' -v

# 2. flip the state in Postgres; within ~1s the subscriber prints it
psql gatedb -c "UPDATE gate_state SET state='opening', changed_at=now()"

# 3. a command must reach the REST API
mosquitto_pub -h <broker> -u phone -P <pass> -t domofon/gate/command \
  -m '{"action":"open","request_id":"test1"}'
journalctl -u domofon-bridge -n 5    # "command open ... -> REST 200"

# 4. LWT: stopping the bridge must flip domofon/bridge/status to "offline"
sudo systemctl stop domofon-bridge
```
