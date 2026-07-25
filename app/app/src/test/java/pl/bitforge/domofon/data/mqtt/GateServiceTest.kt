package pl.bitforge.domofon.data.mqtt

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bitforge.domofon.config.DomofonConfig
import pl.bitforge.domofon.domain.BridgeStatus
import pl.bitforge.domofon.domain.ConnectionStatus
import pl.bitforge.domofon.domain.GatePolicy

@OptIn(ExperimentalCoroutinesApi::class)
class GateServiceTest {

    private val transport = FakeTransport()

    private var config = DomofonConfig.EMPTY.copy(
        broker = DomofonConfig.EMPTY.broker.copy(host = "broker.local", clientId = "test-client"),
    )

    private fun TestScope.service() = GateService(
        transport = transport,
        currentConfig = { config },
        scope = backgroundScope,
    )

    private fun FakeTransport.FakeHandle.deliverSignal(
        signal: String,
        ts: String,
        retained: Boolean = true,
    ) = deliver("hc12/rx/$signal", """{"ts":"$ts"}""", retained)

    // --- lease lifecycle -------------------------------------------------------------

    @Test
    fun `first lease opens the connection and connect subscribes everything`() = runTest {
        val service = service()
        service.acquire("phone-ui")

        assertEquals(1, transport.handles.size)
        assertEquals(ConnectionStatus.CONNECTING, service.connection.value.status)

        transport.handles[0].connectComplete()
        assertEquals(ConnectionStatus.CONNECTED, service.connection.value.status)
        // Seven signals + availability + error.
        assertEquals(9, transport.handles[0].subscriptions.size)
    }

    @Test
    fun `second lease reuses the connection`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        service.acquire("car-session")
        assertEquals(1, transport.handles.size)
    }

    @Test
    fun `releasing the last lease tears down and resets the gate state`() = runTest {
        val service = service()
        val lease = service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()
        h.deliverSignal("GateOpened", "2026-07-20T10:00:00Z")
        assertEquals("opened", service.gateState.value.state)

        lease.close()
        assertTrue(h.closed)
        assertEquals(ConnectionStatus.DISCONNECTED, service.connection.value.status)
        assertEquals(GatePolicy.STATE_UNKNOWN, service.gateState.value.state)
        assertEquals(BridgeStatus.UNKNOWN, service.bridgeStatus.value)
    }

    @Test
    fun `closing the same lease twice cannot release somebody else's claim`() = runTest {
        val service = service()
        val phone = service.acquire("phone-ui")
        service.acquire("car-session")

        phone.close()
        phone.close()
        // The car session still holds the connection.
        assertFalse(transport.handles[0].closed)
        transport.handles[0].connectComplete()
        assertEquals(ConnectionStatus.CONNECTED, service.connection.value.status)
    }

    @Test
    fun `callbacks from a retired handle land in silence`() = runTest {
        val service = service()
        val lease = service.acquire("phone-ui")
        val h = transport.handles[0]
        lease.close()

        h.connectComplete()
        h.deliverSignal("GateOpened", "2026-07-20T10:00:00Z")
        h.dropConnection(RuntimeException("late"))

        assertEquals(ConnectionStatus.DISCONNECTED, service.connection.value.status)
        assertEquals(GatePolicy.STATE_UNKNOWN, service.gateState.value.state)
    }

    @Test
    fun `acquiring with edited broker settings rebuilds the connection`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        transport.handles[0].connectComplete()

        config = config.copy(broker = config.broker.copy(port = 8883, tls = true))
        service.acquire("settings")

        assertTrue(transport.handles[0].closed)
        assertEquals(2, transport.handles.size)
        assertEquals(8883, transport.handles[1].wire.broker.port)
    }

    @Test
    fun `refresh without a holder is a no-op, with one it rebuilds on change`() = runTest {
        val service = service()
        service.refresh()
        assertEquals(0, transport.handles.size)

        service.acquire("settings")
        service.refresh() // unchanged wire — nothing happens
        assertEquals(1, transport.handles.size)

        config = config.copy(broker = config.broker.copy(username = "new-user"))
        service.refresh()
        assertEquals(2, transport.handles.size)
        assertTrue(transport.handles[0].closed)
    }

    @Test
    fun `incomplete config fails loudly instead of connecting`() = runTest {
        config = DomofonConfig.EMPTY
        val service = service()
        service.acquire("phone-ui")

        assertEquals(0, transport.handles.size)
        assertEquals(ConnectionStatus.FAILED, service.connection.value.status)
        assertEquals("No broker configured", service.connection.value.reason)
    }

    // --- watchdog and reconnect --------------------------------------------------------

    @Test
    fun `watchdog opens a connection once the user finishes setup`() = runTest {
        config = DomofonConfig.EMPTY
        val service = service()
        service.acquire("phone-ui")
        assertEquals(0, transport.handles.size)

        config = config.copy(
            broker = config.broker.copy(host = "broker.local", clientId = "test-client"),
        )
        advanceTimeBy(16_000)
        assertEquals(1, transport.handles.size)
    }

    @Test
    fun `watchdog dies with the last lease`() = runTest {
        config = DomofonConfig.EMPTY
        val service = service()
        val lease = service.acquire("phone-ui")
        lease.close()

        advanceTimeBy(60_000)
        assertEquals(0, transport.handles.size)
    }

    @Test
    fun `a dropped connection rebuilds with doubling backoff`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h1 = transport.handles[0]
        h1.connectComplete()

        h1.dropConnection(RuntimeException("network yanked"))
        assertEquals(ConnectionStatus.FAILED, service.connection.value.status)
        advanceTimeBy(1_100)
        assertEquals(2, transport.handles.size)

        // Second failure: the next rebuild waits ~2 s, so nothing at 1.1 s.
        transport.handles[1].dropConnection(RuntimeException("still down"))
        advanceTimeBy(1_100)
        assertEquals(2, transport.handles.size)
        advanceTimeBy(1_000)
        assertEquals(3, transport.handles.size)
    }

    @Test
    fun `a successful connect resets the backoff`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        transport.handles[0].connectComplete()
        transport.handles[0].dropConnection(RuntimeException("blip"))
        advanceTimeBy(1_100)

        transport.handles[1].connectComplete() // resets the schedule
        transport.handles[1].dropConnection(RuntimeException("blip"))
        advanceTimeBy(1_100)                    // back to the 1 s floor
        assertEquals(3, transport.handles.size)
    }

    // --- inbound ----------------------------------------------------------------------

    @Test
    fun `retained out-of-order burst keeps the newest state`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.deliverSignal("GateClosed", "2026-07-20T11:40:10Z")
        h.deliverSignal("GateOpening", "2026-07-20T11:40:05Z")
        assertEquals("closed", service.gateState.value.state)
    }

    @Test
    fun `a live message proves the bridge is alive - a retained one does not`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.deliverSignal("GateOpened", "2026-07-20T10:00:00Z", retained = true)
        assertEquals(BridgeStatus.UNKNOWN, service.bridgeStatus.value)

        h.deliverSignal("GateClosed", "2026-07-20T10:00:01Z", retained = false)
        assertEquals(BridgeStatus.ONLINE, service.bridgeStatus.value)
    }

    @Test
    fun `availability topic drives the bridge status`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.deliver("hc12/available", "online", retained = true)
        assertEquals(BridgeStatus.ONLINE, service.bridgeStatus.value)
        h.deliver("hc12/available", "offline", retained = true)
        assertEquals(BridgeStatus.OFFLINE, service.bridgeStatus.value)
    }

    @Test
    fun `our bridge rejection surfaces and expires after its ttl`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.deliver("hc12/error", """{"topic":"hc12/tx/OpenGate","reason":"out of range"}""")
        assertEquals("Gate service refused the command: out of range", service.lastError.value)

        advanceTimeBy(21_000)
        assertEquals("", service.lastError.value)
    }

    @Test
    fun `a foreign rejection on the shared error topic stays silent`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.deliver("hc12/error", """{"topic":"other/tx/Thing","reason":"nope"}""")
        assertEquals("", service.lastError.value)
    }

    @Test
    fun `teardown resets state but keeps the last error for the ui to render`() = runTest {
        val service = service()
        val lease = service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()
        h.deliver("hc12/error", """{"topic":"hc12/tx/OpenGate","reason":"refused"}""")

        lease.close()
        assertEquals("Gate service refused the command: refused", service.lastError.value)
    }

    @Test
    fun `a refused subscription degrades the connection instead of hiding`() = runTest {
        val service = service()
        service.acquire("phone-ui")
        val h = transport.handles[0]
        h.connectComplete()

        h.refuseSubscription("hc12/rx/GateOpened")
        assertEquals(ConnectionStatus.DEGRADED, service.connection.value.status)
    }

    // --- outbound ---------------------------------------------------------------------

    @Test
    fun `sendCommandAwait publishes unretained on the tx topic and releases its lease`() = runTest {
        val service = service()
        val result = async { service.sendCommandAwait("open") }
        runCurrent()

        val h = transport.handles[0]
        h.connectComplete()
        advanceUntilIdle()

        assertTrue(result.await())
        val publish = h.published.single()
        assertEquals("hc12/tx/OpenGate", publish.topic)
        assertEquals("""{"idTarget":4}""", publish.payload)
        assertFalse(publish.retain)
        // The one-shot lease let go: the connection is gone again.
        assertTrue(h.closed)
        assertEquals(ConnectionStatus.DISCONNECTED, service.connection.value.status)
    }

    @Test
    fun `sendCommandAwait that never connects reports it within its budget`() = runTest {
        val service = service()
        val result = async { service.sendCommandAwait("open") }
        advanceTimeBy(8_100)

        assertFalse(result.await())
        assertEquals("Command not sent — no connection to the broker", service.lastError.value)
    }

    @Test
    fun `sendCommandAwait reports a broker that refuses the publish`() = runTest {
        val service = service()
        val result = async { service.sendCommandAwait("open") }
        runCurrent()

        transport.handles[0].ackPublishes = false
        transport.handles[0].connectComplete()
        advanceUntilIdle()

        assertFalse(result.await())
        assertEquals("Command not sent — the broker did not accept it", service.lastError.value)
    }

    @Test
    fun `unknown action never even connects`() = runTest {
        val service = service()
        assertFalse(service.sendCommandAwait("selfdestruct"))
        assertEquals(0, transport.handles.size)
    }

    @Test
    fun `degraded still counts as connected for a command`() = runTest {
        val service = service()
        val result = async { service.sendCommandAwait("stop") }
        runCurrent()

        val h = transport.handles[0]
        h.connectComplete()
        h.refuseSubscription("hc12/rx/GateOpened")
        advanceUntilIdle()

        assertTrue(result.await())
        assertEquals("hc12/tx/StopGate", h.published.single().topic)
    }

    // --- awaitFreshState --------------------------------------------------------------

    @Test
    fun `awaitFreshState gives up when nothing arrives`() = runTest {
        val service = service()
        service.acquire("arrival")
        transport.handles[0].connectComplete()

        assertNull(service.awaitFreshState(6_000))
    }

    @Test
    fun `awaitFreshState waits out the retained burst and returns the winner`() = runTest {
        val service = service()
        service.acquire("arrival")
        val h = transport.handles[0]
        h.connectComplete()

        val result = async { service.awaitFreshState(6_000) }
        runCurrent()

        // The burst arrives in arbitrary order; the first to land is not the newest.
        h.deliverSignal("GateOpening", "2026-07-20T11:40:05Z")
        h.deliverSignal("GateClosed", "2026-07-20T11:40:10Z")
        advanceTimeBy(800) // settle window

        assertEquals("closed", result.await()?.state)
    }
}
