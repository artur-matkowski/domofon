package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bitforge.domofon.domain.config.DomofonConfig
import java.time.Instant

class GateProtocolTest {

    private val topics = DomofonConfig.Topics(
        rxPrefix = "hc12/rx/",
        txPrefix = "hc12/tx/",
        availability = "hc12/available",
        nodeId = 4,
        payloadKey = "idTarget",
    )
    private val mqtt = DomofonConfig.Mqtt(
        qosState = 1,
        qosCommand = 2,
        qosAvailability = 0,
        keepAliveSeconds = 60,
    )
    private val protocol = GateProtocol(topics)

    // --- subscriptions ---------------------------------------------------------------

    @Test
    fun `subscribes every signal plus availability with per-class qos`() {
        val subs = protocol.subscriptions(mqtt)
        assertEquals(GateProtocol.SIGNAL_TO_STATE.size + 1, subs.size)
        GateProtocol.SIGNAL_TO_STATE.keys.forEach { signal ->
            assertTrue(subs.contains(Subscription("hc12/rx/$signal", 1)))
        }
        assertTrue(subs.contains(Subscription("hc12/available", 0)))
    }

    // --- availability ----------------------------------------------------------------

    @Test
    fun `availability online and offline decode to bridge status`() {
        assertEquals(
            GateEvent.Availability(BridgeStatus.ONLINE),
            protocol.decode("hc12/available", "online", retained = true),
        )
        assertEquals(
            GateEvent.Availability(BridgeStatus.OFFLINE),
            protocol.decode("hc12/available", "offline", retained = false),
        )
    }

    @Test
    fun `junk on the availability topic is evidence of nothing`() {
        assertEquals(
            GateEvent.Availability(BridgeStatus.UNKNOWN),
            protocol.decode("hc12/available", "rebooting", retained = false),
        )
    }

    // --- signals ---------------------------------------------------------------------

    @Test
    fun `every signal name maps to its ui state`() {
        val expected = mapOf(
            "GateOpened" to "opened",
            "GateClosed" to "closed",
            "GateOpening" to "opening",
            "GateClosing" to "closing",
            "GateStopped" to "stopped",
            "GateStuckOpening" to "stuck_opening",
            "GateStuckClosing" to "stuck_closing",
        )
        expected.forEach { (signal, state) ->
            val event = protocol.decode(
                "hc12/rx/$signal",
                """{"idSender":4,"idTarget":255,"ts":"2026-07-25T10:00:00Z"}""",
                retained = true,
            )
            assertTrue("$signal decoded as $event", event is GateEvent.Signal)
            assertEquals(state, (event as GateEvent.Signal).state)
        }
    }

    @Test
    fun `signal carries parsed instant, raw ts and the retain flag`() {
        val event = protocol.decode(
            "hc12/rx/GateOpened",
            """{"ts":"2026-07-25T10:00:05+02:00"}""",
            retained = false,
        ) as GateEvent.Signal
        assertEquals(Instant.parse("2026-07-25T08:00:05Z"), event.ts)
        assertEquals("2026-07-25T10:00:05+02:00", event.rawTs)
        assertEquals(false, event.retained)
    }

    @Test
    fun `plain instant timestamps parse too`() {
        val event = protocol.decode(
            "hc12/rx/GateClosed",
            """{"ts":"2026-07-25T10:00:00Z"}""",
            retained = true,
        )
        assertTrue(event is GateEvent.Signal)
    }

    @Test
    fun `unknown signal topic is ignored`() {
        val event = protocol.decode("hc12/rx/SomethingElse", """{"ts":"2026-07-25T10:00:00Z"}""", false)
        assertTrue(event is GateEvent.Ignored)
    }

    @Test
    fun `foreign topic outside our prefixes is ignored`() {
        val event = protocol.decode("zigbee/sensor/door", "whatever", false)
        assertTrue(event is GateEvent.Ignored)
    }

    @Test
    fun `non-json signal payload is ignored`() {
        val event = protocol.decode("hc12/rx/GateOpened", "not json", false)
        assertTrue(event is GateEvent.Ignored)
    }

    @Test
    fun `missing or unparseable ts is ignored`() {
        assertTrue(protocol.decode("hc12/rx/GateOpened", """{"idSender":4}""", false) is GateEvent.Ignored)
        assertTrue(protocol.decode("hc12/rx/GateOpened", """{"ts":"yesterday"}""", false) is GateEvent.Ignored)
    }

    // --- commands --------------------------------------------------------------------

    @Test
    fun `commands encode to the tx topic with the node id under the payload key`() {
        val open = protocol.encodeCommand("open", nodeId = 4, payloadKey = "idTarget")!!
        assertEquals("hc12/tx/OpenGate", open.topic)
        assertEquals("""{"idTarget":4}""", open.payload)

        assertEquals("hc12/tx/CloseGate", protocol.encodeCommand("close", 4, "idTarget")!!.topic)
        assertEquals("hc12/tx/StopGate", protocol.encodeCommand("stop", 4, "idTarget")!!.topic)
    }

    @Test
    fun `unknown action encodes to nothing`() {
        assertNull(protocol.encodeCommand("selfdestruct", 4, "idTarget"))
    }
}
