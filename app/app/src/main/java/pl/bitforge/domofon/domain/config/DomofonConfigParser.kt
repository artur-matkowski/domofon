package pl.bitforge.domofon.domain.config

/**
 * Preference keys — the contract between [ConfigStore], the parser, and
 * `res/xml/preferences.xml`, whose `android:key` attributes must carry these exact
 * strings. There is no compile-time check across that boundary: change one here and you
 * must change the XML with it.
 */
object ConfigKeys {
    const val HOST = "broker.host"
    const val PORT = "broker.port"
    const val TLS = "broker.tls"
    const val USER = "broker.username"
    const val PASS = "broker.password"
    const val CLIENT_ID = "broker.clientId"

    const val RX_PREFIX = "topics.rxPrefix"
    const val TX_PREFIX = "topics.txPrefix"
    const val AVAILABILITY = "topics.availability"
    const val ERROR = "topics.error"
    const val NODE_ID = "topics.nodeId"
    const val PAYLOAD_KEY = "topics.payloadKey"

    const val QOS_STATE = "mqtt.qosState"
    const val QOS_COMMAND = "mqtt.qosCommand"
    const val QOS_AVAILABILITY = "mqtt.qosAvailability"
    const val KEEP_ALIVE = "mqtt.keepAliveSeconds"

    const val GEOFENCE = "home.enabled"
    const val LAT = "home.latitude"
    const val LON = "home.longitude"
    const val RADIUS = "home.radiusMeters"

    const val SOURCE = "camera.source"
    const val SNAPSHOT_URL = "camera.snapshotUrl"
    const val RTSP_URL = "camera.rtspUrl"
    const val AUDIO_URL = "camera.audioUrl"
    const val SNAPSHOT_SECS = "camera.snapshotSecs"
    const val AUDIO_ENABLED = "camera.audioEnabled"

    const val REQUIRE_UNLOCK = "security.requireUnlock"

    /**
     * Keys whose values are Keystore-encrypted at rest. Every camera URL is here because
     * they carry the camera credentials inline (`rtsp://user:pass@host/…`) — there are no
     * separate username/password fields for any of them.
     */
    val SECRETS = setOf(PASS, SNAPSHOT_URL, RTSP_URL, AUDIO_URL)
}

/**
 * What the parser reads from — the raw stored strings, with secrets already decrypted.
 * [ConfigStore] adapts SharedPreferences to this; tests hand in a map.
 */
interface RawPrefs {
    /** The stored value for [key], or null when unset. */
    fun string(key: String): String?

    fun boolean(key: String, fallback: Boolean): Boolean
}

/**
 * Raw stored preferences to a valid [DomofonConfig] — all validation in one place.
 *
 * Clamping happens here, not at the point of use: a hand-edited or corrupted value must
 * become a legal one before anything downstream (the HiveMQ builder throws on an
 * out-of-range keepAlive; `MqttQos.fromCode` returns null) can see it. Numeric fields
 * arrive as strings because EditTextPreference stores everything as `String`.
 */
object DomofonConfigParser {

    fun parse(prefs: RawPrefs): DomofonConfig {
        val tls = prefs.boolean(ConfigKeys.TLS, false)
        return DomofonConfig(
            broker = DomofonConfig.Broker(
                host = str(prefs, ConfigKeys.HOST, "").trim(),
                port = int(
                    prefs, ConfigKeys.PORT,
                    if (tls) DomofonConfig.Defaults.PORT_TLS else DomofonConfig.Defaults.PORT_PLAIN,
                ),
                tls = tls,
                // Trimmed like the host above. A trailing space from the soft keyboard's
                // autocomplete is invisible in the settings row and rejected by the broker,
                // which reads as "the same credentials work in mosquitto_sub but not here".
                username = str(prefs, ConfigKeys.USER, "").trim(),
                password = str(prefs, ConfigKeys.PASS, ""),
                clientId = str(prefs, ConfigKeys.CLIENT_ID, ""),
            ),
            topics = DomofonConfig.Topics(
                rxPrefix = prefix(prefs, ConfigKeys.RX_PREFIX, DomofonConfig.Defaults.RX_PREFIX),
                txPrefix = prefix(prefs, ConfigKeys.TX_PREFIX, DomofonConfig.Defaults.TX_PREFIX),
                availability = str(prefs, ConfigKeys.AVAILABILITY, DomofonConfig.Defaults.AVAILABILITY).trim(),
                error = str(prefs, ConfigKeys.ERROR, DomofonConfig.Defaults.ERROR).trim(),
                nodeId = int(prefs, ConfigKeys.NODE_ID, DomofonConfig.Defaults.NODE_ID),
                payloadKey = str(prefs, ConfigKeys.PAYLOAD_KEY, DomofonConfig.Defaults.PAYLOAD_KEY),
            ),
            mqtt = DomofonConfig.Mqtt(
                qosState = int(prefs, ConfigKeys.QOS_STATE, DomofonConfig.Defaults.QOS).coerceIn(0, 2),
                qosCommand = int(prefs, ConfigKeys.QOS_COMMAND, DomofonConfig.Defaults.QOS).coerceIn(0, 2),
                qosAvailability = int(prefs, ConfigKeys.QOS_AVAILABILITY, DomofonConfig.Defaults.QOS)
                    .coerceIn(0, 2),
                keepAliveSeconds = int(prefs, ConfigKeys.KEEP_ALIVE, DomofonConfig.Defaults.KEEP_ALIVE_S)
                    .coerceIn(0, 65_535),
            ),
            home = DomofonConfig.Home(
                enabled = prefs.boolean(ConfigKeys.GEOFENCE, false),
                latitude = prefs.string(ConfigKeys.LAT)?.toDoubleOrNull(),
                longitude = prefs.string(ConfigKeys.LON)?.toDoubleOrNull(),
                radiusMeters = prefs.string(ConfigKeys.RADIUS)?.toFloatOrNull()
                    ?: DomofonConfig.Defaults.RADIUS_M,
            ),
            camera = DomofonConfig.Camera(
                // Unrecognised values fall back to RTSP rather than throwing: this is the
                // one setting that decides whether any picture appears at all, and a
                // stored typo must not leave the panel permanently blank.
                source = CameraSource.of(prefs.string(ConfigKeys.SOURCE)),
                snapshotUrl = str(prefs, ConfigKeys.SNAPSHOT_URL, "").trim(),
                rtspUrl = str(prefs, ConfigKeys.RTSP_URL, "").trim(),
                audioUrl = str(prefs, ConfigKeys.AUDIO_URL, "").trim(),
                // Clamped for the same reason as the mqtt fields: a hand-edited 0 would
                // busy-loop the grabber, and a huge value is harmless but pointless.
                snapshotSecs = int(prefs, ConfigKeys.SNAPSHOT_SECS, DomofonConfig.Defaults.SNAPSHOT_SECS)
                    .coerceIn(1, 300),
                audioEnabled = prefs.boolean(ConfigKeys.AUDIO_ENABLED, DomofonConfig.Defaults.AUDIO_ENABLED),
            ),
            requireUnlockForCommands = prefs.boolean(ConfigKeys.REQUIRE_UNLOCK, true),
        )
    }

    private fun str(prefs: RawPrefs, key: String, fallback: String): String =
        prefs.string(key) ?: fallback

    /**
     * A topic prefix, guaranteed to end in `/`.
     *
     * A topic is built by concatenating this with a bare signal name, so a prefix typed as
     * `hc12/rx` (which is how a person naturally writes it, and how the settings row
     * renders it back) silently produces `hc12/rxGateOpened`. The broker accepts the
     * subscription and the publish, nothing ever matches, and both the gate state and every
     * command vanish with no error anywhere. Normalising on read fixes it for a value
     * already stored, not just for the next one typed.
     */
    private fun prefix(prefs: RawPrefs, key: String, fallback: String): String {
        val value = str(prefs, key, fallback).trim()
        if (value.isEmpty()) return fallback
        return if (value.endsWith('/')) value else "$value/"
    }

    private fun int(prefs: RawPrefs, key: String, fallback: Int): Int =
        prefs.string(key)?.trim()?.toIntOrNull() ?: fallback
}
