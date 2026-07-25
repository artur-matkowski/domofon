package pl.bitforge.domofon.domain.config

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
     * The camera, as two paths the user picks between rather than one path plus an override.
     *
     * [RTSP][CameraSource.RTSP] is the default and the only one a fresh install needs: every
     * camera speaks RTSP, the user already owns that address for their own player, it carries
     * its own auth, and stills *and* audio come out of the one session — see
     * [pl.bitforge.domofon.data.camera.RtspFrameSource].
     *
     * [HTTP][CameraSource.HTTP] is for a restreamer in front of the camera (the author runs
     * Frigate + go2rtc): [snapshotUrl] is polled for JPEGs and [audioUrl] is a *separate*
     * audio-only RTSP stream. That split is only safe because the two addresses are different
     * servers — a second session to the **camera** knocks the first one off, which is exactly
     * how the deleted `RtspAudioPlayer` failed. See docs/modules/camera.md.
     *
     * Both paths' fields persist regardless of [source], so switching back and forth never
     * costs a retyped URL.
     */
    data class Camera(
        /** Which path produces the picture. The user's explicit choice, never inferred. */
        val source: CameraSource,
        /** HTTP(S) endpoint returning one JPEG per request. May carry credentials inline. */
        val snapshotUrl: String,
        /** The camera address. Carries the credentials inline: `rtsp://user:pass@host/…` */
        val rtspUrl: String,
        /**
         * Audio-only RTSP stream for the HTTP path, e.g. a go2rtc restream. Unused on the
         * RTSP path, where audio rides the picture's own session.
         */
        val audioUrl: String,
        /**
         * Seconds between snapshots the [pl.bitforge.domofon.data.camera.CameraFrameGrabber]
         * fetches. Governs both the phone view's refresh and the car screen's redraw cadence.
         * Clamped in the parser.
         *
         * Deliberately absent from [feed]: retuning the cadence must not tear down a live
         * session, which on the RTSP path costs a 1–3 s handshake over the VPN.
         */
        val snapshotSecs: Int,
        /**
         * Play gate audio to the speaker while a camera view is open. On by default; a
         * switch, not a URL, so a user can silence the gate without unsetting an address.
         * Applies to both paths — the RTSP session's own audio track, or [audioUrl].
         */
        val audioEnabled: Boolean,
    ) {
        /**
         * The selected path resolved to exactly the fields that path needs, or null when its
         * required URL is blank — which is the whole of "is a camera configured".
         *
         * This value doubles as the **session restart key**: the grabber reopens its source
         * when this changes and only when it changes, the same trick [wire] plays for MQTT.
         */
        val feed: CameraFeed?
            get() = when (source) {
                CameraSource.RTSP ->
                    if (rtspUrl.isBlank()) null
                    else CameraFeed.Rtsp(url = rtspUrl, audioEnabled = audioEnabled)

                CameraSource.HTTP ->
                    if (snapshotUrl.isBlank()) null
                    else CameraFeed.Http(
                        imageUrl = snapshotUrl,
                        // Blank is legitimate: stills without sound is a working config.
                        audioUrl = if (audioEnabled) audioUrl else "",
                        audioEnabled = audioEnabled,
                    )
            }

        /** Whether the phone panel and the car's camera pane have anything to show at all. */
        val hasPicture: Boolean get() = feed != null

        /** Same reasoning as [Broker.toString] — every one of these URLs embeds credentials. */
        override fun toString(): String =
            "Camera(source=$source, snapshot=${snapshotUrl.isNotBlank()}, " +
                "stream=${rtspUrl.isNotBlank()}, audioUrl=${audioUrl.isNotBlank()}, " +
                "snapshotSecs=$snapshotSecs, audio=$audioEnabled)"
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
            mqtt = Mqtt(
                qosState = Defaults.QOS,
                qosCommand = Defaults.QOS,
                qosAvailability = Defaults.QOS,
                keepAliveSeconds = Defaults.KEEP_ALIVE_S,
            ),
            home = Home(enabled = false, latitude = null, longitude = null, radiusMeters = Defaults.RADIUS_M),
            camera = Camera(
                source = Defaults.CAMERA_SOURCE,
                snapshotUrl = "",
                rtspUrl = "",
                audioUrl = "",
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
        const val NODE_ID = 4
        const val PAYLOAD_KEY = "idTarget"

        /** At-least-once — what every topic used before QoS became configurable. */
        const val QOS = 1
        const val KEEP_ALIVE_S = 60

        /** Snapshot cadence in seconds — the old hardcoded 10 s, now the default. */
        const val SNAPSHOT_SECS = 10

        /** Gate audio plays by default: hearing the gate is the point of an entry phone. */
        const val AUDIO_ENABLED = true

        /**
         * RTSP, because it is the only path that works with nothing but the camera itself.
         * The HTTP path needs a restreamer someone deliberately set up, so it can never be
         * what a stranger's fresh install assumes.
         */
        val CAMERA_SOURCE = CameraSource.RTSP

        /** 2 km — well above the geofence design's ~150 m reliability floor (docs/modules/geo.md); fires ~2 min out at 60 km/h. */
        const val RADIUS_M = 2_000f
    }
}

/**
 * Which of the two camera paths is in use — the user's explicit choice.
 *
 * This used to be inferred: a non-blank snapshot URL silently outranked the stream. That
 * made a filled-in field the *only* way to express a preference, so the two settings could
 * never both be filled in and only one of them could ever be tried.
 *
 * The stored values (`rtsp`, `http`) are the `entryValues` of the settings dropdown, so they
 * are part of the persisted contract — see [ConfigKeys.SOURCE].
 */
enum class CameraSource {
    /** One RTSP session carries the picture and its audio. The default. */
    RTSP,

    /** Polled JPEGs over HTTP, with audio from a separate RTSP stream. */
    HTTP;

    companion object {
        /**
         * Resolves a stored value, falling back to [RTSP] for anything unrecognised.
         *
         * Same contract as the parser's numeric helpers: a hand-edited or corrupted
         * preference must become a legal value here rather than throwing somewhere
         * downstream that has no idea what to do about it.
         */
        fun of(raw: String?): CameraSource =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: RTSP
    }
}

/**
 * The selected camera path, resolved to the fields that path actually uses.
 *
 * Two jobs, both of which the flat [DomofonConfig.Camera] record does badly:
 *
 * 1. **Illegal states stop existing.** A source is handed exactly its own URLs, so no code
 *    below this point can read the other path's settings or has to ask which path it is on.
 * 2. **It is the session restart key.** These are `data class`es, so equality is structural,
 *    and [DomofonConfig.Camera.snapshotSecs] is deliberately *not* a member: the grabber
 *    reopens on any change to this value, and a cadence tweak must not cost an RTSP
 *    handshake. Cadence is read live instead.
 */
sealed interface CameraFeed {

    /** One media3 session for stills and sound both. */
    data class Rtsp(
        val url: String,
        val audioEnabled: Boolean,
    ) : CameraFeed

    /**
     * A JPEG endpoint polled for stills, plus an optional audio-only RTSP stream.
     *
     * [audioUrl] is blank when there is no sound to play — either the switch is off or the
     * user never set one — and stills-only is a perfectly good configuration, so that is not
     * an error state.
     */
    data class Http(
        val imageUrl: String,
        val audioUrl: String,
        val audioEnabled: Boolean,
    ) : CameraFeed {
        /** Whether a separate audio session should be opened alongside the stills. */
        val hasAudio: Boolean get() = audioEnabled && audioUrl.isNotBlank()
    }
}
