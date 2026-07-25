package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class GatePolicyTest {

    // --- primaryAction ---------------------------------------------------------------

    @Test
    fun `opened offers close, everything else offers open`() {
        assertEquals(PrimaryAction("Close gate", "close"), GatePolicy.primaryAction("opened"))
        listOf("closed", "opening", "closing", "stopped", "stuck_opening", "unknown").forEach {
            assertEquals("state=$it", PrimaryAction("Open gate", "open"), GatePolicy.primaryAction(it))
        }
    }

    // --- gateStatusLine: the full matrix ---------------------------------------------

    private fun line(
        status: ConnectionStatus,
        bridge: BridgeStatus = BridgeStatus.UNKNOWN,
        state: String = GatePolicy.STATE_UNKNOWN,
        reason: String = "",
    ) = GatePolicy.gateStatusLine(ConnectionState(status, reason), bridge, state)

    @Test
    fun `failed names its reason`() {
        assertEquals(
            "Gate — Broker rejected the username or password",
            line(ConnectionStatus.FAILED, reason = "Broker rejected the username or password"),
        )
    }

    @Test
    fun `connecting or disconnected with no state reads as connecting, never an outage`() {
        assertEquals("Gate — connecting…", line(ConnectionStatus.CONNECTING))
        assertEquals("Gate — connecting…", line(ConnectionStatus.DISCONNECTED))
    }

    @Test
    fun `connecting with a state already held shows the state`() {
        assertEquals("Gate — opened", line(ConnectionStatus.CONNECTING, state = "opened"))
    }

    @Test
    fun `bridge LWT offline reads as unreachable regardless of state`() {
        assertEquals("Gate — unreachable", line(ConnectionStatus.CONNECTED, BridgeStatus.OFFLINE, "opened"))
    }

    @Test
    fun `connected but silent bridge is waiting, not healthy`() {
        assertEquals("Gate — waiting for the service", line(ConnectionStatus.CONNECTED, BridgeStatus.UNKNOWN))
    }

    @Test
    fun `connected with silent bridge but retained state shows the state`() {
        assertEquals(
            "Gate — closed",
            line(ConnectionStatus.CONNECTED, BridgeStatus.UNKNOWN, "closed"),
        )
    }

    @Test
    fun `bridge online with no state distinguishes itself from waiting`() {
        assertEquals("Gate — no state reported", line(ConnectionStatus.CONNECTED, BridgeStatus.ONLINE))
    }

    @Test
    fun `bridge online with state is the normal happy line`() {
        assertEquals("Gate — opening", line(ConnectionStatus.CONNECTED, BridgeStatus.ONLINE, "opening"))
    }

    @Test
    fun `degraded renders exactly like connected`() {
        listOf(BridgeStatus.UNKNOWN, BridgeStatus.ONLINE, BridgeStatus.OFFLINE).forEach { bridge ->
            listOf("unknown", "opened").forEach { state ->
                assertEquals(
                    "bridge=$bridge state=$state",
                    line(ConnectionStatus.CONNECTED, bridge, state),
                    line(ConnectionStatus.DEGRADED, bridge, state),
                )
            }
        }
    }
}
