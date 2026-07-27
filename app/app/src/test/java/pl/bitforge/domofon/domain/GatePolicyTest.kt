package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bitforge.domofon.domain.camera.AudioStatus

class GatePolicyTest {

    // --- primaryAction ---------------------------------------------------------------

    /**
     * All eight states named explicitly, none omitted. The previous version of this test
     * asserted the old rule ("anything not opened offers Open") and quietly left
     * `stuck_closing` out of its list, so the one state nobody had thought about was also
     * the one nothing checked.
     */
    @Test
    fun `open and travelling-open offer close, everything else offers open`() {
        val close = PrimaryAction("Close gate", "close")
        val open = PrimaryAction("Open gate", "open")

        listOf(
            GatePolicy.STATE_OPENED,
            // The bug Artur hit: mid-travel offered "Open gate", an action that does nothing.
            GatePolicy.STATE_OPENING,
            // Jammed on the way open — it is an open gate, and Close is what clears it.
            GatePolicy.STATE_STUCK_OPENING,
        ).forEach { assertEquals("state=$it", close, GatePolicy.primaryAction(it)) }

        listOf(
            GatePolicy.STATE_CLOSED,
            GatePolicy.STATE_CLOSING,
            // Halted mid-travel is neither; Open is the safer default for someone driving up.
            GatePolicy.STATE_STOPPED,
            GatePolicy.STATE_STUCK_CLOSING,
            GatePolicy.STATE_UNKNOWN,
        ).forEach { assertEquals("state=$it", open, GatePolicy.primaryAction(it)) }
    }

    /** The rule keys off the protocol's own vocabulary, so nothing may fall outside it. */
    @Test
    fun `every protocol state is covered by the rule`() {
        val known = setOf(
            GatePolicy.STATE_OPENED, GatePolicy.STATE_CLOSED,
            GatePolicy.STATE_OPENING, GatePolicy.STATE_CLOSING,
            GatePolicy.STATE_STOPPED,
            GatePolicy.STATE_STUCK_OPENING, GatePolicy.STATE_STUCK_CLOSING,
        )
        assertEquals(known, GateProtocol.SIGNAL_TO_STATE.values.toSet())
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

    @Test
    fun `only a failed audio stream is worth a line`() {
        assertEquals("Gate audio unavailable", GatePolicy.cameraAudioNotice(AudioStatus.ERROR))
        // Everything else renders nothing. CONNECTING especially: an audio stream takes a
        // moment to come up, and announcing that would put a line on screen at every start.
        listOf(AudioStatus.NONE, AudioStatus.CONNECTING, AudioStatus.PLAYING).forEach { status ->
            assertEquals("$status", "", GatePolicy.cameraAudioNotice(status))
        }
    }
}
