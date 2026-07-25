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
coordinates, the geofence never sees the broker password. `config.wire` is the comparable
value whose change forces a reconnect.

## Invariants

1. **`ConfigKeys` strings must equal the `android:key` attributes in
   `res/xml/preferences.xml` — there is no compile-time check across that boundary.**
   Change one, change both. (`status.connection` and `about.version` in the XML are
   UI-only rows, not config keys.)
2. **All validation lives in the parser, on read.** Clamps (QoS 0–2, keepAlive 0–65535,
   snapshot 1–300 s), numeric-string parsing (EditTextPreference stores strings), trims
   (a soft-keyboard trailing space is invisible in the row and rejected by the broker),
   and **topic-prefix normalisation to a trailing `/`** — `hc12/rx` typed naturally would
   silently produce `hc12/rxGateOpened`, which the broker happily accepts and nothing ever
   matches.
3. **Secrets (`broker.password`, both camera URLs — credentials ride inline in them) are
   Keystore-encrypted at rest** and transparently decrypted on read. The Keystore alias
   `domofon.config.secrets.v1` is load-bearing: renaming it orphans every stored value.
   Decryption failure (lock-screen change drops app keys) reads as "unset", not an error.
4. **SharedPreferences, not DataStore** — [decision D8](../architecture/decisions.md):
   receivers need synchronous reads inside a ~10 s goAsync budget.
5. **The client id is random per install**, generated on first run. Never derive it from
   the device ([dead ends](../architecture/decisions.md#recorded-dead-ends)).
6. **`home.isUsable` treats null-island (0,0) as unset** — it is what a half-filled form
   produces, and a geofence there would silently never fire.
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

## Adding a setting

1. Key in `ConfigKeys` (+ `SECRETS` if it must be encrypted at rest).
2. Field + default in `DomofonConfig` / `Defaults`; parse + validate in
   `DomofonConfigParser` (add a `DomofonConfigParserTest` case).
3. Row in `res/xml/preferences.xml` with the *same* key string.
4. If it affects the connection, decide whether it belongs in `Wire`.
5. Input handling in `SettingsFragment` if it is a text field.

## Related pages

[security](../architecture/security.md) · [gate](gate.md) ·
[app-container](app-container.md)
