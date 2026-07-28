package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class GateTimestampTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun `an offset timestamp renders as the wall clock in the given zone`() {
        assertEquals("18:34", hourMinute("2026-07-27T18:34:12+02:00", warsaw))
    }

    @Test
    fun `a bare instant is converted, not printed`() {
        // The bridge has published both forms. 16:34Z is 18:34 in Warsaw in July.
        assertEquals("18:34", hourMinute("2026-07-27T16:34:12Z", warsaw))
    }

    @Test
    fun `an offset from somewhere else is still shown as local time`() {
        // A driver reads a notification against their own clock, not the publisher's.
        assertEquals("18:34", hourMinute("2026-07-27T12:34:12-04:00", warsaw))
    }

    @Test
    fun `it is 24-hour, always`() {
        // Never a locale short-time format: "6:34 PM" is longer and slower to read at 60 km/h.
        assertEquals("18:34", hourMinute("2026-07-27T18:34:00+02:00", warsaw))
        assertEquals("06:34", hourMinute("2026-07-27T06:34:00+02:00", warsaw))
        assertEquals("00:05", hourMinute("2026-07-27T00:05:00+02:00", warsaw))
    }

    @Test
    fun `unparseable input falls back to the raw string`() {
        // Unreachable in practice — GateProtocol turns a bad ts into GateEvent.Ignored — but
        // dropping the timestamp would be a worse answer than showing it ugly.
        assertEquals("nonsense", hourMinute("nonsense", warsaw))
        assertEquals("", hourMinute("", warsaw))
    }

    @Test
    fun `the parser accepts exactly the two wire forms`() {
        assertEquals(
            java.time.Instant.parse("2026-07-27T16:34:12Z"),
            parseWireTimestamp("2026-07-27T18:34:12+02:00"),
        )
        assertEquals(
            java.time.Instant.parse("2026-07-27T16:34:12Z"),
            parseWireTimestamp("2026-07-27T16:34:12Z"),
        )
        assertNull(parseWireTimestamp("2026-07-27"))
        assertNull(parseWireTimestamp(""))
    }
}
