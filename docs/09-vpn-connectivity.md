# 09 — VPN + connectivity hardening (milestone M8)

Goal: the whole stack assumes the phone can reach home through **OpenVPN for Android**.
This chapter makes that assumption hold in practice, and makes the app honest when it
doesn't.

## 1. OpenVPN for Android setup for this use case

1. Import your `.ovpn` profile into **OpenVPN for Android** (Arne Schwabe's app —
   F-Droid or Play).
2. Profile → **Allowed Apps**: two workable modes —
   - *All apps through VPN* (default): simplest, everything works, but all phone
     traffic hairpins through home.
   - *Per-app VPN*: enable **"VPN is used for only these apps"** and select **Domofon**
     and (if you ever browse camera admin pages) your browser. Recommended: only
     Domofon rides the tunnel; the rest of the phone is unaffected.
3. **Always-on**: Android Settings → Network & Internet → VPN → ⚙ next to the OpenVPN
   profile → *Always-on VPN*. Do **not** enable *Block connections without VPN* if you
   chose per-app mode.
4. Profile → Battery: OpenVPN app set to **Unrestricted** (ch. 06); in the profile's
   connection settings keep *Persistent tun* and reconnect-on-network-change enabled.
5. **Transport**: prefer **UDP** for the tunnel. RTSP/RTP inside a TCP-based VPN causes
   the classic "video freezes then fast-forwards" meltdown (TCP-over-TCP). If your
   server only does TCP, revisit ch. 04 fallbacks and expect worse latency.

Sanity check from the phone (any terminal app, or just the Domofon app once built):
with mobile data + VPN, the broker's IP and the camera's IP must be reachable.

## 2. App-side reachability honesty

The app already has the key signal: `domofon/bridge/status` (LWT) tells it whether the
*bridge* is alive, and the MQTT connection state tells it whether *home* is reachable
at all. Surface both:

| Condition | UI |
|---|---|
| MQTT connected, bridge `online` | normal UI |
| MQTT connected, bridge `offline` | banner "Gate system down" — buttons disabled |
| MQTT unreachable (connect timeout) | banner "Home unreachable — VPN on?" |

Implementation notes:

- HiveMQ client: add a connect timeout (a few seconds) and use
  `addConnectedListener`/`addDisconnectedListener` to drive a
  `connectionState: StateFlow` in `GateRepository`; map it to the banner + a
  `bridgeOnline` property in QML (already plumbed in ch. 05).
- Optional deep-link nicety: the "VPN on?" banner can open OpenVPN for Android via its
  launch intent — one tap to fix the most common failure.
- Timeouts everywhere: MQTT keepalive ~30 s, RTSP player watchdog (if `MediaPlayer`
  reports a stalled/error state, show a *Retry* overlay instead of a black rectangle).

## 3. The full-chain failure drill (do this once, deliberately)

Break each link and confirm the app tells the truth and self-heals:

| Break | Expected app behavior |
|---|---|
| VPN off | "Home unreachable" within a few seconds; recovers when VPN reconnects |
| Bridge stopped | "Gate system down" via LWT ≤ keepalive; recovers on start |
| Broker stopped | same as VPN off (connection drops); reconnect loop recovers |
| Camera unplugged | gate control still works; video shows *Retry* overlay |
| Airplane mode 60 s while driving profile (DHU) | HUN resumes working after reconnect |

## Acceptance test — milestone M8

✅ **M8 passes when the drill table above matches reality, and a normal day (leave
home, come back through the geofence, open the gate from the car) works without ever
opening the OpenVPN app manually.**

At this point the app is *done* by the original definition. Celebrate, then file
whatever annoyed you as future ideas in the backlog section of
[10-troubleshooting.md](10-troubleshooting.md).
