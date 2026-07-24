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
    const val K_ERROR = "topics.error"
    const val K_NODE_ID = "topics.nodeId"
    const val K_PAYLOAD_KEY = "topics.payloadKey"

    const val K_QOS_STATE = "mqtt.qosState"
    const val K_QOS_COMMAND = "mqtt.qosCommand"
    const val K_QOS_AVAILABILITY = "mqtt.qosAvailability"
    const val K_KEEP_ALIVE = "mqtt.keepAliveSeconds"

    const val K_GEOFENCE = "home.enabled"
    const val K_LAT = "home.latitude"
    const val K_LON = "home.longitude"
    const val K_RADIUS = "home.radiusMeters"

    const val K_SNAPSHOT_URL = "camera.snapshotUrl"
    const val K_RTSP_URL = "camera.rtspUrl"
    const val K_SNAPSHOT_SECS = "camera.snapshotSecs"
    const val K_AUDIO_ENABLED = "camera.audioEnabled"

    const val K_REQUIRE_UNLOCK = "security.requireUnlock"

    /**
     * Keys whose values are Keystore-encrypted at rest. Both camera URLs are here because
     * they carry the camera credentials inline (`rtsp://user:pass@host/…`) — there are no
     * separate username/password fields for either.
     */
    private val SECRET_KEYS = setOf(K_PASS, K_SNAPSHOT_URL, K_RTSP_URL)

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
                // Trimmed like the host above. A trailing space from the soft keyboard's
                // autocomplete is invisible in the settings row and rejected by the broker,
                // which reads as "the same credentials work in mosquitto_sub but not here".
                username = str(K_USER, "").trim(),
                password = secret(K_PASS),
                clientId = str(K_CLIENT_ID, ""),
            ),
            topics = DomofonConfig.Topics(
                rxPrefix = prefix(K_RX_PREFIX, DomofonConfig.Defaults.RX_PREFIX),
                txPrefix = prefix(K_TX_PREFIX, DomofonConfig.Defaults.TX_PREFIX),
                availability = str(K_AVAILABILITY, DomofonConfig.Defaults.AVAILABILITY).trim(),
                error = str(K_ERROR, DomofonConfig.Defaults.ERROR).trim(),
                nodeId = int(K_NODE_ID, DomofonConfig.Defaults.NODE_ID),
                payloadKey = str(K_PAYLOAD_KEY, DomofonConfig.Defaults.PAYLOAD_KEY),
            ),
            // Clamped here, not at the point of use: a hand-edited or corrupted value must
            // become a legal one before anything downstream (the HiveMQ builder throws on
            // an out-of-range keepAlive; MqttQos.fromCode returns null) can see it.
            mqtt = DomofonConfig.Mqtt(
                qosState = int(K_QOS_STATE, DomofonConfig.Defaults.QOS).coerceIn(0, 2),
                qosCommand = int(K_QOS_COMMAND, DomofonConfig.Defaults.QOS).coerceIn(0, 2),
                qosAvailability = int(K_QOS_AVAILABILITY, DomofonConfig.Defaults.QOS).coerceIn(0, 2),
                keepAliveSeconds = int(K_KEEP_ALIVE, DomofonConfig.Defaults.KEEP_ALIVE_S)
                    .coerceIn(0, 65_535),
            ),
            home = DomofonConfig.Home(
                enabled = prefs.getBoolean(K_GEOFENCE, false),
                latitude = str(K_LAT, "").toDoubleOrNull(),
                longitude = str(K_LON, "").toDoubleOrNull(),
                radiusMeters = str(K_RADIUS, "").toFloatOrNull() ?: DomofonConfig.Defaults.RADIUS_M,
            ),
            camera = DomofonConfig.Camera(
                snapshotUrl = secret(K_SNAPSHOT_URL).trim(),
                rtspUrl = secret(K_RTSP_URL).trim(),
                // Clamped here for the same reason as the mqtt fields: a hand-edited 0 would
                // busy-loop the grabber, and a huge value is harmless but pointless.
                snapshotSecs = int(K_SNAPSHOT_SECS, DomofonConfig.Defaults.SNAPSHOT_SECS)
                    .coerceIn(1, 300),
                audioEnabled = prefs.getBoolean(K_AUDIO_ENABLED, DomofonConfig.Defaults.AUDIO_ENABLED),
            ),
            requireUnlockForCommands = prefs.getBoolean(K_REQUIRE_UNLOCK, true),
        )
    }

    private fun str(key: String, fallback: String): String =
        prefs.getString(key, fallback) ?: fallback

    /**
     * A topic prefix, guaranteed to end in `/`.
     *
     * [GateRepository] builds a topic by concatenating this with a bare signal name, so a
     * prefix typed as `hc12/rx` (which is how a person naturally writes it, and how the
     * settings row renders it back) silently produces `hc12/rxGateOpened`. The broker
     * accepts the subscription and the publish, nothing ever matches, and both the gate
     * state and every command vanish with no error anywhere. Normalising on read fixes it
     * for a value already stored, not just for the next one typed.
     */
    private fun prefix(key: String, fallback: String): String {
        val value = str(key, fallback).trim()
        if (value.isEmpty()) return fallback
        return if (value.endsWith('/')) value else "$value/"
    }

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
