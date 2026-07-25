package pl.bitforge.domofon.gate

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smoke test proving the JVM unit-test harness works under the Qt Gradle plugin.
 * The full status-line matrix lands with the domain extraction; this pins the
 * three wordings that have already drifted once between surfaces.
 */
class GateStatusLineTest {

    @Test
    fun `failed connection names its reason`() {
        val line = gateStatusLine(
            ConnectionState(ConnectionStatus.FAILED, "Broker refused authorization"),
            BridgeStatus.UNKNOWN,
            "unknown",
        )
        assertEquals("Gate — Broker refused authorization", line)
    }

    @Test
    fun `connecting with no state reads as connecting, not an outage`() {
        val line = gateStatusLine(
            ConnectionState(ConnectionStatus.CONNECTING),
            BridgeStatus.UNKNOWN,
            "unknown",
        )
        assertEquals("Gate — connecting…", line)
    }

    @Test
    fun `bridge LWT offline reads as unreachable`() {
        val line = gateStatusLine(
            ConnectionState(ConnectionStatus.CONNECTED),
            BridgeStatus.OFFLINE,
            "opened",
        )
        assertEquals("Gate — unreachable", line)
    }
}
