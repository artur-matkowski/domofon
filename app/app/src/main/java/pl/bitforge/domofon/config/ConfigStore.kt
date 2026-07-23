package pl.bitforge.domofon.config

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * The single source of configuration, and the only thing that touches persistent settings.
 *
 * It is deliberately a [PreferenceDataStore] as well as a store: that is the seam
 * `androidx.preference` offers for putting a custom backend behind a preference screen, so
 * [SettingsActivity] gets to be a plain XML `PreferenceFragmentCompat` while passwords are
 * still transparently encrypted on the way in and out (see [SecretStore]).
 *
 * `SharedPreferences` rather than DataStore, on purpose. Every consumer here is either a
 * `BroadcastReceiver` that needs a value *now* (`GeofenceReceiver` has ~10 s of `goAsync`
 * budget and no room for a suspending read) or the preference framework, which is
 * synchronous by design. DataStore's async API would have to be bridged back to blocking
 * at both ends; the file is a few hundred bytes in app-private storage, already covered by
 * file-based encryption, with the two real secrets Keystore-encrypted on top.
 */
object ConfigStore : PreferenceDataStore() {

    private const val FILE = "domofon.config"

    // Preference keys. These strings are also the android:key values in res/xml/preferences.xml
    // — change one and you must change both.
    const val K_HOST = "broker.host"
    const val K_PORT = "broker.port"
    const val K_TLS = "broker.tls"
    const val K_USER = "broker.username"
    const val K_PASS = "broker.password"
    private const val K_CLIENT_ID = "broker.clientId"

    const val K_RX_PREFIX = "topics.rxPrefix"
    const val K_TX_PREFIX = "topics.txPrefix"
    const val K_AVAILABILITY = "topics.availability"
    const val K_NODE_ID = "topics.nodeId"
    const val K_PAYLOAD_KEY = "topics.payloadKey"

    const val K_GEOFENCE = "home.enabled"
    const val K_LAT = "home.latitude"
    const val K_LON = "home.longitude"
    const val K_RADIUS = "home.radiusMeters"

    const val K_RTSP_URL = "camera.rtspUrl"
    const val K_RTSP_USER = "camera.rtspUsername"
    const val K_RTSP_PASS = "camera.rtspPassword"

    const val K_REQUIRE_UNLOCK = "security.requireUnlock"

    /** Keys whose values are Keystore-encrypted at rest. */
    private val SECRET_KEYS = setOf(K_PASS, K_RTSP_PASS)

    private lateinit var prefs: SharedPreferences

    private val _config = MutableStateFlow(DomofonConfig.EMPTY)

    /** Emits on every settings change, so the QML view and the car screen re-read live. */
    val config: StateFlow<DomofonConfig> = _config.asStateFlow()

    /** Synchronous snapshot, for receivers and other callers with no coroutine to hand. */
    val current: DomofonConfig get() = _config.value

    // Strong reference: SharedPreferences keeps only a weak one, and a collected listener
    // is a bug that shows up as "settings changes stop taking effect after a while".
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _config.value = read()
    }

    /** Call once from `Application.onCreate` — before any component can read [current]. */
    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        ensureClientId()
        _config.value = read()
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
        if (prefs.getString(K_CLIENT_ID, "").isNullOrBlank()) {
            prefs.edit()
                .putString(K_CLIENT_ID, "domofon-" + UUID.randomUUID().toString().take(12))
                .apply()
        }
    }

    private fun read(): DomofonConfig {
        val tls = prefs.getBoolean(K_TLS, false)
        return DomofonConfig(
            broker = DomofonConfig.Broker(
                host = str(K_HOST, "").trim(),
                port = int(K_PORT, if (tls) DomofonConfig.Defaults.PORT_TLS else DomofonConfig.Defaults.PORT_PLAIN),
                tls = tls,
                username = str(K_USER, ""),
                password = secret(K_PASS),
                clientId = str(K_CLIENT_ID, ""),
            ),
            topics = DomofonConfig.Topics(
                rxPrefix = str(K_RX_PREFIX, DomofonConfig.Defaults.RX_PREFIX),
                txPrefix = str(K_TX_PREFIX, DomofonConfig.Defaults.TX_PREFIX),
                availability = str(K_AVAILABILITY, DomofonConfig.Defaults.AVAILABILITY),
                nodeId = int(K_NODE_ID, DomofonConfig.Defaults.NODE_ID),
                payloadKey = str(K_PAYLOAD_KEY, DomofonConfig.Defaults.PAYLOAD_KEY),
            ),
            home = DomofonConfig.Home(
                enabled = prefs.getBoolean(K_GEOFENCE, false),
                latitude = str(K_LAT, "").toDoubleOrNull(),
                longitude = str(K_LON, "").toDoubleOrNull(),
                radiusMeters = str(K_RADIUS, "").toFloatOrNull() ?: DomofonConfig.Defaults.RADIUS_M,
            ),
            camera = DomofonConfig.Camera(
                rtspUrl = str(K_RTSP_URL, "").trim(),
                rtspUsername = str(K_RTSP_USER, ""),
                rtspPassword = secret(K_RTSP_PASS),
            ),
            requireUnlockForCommands = prefs.getBoolean(K_REQUIRE_UNLOCK, true),
        )
    }

    private fun str(key: String, fallback: String): String =
        prefs.getString(key, fallback) ?: fallback

    /** Numeric fields come from EditTextPreference, so they are strings on disk. */
    private fun int(key: String, fallback: Int): Int =
        prefs.getString(key, null)?.trim()?.toIntOrNull() ?: fallback

    private fun secret(key: String): String =
        SecretStore.decrypt(prefs.getString(key, "") ?: "")

    // --- PreferenceDataStore: the settings screen writes through these -------------------

    override fun putString(key: String, value: String?) {
        val stored = if (key in SECRET_KEYS) SecretStore.encrypt(value.orEmpty()) else value
        prefs.edit().putString(key, stored).apply()
    }

    override fun getString(key: String, defValue: String?): String? =
        if (key in SECRET_KEYS) secret(key) else prefs.getString(key, defValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        prefs.getBoolean(key, defValue)
}
