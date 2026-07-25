package pl.bitforge.domofon.config

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The single source of configuration, and the only thing that touches persistent settings.
 * One instance, owned by the AppContainer; everything else receives it.
 *
 * `SharedPreferences` rather than DataStore, on purpose. Every consumer here is either a
 * `BroadcastReceiver` that needs a value *now* (`GeofenceReceiver` has ~10 s of `goAsync`
 * budget and no room for a suspending read) or the preference framework, which is
 * synchronous by design. DataStore's async API would have to be bridged back to blocking
 * at both ends; the file is a few hundred bytes in app-private storage, already covered by
 * file-based encryption, with the two real secrets Keystore-encrypted on top.
 *
 * Parsing and validation live in [DomofonConfigParser]; keys in [ConfigKeys]; encryption
 * in [SecretStore]. This class owns the file, the change listener and the flow — plus the
 * write-through path the settings screen uses via [ConfigPreferenceDataStore].
 */
class ConfigStore(context: Context, private val secrets: SecretStore) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(DomofonConfig.EMPTY)

    /** Emits on every settings change, so the QML view and the car screen re-read live. */
    val config: StateFlow<DomofonConfig> = _config.asStateFlow()

    /** Synchronous snapshot, for receivers and other callers with no coroutine to hand. */
    val current: DomofonConfig get() = _config.value

    /** SharedPreferences adapted to what the parser wants: secrets arrive decrypted. */
    private val raw = object : RawPrefs {
        override fun string(key: String): String? {
            val stored = prefs.getString(key, null) ?: return null
            return if (key in ConfigKeys.SECRETS) secrets.decrypt(stored) else stored
        }

        override fun boolean(key: String, fallback: Boolean): Boolean =
            prefs.getBoolean(key, fallback)
    }

    // Strong reference: SharedPreferences keeps only a weak one, and a collected listener
    // is a bug that shows up as "settings changes stop taking effect after a while".
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _config.value = DomofonConfigParser.parse(raw)
    }

    init {
        ensureClientId()
        _config.value = DomofonConfigParser.parse(raw)
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    /**
     * A stable, random per-install MQTT client id, generated on first run.
     *
     * Not derived from the device or the user: an id that collides makes two clients evict
     * each other from the broker in a reconnect loop, and an id derived from `Build.MODEL`
     * collides for every user with the same phone.
     */
    private fun ensureClientId() {
        if (prefs.getString(ConfigKeys.CLIENT_ID, "").isNullOrBlank()) {
            prefs.edit()
                .putString(ConfigKeys.CLIENT_ID, "domofon-" + UUID.randomUUID().toString().take(12))
                .apply()
        }
    }

    // --- write-through: the settings screen reaches these via ConfigPreferenceDataStore --

    fun putString(key: String, value: String?) {
        val stored = if (key in ConfigKeys.SECRETS) secrets.encrypt(value.orEmpty()) else value
        prefs.edit().putString(key, stored).apply()
    }

    fun getString(key: String, defValue: String?): String? =
        if (key in ConfigKeys.SECRETS) raw.string(key) ?: "" else prefs.getString(key, defValue)

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    private companion object {
        const val FILE = "domofon.config"
    }
}
