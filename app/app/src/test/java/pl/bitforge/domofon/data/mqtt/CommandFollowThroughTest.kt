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
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bitforge.domofon.domain.GatePolicy
import pl.bitforge.domofon.domain.config.DomofonConfig

/**
 * The window in which the gate's answer to a notification-button command has somewhere to
 * land. Every case here is the shape of the bug it exists to prevent: the app was attached
 * for the question and gone before the answer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandFollowThroughTest {

    private val transport = FakeTransport()

    private val config = DomofonConfig.EMPTY.copy(
        broker = DomofonConfig.EMPTY.broker.copy(host = "broker.local", clientId = "test-client"),
    )

    private fun TestScope.service() = GateService(
        transport = transport,
        currentConfig = { config },
        scope = backgroundScope,
    )

    private fun TestScope.followThrough(gate: GateService) =
        CommandFollowThrough(gate, backgroundScope)

    /**
     * The live message the gate publishes a second or two after taking the command. The stamp
     * is safely in the past — [pl.bitforge.domofon.domain.GateStateReducer] disbelieves
     * anything more than five minutes ahead of the real clock, and a "now" written into a test
     * is ahead of it by however long the test takes to be run again.
     */
    private fun FakeTransport.FakeHandle.gateStartsMoving() =
        deliver("hc12/rx/GateOpening", """{"ts":"2026-07-20T10:00:00Z"}""", retained = false)

    @Test
    fun `the connection outlives the command that opened it`() = runTest {
        val gate = service()
        val follow = followThrough(gate)

        // Exactly the notification-button path: arm, then send. Nothing else holds a lease.
        follow.arm()
        val sent = async { gate.sendCommandAwait("open") }
        runCurrent()

        val h = transport.handles[0]
        h.connectComplete()
        advanceUntilIdle()
        assertTrue(sent.await())

        // sendCommandAwait has released its own lease by now. Without the follow-through this
        // is where the socket closed and the gate reported into nothing.
        assertFalse("the connection must survive the send", h.closed)

        // ...so the gate's answer is heard, and it is heard as a *live* message, which is the
        // only kind StateChangeAnnouncer will announce.
        h.gateStartsMoving()
        assertEquals(GatePolicy.STATE_OPENING, gate.gateState.value.state)
        assertTrue(gate.gateState.value.live)
    }

    @Test
    fun `the hold covers a whole gate cycle and then lets go`() = runTest {
        val gate = service()
        val follow = followThrough(gate)
        follow.arm()
        transport.handles[0].connectComplete()

        // `opened` lands fifteen to twenty-five seconds after `opening`.
        advanceTimeBy(30_000)
        assertFalse(transport.handles[0].closed)

        advanceTimeBy(CommandFollowThrough.HOLD_MS)
        assertTrue("a bounded hold, not a 24/7 connection (D5)", transport.handles[0].closed)
    }

    @Test
    fun `re-arming extends the window without dropping the connection`() = runTest {
        val gate = service()
        val follow = followThrough(gate)
        follow.arm()
        transport.handles[0].connectComplete()

        advanceTimeBy(40_000)
        follow.arm()
        advanceUntilIdle()

        // The second lease was taken before the first wait was cancelled, so the count never
        // reached zero — a teardown here would reset gateState and cost a VPN handshake to
        // undo, during which the live answer would be missed for good.
        assertFalse(transport.handles[0].closed)
        assertEquals("no rebuild, one connection", 1, transport.handles.size)

        advanceTimeBy(CommandFollowThrough.HOLD_MS + 1)
        assertTrue(transport.handles[0].closed)
    }

    @Test
    fun `disarm releases at once`() = runTest {
        val gate = service()
        val follow = followThrough(gate)
        follow.arm()
        transport.handles[0].connectComplete()

        // The command never left the phone: there is nothing coming to listen for.
        follow.disarm()
        advanceUntilIdle()
        assertTrue(transport.handles[0].closed)
    }

    @Test
    fun `disarming before the timer has even started still releases`() = runTest {
        // The failure path's real ordering, and the bug this caught: with the release living
        // in the coroutine's `finally`, cancelling it before the dispatcher had run it once
        // meant the body never entered and the lease was held for the life of the process.
        val gate = service()
        val follow = followThrough(gate)

        follow.arm()
        follow.disarm()   // no runCurrent() in between — the wait has not begun
        advanceUntilIdle()

        assertTrue("the lease must not outlive a disarm", transport.handles[0].closed)
    }

    @Test
    fun `a surface holding the connection is unaffected by the hold expiring`() = runTest {
        val gate = service()
        val follow = followThrough(gate)
        gate.acquire("phone-ui")
        follow.arm()
        transport.handles[0].connectComplete()

        advanceTimeBy(CommandFollowThrough.HOLD_MS + 1)
        assertFalse("the phone still holds it", transport.handles[0].closed)
    }
}
