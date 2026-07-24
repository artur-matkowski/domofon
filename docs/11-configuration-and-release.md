# 11 — Configuration, hardening and the Play release

Chapters 00–09 build a personal app for one gate on one homelab. Publishing it changes
what "correct" means: the APK becomes a public artifact, the users become strangers, and
Google becomes a reviewer. This chapter covers the three consequences — configuration
moves onto the device, the security holes that were tolerable in a sideloaded build get
closed, and the Play Console paperwork gets assembled.

---

## 1. Why nothing may be compiled in any more

The scaffold read broker credentials from `local.properties` into `BuildConfig`, and kept
the home coordinates as `const val`s in `GeofenceManager`. Both are fine for a build that
never leaves your phone. Neither survives publication:

```bash
unzip -p app-release.aab | strings | grep -i mqtt   # this is the whole attack
```

`BuildConfig` fields are string constants in the DEX. Anyone who downloads the app gets
them. The same argument applies to the coordinates, which are your home address.

So the rule for the rest of this project: **if a value describes a deployment rather than
the protocol, it lives in `ConfigStore` and nowhere else.** Topic *prefixes* are
deployment. The signal names inside them (`GateOpened`, `GateClosing`, …) are protocol,
and stay in code — see §2.

Verify it holds, every release:

```bash
./gradlew :app:assembleRelease
unzip -p app/build/outputs/apk/release/app-release-unsigned.apk \
  | strings -a | grep -aicF '<your broker host>'      # must print 0
```

## 2. The configuration model

`pl.bitforge.domofon.config`:

| File | Role |
|---|---|
| `DomofonConfig.kt` | Immutable snapshot: `Broker`, `Topics`, `Home`, `Camera`. `isComplete` gates every entry point. |
| `ConfigStore.kt` | `SharedPreferences`-backed singleton. Exposes `config: StateFlow` and a synchronous `current`. Also *is* the `PreferenceDataStore` behind the settings screen. |
| `SecretStore.kt` | AES-256/GCM via the Android Keystore, for the broker password and both camera URLs (each carries its credentials inline). |
| `SettingsActivity.kt` | `PreferenceFragmentCompat` over `res/xml/preferences.xml`. |

**Why `SharedPreferences` and not DataStore.** Every consumer is either a
`BroadcastReceiver` that needs a value immediately — `GeofenceReceiver` has ~10 s of
`goAsync` budget and no room for a suspending read — or the preference framework, which is
synchronous by design. DataStore would have to be bridged back to blocking at both ends.
The file is a few hundred bytes in app-private storage, already covered by file-based
encryption, with the two real secrets Keystore-encrypted on top.

**Why not `androidx.security:security-crypto`.** Jetpack Security is deprecated;
`EncryptedSharedPreferences` is no longer the recommendation. `SecretStore` does what
replaced it — talks to the Keystore directly.

**The Keystore key requires no user authentication, deliberately.** `GeofenceReceiver`
decrypts the broker password while the phone is locked in your pocket on the drive home.
Requiring auth on the key would break the one feature the geofence exists for.
Confirmation for *acting* on the gate is enforced separately, at the notification action —
see §3.

**The one thing that is not configurable.** `SIGNAL_TO_STATE` in `GateRepository` maps the
seven hc12 radio signals to UI labels. Exposing it would mean seven more text fields for a
setting almost nobody changes. Prefixes, availability topic, node id and payload key *are*
configurable, which covers the realistic variation.

### Adding a setting

1. Add the key constant to `ConfigStore` (`K_*`).
2. Add the field to the relevant nested class in `DomofonConfig`, and to `EMPTY`.
3. Read it in `ConfigStore.read()`.
4. Add the `<EditTextPreference>`/`<SwitchPreferenceCompat>` to `res/xml/preferences.xml`
   with **the same `app:key` string** — nothing checks that correspondence at compile time.
5. If it is a secret, add the key to `SECRET_KEYS` and call `masked()` in
   `SettingsFragment`.

## 3. What was fixed, and why each one mattered

| Area | Before | After |
|---|---|---|
| Credentials | Compiled into `BuildConfig` | On-device, Keystore-encrypted |
| Car host validation | `ALLOW_ALL_HOSTS_VALIDATOR` in every build | Real allowlist in release; `ALLOW_ALL` only when debuggable |
| Home coordinates | `const val` in source and in git | `ConfigStore`, geofence off by default |
| Lock screen | Full text + live "Open gate" button | `VISIBILITY_PRIVATE` + unlock required (default on) |
| MQTT transport | Plaintext only | TLS toggle |
| Client id | Derived from `Build.MODEL` | Random UUID per install |
| Release build | No R8, no signing | R8 on, signing from `keystore.properties` |
| Debug geofence trigger | Exported the *real* receiver | Inert `DebugGeofenceTrigger`, `src/debug` only |
| Backup | `allowBackup=false` only | Plus `dataExtractionRules` (device transfer) |

Three deserve explanation, because they were not obvious.

**The car service was remotely exploitable.** `CarAppService` is exported — it has to be,
the host binds it — and carries no `android:permission`, so `createHostValidator()` was the
entire security boundary. With `ALLOW_ALL_HOSTS_VALIDATOR`, any installed app holding *no
permissions at all* could bind it, complete the handshake, call `onAppCreate` to force an
authenticated MQTT connection to your broker, fetch the template, and invoke the grid
item's `OnClickDelegate` over the binder — opening the gate, with nothing drawn on screen
and nothing in the shade.

**A poisoned timestamp froze the state permanently.** `onMessage` only applied its
max-timestamp rule to *retained* messages; a live one set `newestTs` unconditionally. One
publish stamped in the future — NTP skew on the bridge, or anyone who could reach a
plaintext broker — and every subsequent message, live or retained, looked older and was
dropped for the rest of the process lifetime. The app kept showing a stale state while
looking perfectly healthy. Now future stamps are rejected outright and the monotonic guard
applies to live messages too (they still win *ties*, which is what lets a live message
override a retained one carrying the same one-second stamp).

**The arrival pop-up never actually checked the gate.** `awaitState` waited for
`state != unknown`, but the state flow kept its last value across disconnects — so it
returned instantly from memory without a byte crossing the network. The pop-up would
announce "gate: closed" with total confidence, reporting whatever the gate had been doing
when you last opened the app. `disconnect()` now resets the state, and the method is named
`awaitFreshState` because that is the property that matters.

## 3a. Accepted residual risks

Three holes are knowingly left open, because closing them would cost the feature they
protect. All are recorded here so a future session does not "discover" them and quietly
break the product to fix them.

**A notification listener can fire the gate command.** The heads-up notification carries a
`PendingIntent` to `GateCommandReceiver`. Any app granted *notification access* — a
permission routinely handed to smartwatch companions, automation apps and
notification-history apps — can read `sbn.notification.actions[0].actionIntent` and
`send()` it verbatim, extras and all. `FLAG_IMMUTABLE` does not help: the attacker replays
our intent rather than editing it, so a nonce would be replayed with it.
`Action.setAuthenticationRequired(true)` does not help either — SystemUI enforces that for
taps it renders, not for a direct `send()`.

*Mitigated, not closed:* `GateCommandReceiver.refuseWhileLocked` blocks it whenever the
device is locked and secured, which covers the phone-in-a-pocket case.

*Why not closed:* the only complete fix is to make the action open a confirmation screen
instead of carrying the command. That deletes the one-tap-from-Maps behaviour that M5 and
M6 exist to deliver. **Decision (Artur, 2026-07-23): keep one-tap, accept the residual.**
It requires the user to have installed something malicious *and* granted it notification
access.

**Cleartext HTTP is permitted app-wide** (`res/xml/network_security_config.xml`,
2026-07-24), for the *optional* HTTP snapshot override only. API 28+ blocks cleartext by
default, and the block cannot be lifted for a host list because **every address in this app
is typed by the user at runtime** — which is the whole point of §1. So it is lifted globally.

*What is actually exposed:* nothing, on a default install. The camera's own path is RTSP,
which is a raw socket this policy never governed, and the snapshot field is empty unless the
user deliberately fills it. When they do: one GET per interval to an address they chose,
with credentials they typed, normally inside their own VPN. Gate **commands** never travel
this way — they are MQTT, with its own TLS switch — so the thing worth protecting is not on
this path at all.

*Why not closed:* the alternatives are worse. Requiring `https://` would exclude essentially
every consumer IP camera and most homelab restreamers, making the override useless; a pinned
host list would put a deployment address back in the APK, undoing §1. The config permits
cleartext, it does not require it — an `https://` URL works unchanged.

**One binder-level click on the car screen moves the gate.** `GridItem`'s click listener
sends the command with no confirmation step, so a validator misconfiguration is
immediately exploitable rather than merely dangerous. The host validator (§3) is the real
control and is correct; a confirmation template would be defence in depth. Car app quality
rule IT-1 explicitly permits one-touch on/off control while driving, so the confirmation
is a genuine trade rather than an obvious win. Parked in the ch. 10 backlog.

## 4. Release build

```bash
cd app
cp keystore.properties.example keystore.properties   # then edit it
keytool -genkeypair -v -keystore upload.jks -keyalg RSA -keysize 4096 \
        -validity 10000 -alias domofon-upload
./gradlew bundleRelease
```

`keystore.properties` and `*.jks` are gitignored. **Back up `upload.jks` off this
machine.** With Play App Signing the app signing key is Google's and recoverable; the
upload key is not, and losing it means losing the ability to publish updates.

### R8 traps specific to this project

- **Resource shrinking is off, and must stay off** until you add `res/raw/keep.xml`.
  `QtLoader` resolves its bootstrap data by *name* via `Resources.getIdentifier()` —
  `qt_libs`, `load_local_libs`, `bundled_libs`, `use_local_qt_libs`,
  `bundle_local_qt_libs`, `system_libs_prefix`, `fatal_error_msg`. The shrinker sees them
  unreferenced and strips them; the app then dies at launch. Keep rules do not help.
- **The HiveMQ keep rules use the shaded prefix.** HiveMQ's own Android documentation
  gives rules for `io.netty.**`, which is correct for the plain artifact and a silent
  no-op for the `-shaded` one this project uses — everything there lives under
  `com.hivemq.client.internal.shaded.io.netty`.
- **`androidx.car.app` needs no rules from us.** Both AARs ship consumer rules keeping
  everything annotated `@KeepFields`, which is what the host's by-name binder
  serialization requires. A blanket keep would only defeat shrinking.
- **Always re-test the release build on device.** Qt and Netty both resolve classes
  reflectively; debug working proves nothing about release.

### Manifest hygiene

The Qt AAR declares `WRITE_EXTERNAL_STORAGE`, and the merger then *implies*
`READ_EXTERNAL_STORAGE` from it. Both are removed with `tools:node="remove"` — removing
only WRITE leaves READ behind. Re-check after every Qt upgrade:

```bash
./gradlew :app:processReleaseMainManifest
grep -oE 'android:name="android.permission[^"]*"' \
  app/build/intermediates/**/release/**/AndroidManifest.xml
```

Expected: `INTERNET`, `POST_NOTIFICATIONS`, `ACCESS_FINE_LOCATION`,
`ACCESS_BACKGROUND_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`,
`HIDE_OVERLAY_WINDOWS` (install-time, tapjacking guard — see ch. 10). Nothing else.

## 5. Play Console runbook

Ordered by what blocks what.

1. **Confirm the account type.** Personal accounts registered after Nov 2023 must run a
   *closed* test with **12 testers opted in for 14 continuous days** before production
   access is granted. Internal testing does not count. This is the critical path — start
   it before polishing anything.
2. **Privacy policy.** Mandatory here (sensitive permissions). Active URL, not a PDF, not
   editable. GitHub Pages on this repo works. Link it in the listing *and* in the app.
3. **Data Safety.** Location and credentials never leave the device, and Google's own
   definition is that "collect" means transmitting off-device — so declare **not collected,
   not shared**. Mandatory even when the answer is "nothing".
4. **App access.** A reviewer has neither your broker nor your VPN, so the app looks
   permanently broken to them — this is the most likely rejection. Provide a reachable test
   broker plus exact steps in *App access*. A demo mode that simulates gate state without a
   broker would remove the failure class entirely; it is the single highest-value thing
   left to build.
5. **Background location declaration.** Permissions declaration form + a ≤30 s demo video
   showing the disclosure dialog, the permission prompt, and the feature working from the
   background. The disclosure must precede the prompt — `SettingsActivity.startLocationFlow`
   does this. Frame the declared feature around driving: *"notifies the user as they
   approach their gate so they can open it without handling the phone while driving"*.
   Expect pushback on "could this work with foreground access"; the geofencing API
   requiring `ACCESS_FINE_LOCATION` + `ACCESS_BACKGROUND_LOCATION` is the counter-argument.
6. **Android Auto form factor.** Add it under *Advanced settings → Form factors*, then
   release to a testing track. The car app gets a **separate manual review**, blocking for
   open testing and production. IOT must meet Tier 2 ("Car Optimized"). Note IT-1: setup
   must not be reachable from the car screen — `GateScreen` shows a message and points at
   the phone, deliberately.
7. **Remaining declarations:** Ads (none), Content rating, Target audience (adults only —
   including children pulls in Families policy, which background location will fail).

### If you add a foreground service later

docs/06 sketches a 15-minute foreground service on geofence entry. Do **not** declare it
with `foregroundServiceType="location"`: Play removed geofencing as an approved
location-FGS use case in August 2026. The service would be keeping an MQTT connection
alive, so `connectedDevice` is the honest type — its definition explicitly covers
"interactions with external devices that require a … network connection". It needs one of
`CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `NFC` / `TRANSMIT_IR` in the manifest, plus
a Play Console declaration with its own demo video.

## 6. Acceptance tests

- [ ] Release APK contains no broker host, username, password or coordinate (§1).
- [ ] Fresh install opens `SettingsActivity`, not a dead QML screen.
- [ ] Settings survive force-stop; `adb shell run-as pl.bitforge.domofon` shows the stored
      password as ciphertext, not plaintext.
- [ ] Release build on the DHU **refuses** to connect (the DHU is an unknown host); debug
      build still works. That asymmetry is the proof the host validator is live.
- [ ] Lock the phone, trigger a state change: body hidden, Open action demands unlock.
- [ ] Fresh install requests no location until the geofence toggle is switched on, and the
      disclosure dialog appears *before* the system prompt.
- [ ] Every M2/M4/M5/M6 acceptance test re-run against the **release** build.
