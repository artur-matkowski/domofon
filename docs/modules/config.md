# Module: config (domain/config + data/config)

*[Wiki home](../README.md) › modules › config*

## Responsibility

Every setting the app has — schema, validation, storage, encryption, and the settings
screen's write path. Nothing deployment-specific is compiled in
([decision D6](../architecture/decisions.md)).

| Class | Owns |
|---|---|
| `domain/config/DomofonConfig` | The immutable config value type + `Defaults`; redacting `toString()`s |
| `domain/config/ConfigKeys` | The preference key strings + `SECRETS` set |
| `domain/config/DomofonConfigParser` (+ `RawPrefs`) | Raw strings → valid config: trims, clamps, prefix normalisation |
| `data/config/ConfigStore` | The SharedPreferences file, the change listener, `config: StateFlow` + `current`, secret-aware write-through |
| `data/config/SecretStore` | AES-256/GCM via Android Keystore for the three secret keys |
| `data/config/ConfigPreferenceDataStore` | Thin `PreferenceDataStore` adapter for the XML settings screen |
| `ui/settings/SettingsActivity` | The whole settings UI + the two-step location permission flow |

## Public API

```kotlin
class ConfigStore(context, secrets: SecretStore) {
    val config: StateFlow<DomofonConfig>   // emits on every settings change
    val current: DomofonConfig             // synchronous snapshot — receivers need it *now*
    fun putString/getString/putBoolean/getBoolean  // write-through for the adapter
}
```

Slices: consumers take exactly the piece they need (`config.broker`, `.topics`, `.mqtt`,
`.home`, `.camera`, `.requireUnlockForCommands`); the MQTT layer never sees the home
coordinates, the geofence never sees the broker password.

**Two comparable values drive restarts**, and they are the same idea applied twice:

| Value | Whose restart | Deliberately excludes |
|---|---|---|
| `config.wire` | the MQTT connection | everything but broker/topics/mqtt |
| `config.camera.feed` | the camera source | `camera.snapshotSecs` |

`camera.feed` also resolves the selected path down to just its own fields (`CameraFeed.Rtsp`
or `.Http`), so no code below `ConfigStore` can read the *other* path's URLs or has to ask
which path it is on. It is `null` when the selected path's URL is blank, which is the whole of
`hasPicture`.

## Invariants

1. **`ConfigKeys` strings must equal the `android:key` attributes in
   `res/xml/preferences.xml` — there is no compile-time check across that boundary.**
   Change one, change both. (`status.connection`, `status.geofence` and `about.version` in
   the XML are UI-only rows, not config keys — `status.geofence` is `persistent="false"` and
   its summary is filled from `GeofenceStatusStore`, which is a *separate* prefs file: it is
   observed state, not settings, and routing it through here would emit on `config` every
   time the fence re-registered.)
2. **All validation lives in the parser, on read.** Clamps (QoS 0–2, keepAlive 0–65535,
   snapshot 1–300 s), numeric-string parsing (EditTextPreference stores strings), trims
   (a soft-keyboard trailing space is invisible in the row and rejected by the broker),
   and **topic-prefix normalisation to a trailing `/`** — `hc12/rx` typed naturally would
   silently produce `hc12/rxGateOpened`, which the broker happily accepts and nothing ever
   matches.
3. **Secrets (`broker.password` and all three camera URLs — credentials ride inline in them)
   are Keystore-encrypted at rest** and transparently decrypted on read. The Keystore alias
   `domofon.config.secrets.v1` is load-bearing: renaming it orphans every stored value.
   Decryption failure (lock-screen change drops app keys) reads as "unset", not an error.
4. **SharedPreferences, not DataStore** — [decision D8](../architecture/decisions.md):
   receivers need synchronous reads inside a ~10 s goAsync budget.
5. **The client id is random per install**, generated on first run. Never derive it from
   the device ([dead ends](../architecture/decisions.md#recorded-dead-ends)).
6. **`home.isUsable` treats null-island (0,0) as unset** — it is what a half-filled form
   produces, and a geofence there would silently never fire.
   `home.inAppFence` is the opt-in second arrival trigger
   ([D13](../architecture/decisions.md)); it defaults **off**, depends on `home.enabled` in
   the XML, and needs no permission of its own — it reuses the coordinates and the grant the
   geofence already required. It is deliberately *not* part of `isUsable`: it changes which
   triggers run, never whether there is a usable home position.
7. **`SecretStore.key()` is synchronized and must stay so**: two threads racing first-run
   key generation both call `generateKey()`, the second silently *overwrites* the entry,
   and whatever the first encrypted fails its GCM tag forever.

## Gotchas

- Default broker port follows the TLS switch (1883/8883) *only while the port field is
  unset*.
- `SettingsActivity` holds a `ConnectionLease("settings")` while visible so the status row
  is a live credential test; its config collector calls `GateService.refresh()` on every
  change (`drop(1)` — the current value is what it already connected with).
- Input-type handling in the settings rows is deliberate (no autocorrect on
  host/username, masked passwords, URI inputs whose summaries strip userinfo) — see the
  KDoc in `SettingsActivity`.
- **`ConfigPreferenceDataStore` overrides only `put/getString` and `put/getBoolean`.** Every
  other method on the `PreferenceDataStore` base throws `UnsupportedOperationException`, so a
  `SeekBarPreference` (`getInt`) or `MultiSelectListPreference` (`getStringSet`) row crashes at
  *inflation*. `ListPreference` and `DropDownPreference` persist a `String` and are safe as-is
  — which is why the camera source selector is one.
- **Camera rows are shown and hidden, not disabled.** `CameraSettingsRows` (in `domain/`, so
  it is testable) says which keys belong to which source; `SettingsFragment` assigns
  `isVisible` over `ALL` in one loop. The `home.*` rows use `app:dependency` instead, which
  greys out — right for "this depends on that being on", wrong here, because the other path's
  URL is not disabled, it is irrelevant.
- **A `setOnPreferenceChangeListener` fires *before* the value is persisted.** The source
  selector must therefore act on its `newValue` argument; re-reading the config there applies
  the previous selection and leaves the screen one change behind for good.
- Only two things observe `config` reactively: `GateViewModel` (rendering) and, since the
  camera source became switchable, `CameraFrameGrabber`. Everything else pulls
  `configStore.current` at a deliberately chosen moment. A new setting that must take effect
  without leaving the screen has to say so explicitly.

## Adding a setting

1. Key in `ConfigKeys` (+ `SECRETS` if it must be encrypted at rest).
2. Field + default in `DomofonConfig` / `Defaults`; parse + validate in
   `DomofonConfigParser` (add a `DomofonConfigParserTest` case). Enum-valued settings get a
   garbage-tolerant `of(raw)` resolver — a hand-edited file must never make the app unusable.
3. Row in `res/xml/preferences.xml` with the *same* key string; entries in `arrays.xml` if it
   is a list. Anything other than EditText/Switch/List needs the matching data-store
   overrides first — see the gotcha above.
4. If it affects the connection, decide whether it belongs in `Wire`. If it affects the
   camera, decide whether it belongs in `CameraFeed` (i.e. should it reopen the session).
5. Input handling in `SettingsFragment` if it is a text field, and add it to
   `CameraSettingsRows` if it is a camera row that only one source uses.

## Related pages

[security](../architecture/security.md) · [gate](gate.md) ·
[app-container](app-container.md)
