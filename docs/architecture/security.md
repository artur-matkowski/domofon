# Security model

*[Wiki home](../README.md) › architecture › security*

Personal-use scale, but published on Play — so the app assumes a hostile co-installed app
and a public APK. Nothing is exposed to the internet; everything rides the user's VPN.

## The boundaries, outermost first

1. **Nothing deployment-specific in the APK.** Broker, credentials, home coordinates and
   camera URLs are typed by the user at runtime and stored on device
   ([modules/config.md](../modules/config.md)). The release script scans the bundle
   against `scripts/secret-sentinels.txt` and aborts on any hit.
2. **The car host validator is the entire boundary of the car service.**
   `DomofonCarAppService` is exported (the host must bind it) and carries no
   `android:permission`. With `ALLOW_ALL_HOSTS_VALIDATOR`, any installed app holding *no
   permissions at all* could bind it, force an authenticated MQTT connection, fetch the
   template, and invoke the click delegate over the binder — **opening the gate with
   nothing drawn on screen**. Therefore: `ALLOW_ALL` exists only when
   `FLAG_DEBUGGABLE` (the DHU is an unknown host); release uses the library's real
   allowlist. **Debug-only, permanently.** Proof it is live: a release build *refuses* the
   DHU while debug works.
3. **Receivers are `exported="false"`, all of them** — each can move the gate. The
   debug-only `DebugGeofenceTrigger` exists as a *separate* receiver because
   `GeofencingEvent.fromIntent` parses unauthenticated extras; exporting the real receiver
   would let any app forge a geofence ENTER at the exact moment a live "Open gate" button
   appears in front of a driver.
4. **The keyguard re-check** (`GateCommandReceiver.refuseWhileLocked`, setting
   `security.requireUnlock`, default on): `setAuthenticationRequired(true)` covers taps
   SystemUI renders, but not a direct `PendingIntent.send()` from a notification listener
   — the receiver re-checks device lock server-side, the only place both paths funnel
   through. Residual risk R1 in [decisions.md](decisions.md) records what remains open.
5. **Secrets at rest**: the broker password and both camera URLs (credentials ride inline
   in them) are AES-256/GCM encrypted via an Android Keystore key (`SecretStore`, alias
   `domofon.config.secrets.v1` — the alias is load-bearing, renaming it orphans every
   stored secret). No user-auth requirement on the key, deliberately: the arrival flow
   decrypts while the phone is locked in a pocket. Keystore drops app keys when the lock
   screen changes — decryption failure is treated as "unset", surfacing as an empty field
   rather than an inexplicable broker auth failure.
6. **Tapjacking**: `filterTouchesWhenObscured` + `setHideOverlayWindows` (needs the
   install-time `HIDE_OVERLAY_WINDOWS` permission) on the exported `MainActivity` — the
   Open button's position is trivially predictable from the QML layout.
7. **Lock screen**: notifications are `VISIBILITY_PRIVATE` with a redacted public version
   — "Approaching home — gate: opened" on a lock screen tells anyone near the phone both
   that the owner is away and that the gate is open.

## Redaction rules (enforced by convention, checked in review)

- `DomofonConfig.Broker/Home/Camera` override `toString()` — a logged config or an
  exception message interpolating one must never print credentials or coordinates.
- Log lines never carry the broker host, camera URL, exception *messages* from network
  code (they can embed URLs with inline credentials), or the home position. The exception
  *class name* is the diagnosable, safe part.
- The settings screen masks passwords and strips `//user:pass@` userinfo from URL
  summaries; the release build strips `Log.v/d/i` via `-assumenosideeffects`.

## TLS

MQTT TLS is a user switch (a VPN-only broker is a legitimate plaintext setup, but it
should be chosen deliberately). Cleartext HTTP for the optional snapshot override is
accepted residual risk R2 — see [decisions.md](decisions.md).

## Related pages

[decisions.md](decisions.md) · [modules/config.md](../modules/config.md) ·
[modules/ui-car.md](../modules/ui-car.md) ·
[modules/ui-notifications.md](../modules/ui-notifications.md)
