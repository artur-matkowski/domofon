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
    val mqtt: Mqtt,
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

    /**
     * Everything a live connection is built from, as one comparable value.
     *
     * [GateRepository] rebuilds when this changes. It used to compare [broker] alone, so
     * correcting a topic prefix in Settings changed nothing until the app was backgrounded
     * and reopened: the subscriptions and the command topic are pinned when the client
     * opens, and the screen went on reporting a healthy connection to the wrong topics.
     * [mqtt] belongs here too — `keepAlive` travels in the CONNECT packet, and the QoS
     * values are fixed at subscribe time.
     */
    val wire: Wire get() = Wire(broker, topics, mqtt)

    data class Wire(val broker: Broker, val topics: Topics, val mqtt: Mqtt)

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
        /**
         * Where the bridge explains a command it refused. It fails closed and publishes the
         * reason ("idTarget out of range 0..255", "unknown message name '…'"), and until the
         * app subscribed to this, a rejected command was indistinguishable on screen from a
         * delivered one — the broker acks the publish either way.
         */
        val error: String,
        /** The gate controller's node id on the radio; goes in the command payload. */
        val nodeId: Int,
        /** JSON key carrying [nodeId] in a command payload. */
        val payloadKey: String,
    )

    /**
     * MQTT session tuning, one QoS knob per topic class rather than one global one: state
     * subscriptions, command publishes and the availability subscription have genuinely
     * different delivery needs, and a lossy radio bridge may support them unevenly.
     * [ConfigStore] clamps every value on the way in, so nothing here can be out of range.
     */
    data class Mqtt(
        /** QoS for the rx state-topic subscriptions. */
        val qosState: Int,
        /** QoS for command publishes on the tx topics. */
        val qosCommand: Int,
        /** QoS for the availability (LWT) subscription. */
        val qosAvailability: Int,
        val keepAliveSeconds: Int,
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

    /**
     * The camera, as one URL the user already has plus an optional escape hatch.
     *
     * [rtspUrl] is the real setting: every camera speaks RTSP, the user owns that address
     * for their own player, and it carries its own auth. Stills are pulled out of it — see
     * [pl.bitforge.domofon.camera.RtspFrameSource]. [snapshotUrl] overrides that with an
     * HTTP JPEG endpoint for anyone who has a reason (go2rtc, Frigate, bandwidth), and is
     * deliberately *not* required: snapshot paths are vendor-specific and often Digest-only,
     * so an app that needed one would only work for people who know their camera's firmware.
     */
    data class Camera(
        /** Optional HTTP(S) endpoint returning a JPEG still. May carry credentials inline. */
        val snapshotUrl: String,
        /** The camera address. Carries the credentials inline: `rtsp://user:pass@host/…` */
        val rtspUrl: String,
        /**
         * Seconds between snapshots the [pl.bitforge.domofon.camera.CameraFrameGrabber]
         * fetches. Governs both the phone view's refresh and the car screen's redraw cadence.
         * Clamped in [ConfigStore.read].
         */
        val snapshotSecs: Int,
        /**
         * Play the RTSP stream's audio to the speaker while a camera view is open — see
         * [pl.bitforge.domofon.camera.RtspAudioPlayer]. On by default; a switch, not a URL,
         * so a user can silence the gate without unsetting [rtspUrl]. Only the RTSP source
         * carries audio, so this does nothing for a snapshot-only ([hasSnapshot]) config.
         */
        val audioEnabled: Boolean,
    ) {
        /** An HTTP snapshot endpoint is configured, and overrides the stream as the source. */
        val hasSnapshot: Boolean get() = snapshotUrl.isNotBlank()

        /** A stream is configured — the normal case, and the source of stills by default. */
        val hasStream: Boolean get() = rtspUrl.isNotBlank()

        /** Whether the phone panel and the car's camera pane have anything to show at all. */
        val hasPicture: Boolean get() = hasSnapshot || hasStream

        /** Same reasoning as [Broker.toString] — both URLs embed the credentials. */
        override fun toString(): String =
            "Camera(snapshot=$hasSnapshot, stream=$hasStream, snapshotSecs=$snapshotSecs, audio=$audioEnabled)"
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
                error = Defaults.ERROR,
                nodeId = Defaults.NODE_ID,
                payloadKey = Defaults.PAYLOAD_KEY,
            ),
            mqtt = Mqtt(
                qosState = Defaults.QOS,
                qosCommand = Defaults.QOS,
                qosAvailability = Defaults.QOS,
                keepAliveSeconds = Defaults.KEEP_ALIVE_S,
            ),
            home = Home(enabled = false, latitude = null, longitude = null, radiusMeters = Defaults.RADIUS_M),
            camera = Camera(
                snapshotUrl = "",
                rtspUrl = "",
                snapshotSecs = Defaults.SNAPSHOT_SECS,
                audioEnabled = Defaults.AUDIO_ENABLED,
            ),
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
        const val ERROR = "hc12/error"
        const val NODE_ID = 4
        const val PAYLOAD_KEY = "idTarget"

        /** At-least-once — what every topic used before QoS became configurable. */
        const val QOS = 1
        const val KEEP_ALIVE_S = 60

        /** Snapshot cadence in seconds — the old hardcoded 10 s, now the default. */
        const val SNAPSHOT_SECS = 10

        /** Gate audio plays by default: hearing the gate is the point of an entry phone. */
        const val AUDIO_ENABLED = true

        /** 2 km — well above docs/08's ~150 m reliability floor; fires ~2 min out at 60 km/h. */
        const val RADIUS_M = 2_000f
    }
}
