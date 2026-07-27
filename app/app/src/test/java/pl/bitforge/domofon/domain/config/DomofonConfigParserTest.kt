package pl.bitforge.domofon.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomofonConfigParserTest {

    private class FakeRawPrefs(
        private val strings: Map<String, String> = emptyMap(),
        private val booleans: Map<String, Boolean> = emptyMap(),
    ) : RawPrefs {
        override fun string(key: String): String? = strings[key]
        override fun boolean(key: String, fallback: Boolean): Boolean =
            booleans[key] ?: fallback
    }

    private fun parse(
        strings: Map<String, String> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
    ) = DomofonConfigParser.parse(FakeRawPrefs(strings, booleans))

    @Test
    fun `empty prefs parse to the inert defaults`() {
        val config = parse()
        assertFalse(config.isComplete)
        assertEquals(DomofonConfig.Defaults.RX_PREFIX, config.topics.rxPrefix)
        assertEquals(DomofonConfig.Defaults.PORT_PLAIN, config.broker.port)
        assertTrue(config.requireUnlockForCommands)
    }

    @Test
    fun `host and username are trimmed - the soft keyboard appends invisible spaces`() {
        val config = parse(
            strings = mapOf(
                ConfigKeys.HOST to " broker.local ",
                ConfigKeys.USER to "mqtt-user ",
            ),
        )
        assertEquals("broker.local", config.broker.host)
        assertEquals("mqtt-user", config.broker.username)
        assertTrue(config.isComplete)
    }

    @Test
    fun `topic prefixes are normalised to end in a slash`() {
        val config = parse(strings = mapOf(ConfigKeys.RX_PREFIX to "hc12/rx"))
        assertEquals("hc12/rx/", config.topics.rxPrefix)
    }

    @Test
    fun `a prefix already ending in slash is untouched`() {
        val config = parse(strings = mapOf(ConfigKeys.TX_PREFIX to "custom/tx/"))
        assertEquals("custom/tx/", config.topics.txPrefix)
    }

    @Test
    fun `blank prefix falls back to the default`() {
        val config = parse(strings = mapOf(ConfigKeys.RX_PREFIX to "   "))
        assertEquals(DomofonConfig.Defaults.RX_PREFIX, config.topics.rxPrefix)
    }

    @Test
    fun `default port follows the tls switch`() {
        assertEquals(1883, parse().broker.port)
        assertEquals(
            8883,
            parse(booleans = mapOf(ConfigKeys.TLS to true)).broker.port,
        )
    }

    @Test
    fun `numeric strings parse and garbage falls back`() {
        val config = parse(strings = mapOf(ConfigKeys.PORT to " 1884 ", ConfigKeys.NODE_ID to "junk"))
        assertEquals(1884, config.broker.port)
        assertEquals(DomofonConfig.Defaults.NODE_ID, config.topics.nodeId)
    }

    @Test
    fun `qos, keepalive and snapshot interval are clamped to legal ranges`() {
        val config = parse(
            strings = mapOf(
                ConfigKeys.QOS_STATE to "7",
                ConfigKeys.QOS_COMMAND to "-1",
                ConfigKeys.KEEP_ALIVE to "99999999",
                ConfigKeys.SNAPSHOT_SECS to "0",
            ),
        )
        assertEquals(2, config.mqtt.qosState)
        assertEquals(0, config.mqtt.qosCommand)
        assertEquals(65_535, config.mqtt.keepAliveSeconds)
        assertEquals(1, config.camera.snapshotSecs)
    }

    @Test
    fun `half-filled home form is unusable - null island included`() {
        val nullIsland = parse(
            strings = mapOf(ConfigKeys.LAT to "0", ConfigKeys.LON to "0"),
            booleans = mapOf(ConfigKeys.GEOFENCE to true),
        )
        assertFalse(nullIsland.home.isUsable)

        val onlyLat = parse(
            strings = mapOf(ConfigKeys.LAT to "52.1"),
            booleans = mapOf(ConfigKeys.GEOFENCE to true),
        )
        assertNull(onlyLat.home.longitude)
        assertFalse(onlyLat.home.isUsable)

        val complete = parse(
            strings = mapOf(ConfigKeys.LAT to "52.1", ConfigKeys.LON to "21.0"),
            booleans = mapOf(ConfigKeys.GEOFENCE to true),
        )
        assertTrue(complete.home.isUsable)
    }

    @Test
    fun `the in-app fence is off unless asked for`() {
        // It is a second arrival trigger, not a default one: the Play Services fence stays
        // primary, and this only evaluates while a surface is alive.
        assertFalse(parse().home.inAppFence)
        assertFalse(parse(booleans = mapOf(ConfigKeys.GEOFENCE to true)).home.inAppFence)

        val on = parse(
            booleans = mapOf(ConfigKeys.GEOFENCE to true, ConfigKeys.IN_APP_FENCE to true),
        )
        assertTrue(on.home.inAppFence)
    }

    @Test
    fun `wire equality is what drives a connection rebuild`() {
        val base = parse(strings = mapOf(ConfigKeys.HOST to "broker.local"))
        val topicEdit = parse(
            strings = mapOf(ConfigKeys.HOST to "broker.local", ConfigKeys.RX_PREFIX to "other/rx/"),
        )
        val cameraEdit = parse(
            strings = mapOf(ConfigKeys.HOST to "broker.local", ConfigKeys.RTSP_URL to "rtsp://cam/1"),
        )
        // A topic edit changes the wire (subscriptions are pinned at connect)…
        assertNotEquals(base.wire, topicEdit.wire)
        // …a camera edit must not force an MQTT reconnect.
        assertEquals(base.wire, cameraEdit.wire)
    }

    @Test
    fun `camera source parses, and garbage falls back to rtsp`() {
        assertEquals(CameraSource.RTSP, parse().camera.source)
        assertEquals(
            CameraSource.HTTP,
            parse(strings = mapOf(ConfigKeys.SOURCE to "http")).camera.source,
        )
        // The dropdown writes lower case, but a hand-edited file may not.
        assertEquals(
            CameraSource.HTTP,
            parse(strings = mapOf(ConfigKeys.SOURCE to " HTTP ")).camera.source,
        )
        // A typo must never leave the panel permanently blank.
        assertEquals(
            CameraSource.RTSP,
            parse(strings = mapOf(ConfigKeys.SOURCE to "mjpeg")).camera.source,
        )
    }

    @Test
    fun `feed is null when the selected path's url is blank`() {
        // An HTTP selection with only an RTSP URL filled in is not a camera…
        val httpWithoutImage = parse(
            strings = mapOf(
                ConfigKeys.SOURCE to "http",
                ConfigKeys.RTSP_URL to "rtsp://cam/1",
            ),
        )
        assertNull(httpWithoutImage.camera.feed)
        assertFalse(httpWithoutImage.camera.hasPicture)

        // …and neither is the mirror image. The old code would have used whichever was set.
        val rtspWithoutStream = parse(
            strings = mapOf(ConfigKeys.SNAPSHOT_URL to "http://frigate/api/gate/latest.jpg"),
        )
        assertNull(rtspWithoutStream.camera.feed)
    }

    @Test
    fun `feed carries only the selected path's fields`() {
        val strings = mapOf(
            ConfigKeys.SOURCE to "http",
            ConfigKeys.RTSP_URL to "rtsp://cam/1",
            ConfigKeys.SNAPSHOT_URL to "http://frigate/api/gate/latest.jpg",
            ConfigKeys.AUDIO_URL to "rtsp://go2rtc:8554/gate?video=false",
        )
        val feed = parse(strings).camera.feed
        assertTrue(feed is CameraFeed.Http)
        val http = feed as CameraFeed.Http
        assertEquals("http://frigate/api/gate/latest.jpg", http.imageUrl)
        assertEquals("rtsp://go2rtc:8554/gate?video=false", http.audioUrl)
        assertTrue(http.hasAudio)

        // Same stored prefs, other selection: the RTSP feed cannot see any of the above.
        val rtsp = parse(strings + (ConfigKeys.SOURCE to "rtsp")).camera.feed
        assertEquals(CameraFeed.Rtsp("rtsp://cam/1", audioEnabled = true), rtsp)
    }

    @Test
    fun `http stills without an audio url are still a working camera`() {
        val feed = parse(
            strings = mapOf(
                ConfigKeys.SOURCE to "http",
                ConfigKeys.SNAPSHOT_URL to "http://frigate/api/gate/latest.jpg",
            ),
        ).camera.feed as CameraFeed.Http
        assertEquals("", feed.audioUrl)
        assertFalse(feed.hasAudio)
    }

    @Test
    fun `muting drops the audio url from the feed, so the session is never opened`() {
        val feed = parse(
            strings = mapOf(
                ConfigKeys.SOURCE to "http",
                ConfigKeys.SNAPSHOT_URL to "http://frigate/api/gate/latest.jpg",
                ConfigKeys.AUDIO_URL to "rtsp://go2rtc:8554/gate?video=false",
            ),
            booleans = mapOf(ConfigKeys.AUDIO_ENABLED to false),
        ).camera.feed as CameraFeed.Http
        assertEquals("", feed.audioUrl)
        assertFalse(feed.hasAudio)
    }

    @Test
    fun `the snapshot interval is not part of the feed - retuning must not restart a session`() {
        val strings = mapOf(ConfigKeys.RTSP_URL to "rtsp://cam/1")
        val slow = parse(strings + (ConfigKeys.SNAPSHOT_SECS to "10")).camera
        val fast = parse(strings + (ConfigKeys.SNAPSHOT_SECS to "2")).camera
        assertNotEquals(slow.snapshotSecs, fast.snapshotSecs)
        assertEquals(slow.feed, fast.feed)
    }

    @Test
    fun `switching source changes the feed, so the grabber reopens`() {
        val strings = mapOf(
            ConfigKeys.RTSP_URL to "rtsp://cam/1",
            ConfigKeys.SNAPSHOT_URL to "http://frigate/api/gate/latest.jpg",
        )
        assertNotEquals(
            parse(strings).camera.feed,
            parse(strings + (ConfigKeys.SOURCE to "http")).camera.feed,
        )
    }

    @Test
    fun `redacting toString never leaks the secrets`() {
        val config = parse(
            strings = mapOf(
                ConfigKeys.HOST to "broker.local",
                ConfigKeys.PASS to "hunter2",
                ConfigKeys.RTSP_URL to "rtsp://user:hunter2@cam/1",
                ConfigKeys.SNAPSHOT_URL to "http://user:hunter2@frigate/api/gate/latest.jpg",
                ConfigKeys.AUDIO_URL to "rtsp://user:hunter2@go2rtc:8554/gate",
                ConfigKeys.LAT to "52.123456",
                ConfigKeys.LON to "21.654321",
            ),
        )
        val printed = config.toString()
        assertFalse(printed.contains("hunter2"))
        assertFalse(printed.contains("broker.local"))
        assertFalse(printed.contains("frigate"))
        assertFalse(printed.contains("go2rtc"))
        assertFalse(printed.contains("52.123456"))
    }
}
