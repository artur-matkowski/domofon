package pl.bitforge.domofon.config

/**
 * Everything about *this* installation that used to be a `const val` or a `BuildConfig`
 * field. Nothing in here may have a meaningful default that points at a real deployment —
 * the app ships to strangers now, and an APK that knows a gate's address is a liability.
 *
 * Read it through [ConfigStore]. The nested types exist so callers can take exactly the
 * slice they need: [GateRepository] never sees the home coordinates, [GeofenceManager]
 * never sees the broker password.
 */
data class DomofonConfig(
    val broker: Broker,
    val topics: Topics,
    val home: Home,
    val camera: Camera,
    /** Ask for device unlock before a notification button may move the gate. */
    val requireUnlockForCommands: Boolean,
) {

    /**
     * The one gate every entry point checks. Without a broker host there is nothing to
     * talk to, so the car screen, the geofence pop-up and the QML view all render an
     * unconfigured state rather than failing silently — silence looks identical to a bug.
     */
    val isComplete: Boolean get() = broker.host.isNotBlank()

    data class Broker(
        val host: String,
        val port: Int,
        val tls: Boolean,
        val username: String,
        val password: String,
        /**
         * Stable per install, generated once. Deriving this from `Build.MODEL` (as the
         * scaffold did) means every user on the same phone model shares an identifier,
         * and MQTT brokers evict the older session — two strangers would kick each other
         * offline in a loop forever.
         */
        val clientId: String,
    ) {
        /**
         * Redacted, because `data class` would otherwise print the password verbatim. One
         * `Log.d(TAG, "config: $config")` — or any exception message that interpolates a
         * config, or a crash report — would put the broker password in logcat, where adb
         * and anything holding READ_LOGS can read it.
         */
        override fun toString(): String =
            "Broker(host=<redacted>, port=$port, tls=$tls, username=<redacted>, password=<redacted>)"
    }

    /**
     * The hc12 radio bridge's topic scheme. Prefixes rather than whole topics: the signal
     * names themselves come from [GateProfile], which is a built-in default rather than
     * eighteen more text fields in the settings screen.
     */
    data class Topics(
        val rxPrefix: String,
        val txPrefix: String,
        val availability: String,
        /** The gate controller's node id on the radio; goes in the command payload. */
        val nodeId: Int,
        /** JSON key carrying [nodeId] in a command payload. */
        val payloadKey: String,
    )

    data class Home(
        val enabled: Boolean,
        val latitude: Double?,
        val longitude: Double?,
        val radiusMeters: Float,
    ) {
        /**
         * 0,0 is in the Atlantic. It is also what a half-filled settings form produces,
         * and registering a geofence there would silently never fire — so treat unset and
         * null-island as the same thing.
         */
        val isUsable: Boolean
            get() = enabled &&
                latitude != null && longitude != null &&
                latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
                !(latitude == 0.0 && longitude == 0.0) &&
                radiusMeters > 0f

        /** The coordinates are the user's home address; they do not belong in logcat. */
        override fun toString(): String =
            "Home(enabled=$enabled, set=${latitude != null && longitude != null}, r=$radiusMeters)"
    }

    data class Camera(
        val rtspUrl: String,
        val rtspUsername: String,
        val rtspPassword: String,
    ) {
        val isConfigured: Boolean get() = rtspUrl.isNotBlank()

        /** Same reasoning as [Broker.toString] — an RTSP URL often embeds credentials. */
        override fun toString(): String = "Camera(configured=$isConfigured)"
    }

    companion object {
        /** What a fresh install looks like: valid, inert, and pointing at nothing. */
        val EMPTY = DomofonConfig(
            broker = Broker(
                host = "",
                port = Defaults.PORT_PLAIN,
                tls = false,
                username = "",
                password = "",
                clientId = "",
            ),
            topics = Topics(
                rxPrefix = Defaults.RX_PREFIX,
                txPrefix = Defaults.TX_PREFIX,
                availability = Defaults.AVAILABILITY,
                nodeId = Defaults.NODE_ID,
                payloadKey = Defaults.PAYLOAD_KEY,
            ),
            home = Home(enabled = false, latitude = null, longitude = null, radiusMeters = Defaults.RADIUS_M),
            camera = Camera(rtspUrl = "", rtspUsername = "", rtspPassword = ""),
            requireUnlockForCommands = true,
        )
    }

    /**
     * Defaults describe the *shape* of a typical hc12 deployment, never a specific one.
     * Topic prefixes are safe to default; a host, a coordinate or a credential is not.
     */
    object Defaults {
        const val PORT_PLAIN = 1883
        const val PORT_TLS = 8883
        const val RX_PREFIX = "hc12/rx/"
        const val TX_PREFIX = "hc12/tx/"
        const val AVAILABILITY = "hc12/available"
        const val NODE_ID = 4
        const val PAYLOAD_KEY = "idTarget"

        /** 2 km — well above docs/08's ~150 m reliability floor; fires ~2 min out at 60 km/h. */
        const val RADIUS_M = 2_000f
    }
}
