#!/usr/bin/env python3
"""Domofon bridge: Postgres gate state -> MQTT, MQTT commands -> REST.

The only component that talks to Postgres and the REST API. Everything else
(phone UI, Android Auto, notifications) sees MQTT only. See docs/02-mqtt-bridge.md.
"""
from __future__ import annotations

import json
import logging
import os
import signal
import time
from datetime import datetime, timezone

import paho.mqtt.client as mqtt
import psycopg
import requests

log = logging.getLogger("bridge")

# --- config via environment (see bridge.env.example) --------------------------
PG_DSN = os.environ["PG_DSN"]
STATE_QUERY = os.environ["STATE_QUERY"]
MQTT_HOST = os.environ.get("MQTT_HOST", "127.0.0.1")
MQTT_PORT = int(os.environ.get("MQTT_PORT", "1883"))
MQTT_USER = os.environ.get("MQTT_USER", "bridge")
MQTT_PASS = os.environ["MQTT_PASS"]
REST_URL = os.environ["REST_URL"]
POLL_SECONDS = float(os.environ.get("POLL_SECONDS", "1.0"))

T_STATE = "domofon/gate/state"
T_CMD = "domofon/gate/command"
T_STATUS = "domofon/bridge/status"

ALLOWED_ACTIONS = {"open", "close", "stop"}


def fetch_state(conn) -> tuple[str, str]:
    with conn.cursor() as cur:
        cur.execute(STATE_QUERY)
        row = cur.fetchone()
    if row is None:
        raise RuntimeError("STATE_QUERY returned no rows")
    state, changed_at = row
    if isinstance(changed_at, datetime):
        changed_at = changed_at.astimezone(timezone.utc).isoformat()
    return str(state), str(changed_at)


def publish_state(client: mqtt.Client, state: str, changed_at: str) -> None:
    payload = json.dumps({"state": state, "changed_at": changed_at})
    client.publish(T_STATE, payload, qos=1, retain=True)
    log.info("state -> %s", payload)


def on_connect(client, userdata, flags, reason_code, properties=None) -> None:
    # Subscribe and re-announce liveness on every (re)connect, not just the first:
    # after a broker restart the subscription is gone and the bridge would be deaf
    # to commands while still looking healthy.
    if reason_code != 0:
        log.error("mqtt connect failed: %s", reason_code)
        return
    client.subscribe(T_CMD, qos=1)
    client.publish(T_STATUS, "online", qos=1, retain=True)
    log.info("connected to %s:%s, subscribed to %s", MQTT_HOST, MQTT_PORT, T_CMD)


def on_command(client, userdata, msg) -> None:
    try:
        cmd = json.loads(msg.payload)
    except json.JSONDecodeError:
        log.warning("command payload is not JSON: %r", msg.payload[:120])
        return

    action = cmd.get("action")
    if action not in ALLOWED_ACTIONS:
        log.warning("rejected action %r", action)
        return

    try:
        # TODO(artur): match your REST API's JSON contract — this is a guess.
        r = requests.post(REST_URL, json={"action": action}, timeout=5)
        log.info("command %s (id=%s) -> REST %s",
                 action, cmd.get("request_id"), r.status_code)
    except requests.RequestException:
        log.exception("REST call failed for action %s", action)


def main() -> None:
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(message)s")

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="domofon-bridge")
    client.username_pw_set(MQTT_USER, MQTT_PASS)
    client.will_set(T_STATUS, "offline", qos=1, retain=True)
    client.on_connect = on_connect
    client.on_message = on_command
    client.connect(MQTT_HOST, MQTT_PORT, keepalive=30)
    client.loop_start()

    running = True

    def stop(*_) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)

    last: tuple[str, str] | None = None
    while running:
        try:
            with psycopg.connect(PG_DSN, connect_timeout=5) as conn:
                while running:
                    current = fetch_state(conn)  # initial pull, then polling
                    if current != last:
                        publish_state(client, *current)
                        last = current
                    time.sleep(POLL_SECONDS)
        except Exception:
            if not running:
                break
            log.exception("postgres loop failed; retrying in 5s")
            time.sleep(5)

    client.publish(T_STATUS, "offline", qos=1, retain=True).wait_for_publish(3)
    client.loop_stop()
    client.disconnect()


if __name__ == "__main__":
    main()
