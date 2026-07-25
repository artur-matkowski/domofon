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
    fun `redacting toString never leaks the secrets`() {
        val config = parse(
            strings = mapOf(
                ConfigKeys.HOST to "broker.local",
                ConfigKeys.PASS to "hunter2",
                ConfigKeys.RTSP_URL to "rtsp://user:hunter2@cam/1",
                ConfigKeys.LAT to "52.123456",
                ConfigKeys.LON to "21.654321",
            ),
        )
        val printed = config.toString()
        assertFalse(printed.contains("hunter2"))
        assertFalse(printed.contains("broker.local"))
        assertFalse(printed.contains("52.123456"))
    }
}
