package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDistanceFormatTest {

    private val radius = 2_000f

    @Test
    fun `null reading hides the line`() {
        assertEquals("", formatHomeDistance(null, radius))
    }

    @Test
    fun `zone bands derive from the radius`() {
        assertEquals("500 m · at home", formatHomeDistance(499f, radius))          // < R/2
        assertEquals("1.0 km · approaching home", formatHomeDistance(1_000f, radius))
        assertEquals("3.0 km · approaching home", formatHomeDistance(3_000f, radius)) // == 3R/2
        assertEquals("3.1 km · away from home", formatHomeDistance(3_100f, radius))
    }

    @Test
    fun `under a kilometre rounds to 10 m`() {
        assertEquals("120 m · at home", formatHomeDistance(123f, radius))
        assertEquals("130 m · at home", formatHomeDistance(125f, radius))
    }

    @Test
    fun `a kilometre and beyond rounds to a tenth`() {
        assertEquals("1.5 km · approaching home", formatHomeDistance(1_540f, radius))
        assertEquals("12.3 km · away from home", formatHomeDistance(12_345f, radius))
    }

    @Test
    fun `rounding uses a dot regardless of device locale`() {
        // Locale.US is pinned in the formatter; a comma here would desync phone and car.
        assertEquals("1.1 km · approaching home", formatHomeDistance(1_100f, radius))
    }
}
