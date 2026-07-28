package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFenceCrossingTest {

    private val radius = 2_000f
    private val fence = HomeFenceCrossing()

    /** A fix good enough to place us either side of a 2 km fence. */
    private fun reading(meters: Float) = fence.onReading(sideOf(meters, GOOD_FIX_M, radius))

    @Test
    fun `the first reading only establishes which side we are on`() {
        // The in-app mirror of omitting INITIAL_TRIGGER_ENTER: at 2 km the house is inside
        // the fence, so firing on the first reading would announce an arrival every time the
        // car screen opened on the driveway.
        assertFalse(reading(50f))
    }

    @Test
    fun `first reading outside does not fire either`() {
        assertFalse(reading(9_000f))
    }

    @Test
    fun `crossing inward fires exactly once`() {
        reading(9_000f)
        assertFalse(reading(4_000f))     // still outside
        assertTrue(reading(1_500f))      // crossed
        assertFalse(reading(900f))       // still inside — not a new arrival
        assertFalse(reading(50f))
    }

    @Test
    fun `leaving and returning fires again`() {
        reading(9_000f)
        assertTrue(reading(1_000f))
        assertFalse(reading(9_000f))     // outward crossings are not arrivals
        assertTrue(reading(1_000f))
    }

    @Test
    fun `reset makes the next reading establish the side again`() {
        // The tracker stopped outside and restarted inside. Nothing observed the crossing,
        // so nothing may claim one.
        reading(9_000f)
        fence.reset()
        assertFalse(reading(100f))
        // ...and it is armed again from there.
        reading(9_000f)
        assertTrue(reading(100f))
    }

    // --- sideOf: a fix only claims a side it can prove ---------------------------------

    @Test
    fun `a precise fix claims the side it is on`() {
        assertEquals(FenceSide.INSIDE, sideOf(50f, GOOD_FIX_M, radius))
        assertEquals(FenceSide.OUTSIDE, sideOf(9_000f, GOOD_FIX_M, radius))
    }

    @Test
    fun `the boundary is decided with the accuracy as margin`() {
        // Dead on the radius, and the fix could be either side of it. Neither answer is
        // evidence, so neither is given.
        assertEquals(FenceSide.UNKNOWN, sideOf(radius, GOOD_FIX_M, radius))
        assertEquals(FenceSide.INSIDE, sideOf(radius - GOOD_FIX_M, GOOD_FIX_M, radius))
        assertEquals(FenceSide.OUTSIDE, sideOf(radius + GOOD_FIX_M + 1f, GOOD_FIX_M, radius))
    }

    @Test
    fun `a fix whose error bar spans the fence claims nothing`() {
        // The cold cell-derived fix a phone produces in a car that just started: 3 km from
        // home give or take 2 km. That is not evidence of being either side of a 2 km fence,
        // and a bare `meters > radius` would have read it as "outside".
        assertEquals(FenceSide.UNKNOWN, sideOf(3_000f, 2_000f, radius))
        assertEquals(FenceSide.UNKNOWN, sideOf(1_500f, 2_000f, radius))
    }

    @Test
    fun `a coarse fix cannot manufacture a crossing between two good ones`() {
        // This is the false arrival from the driveway: parked at home the whole time, one bad
        // fix in the middle. With a bare distance test that pair is outside-then-inside.
        reading(50f)                                                   // at home, precise
        assertFalse(fence.onReading(sideOf(2_800f, 2_500f, radius)))   // garbage
        assertFalse(reading(50f))                                      // still at home
    }

    @Test
    fun `a coarse fix does not disarm a genuine approach either`() {
        reading(9_000f)                                         // really outside
        assertFalse(fence.onReading(sideOf(4_000f, 5_000f, radius)))   // useless fix en route
        assertTrue(reading(1_000f))                             // the arrival still lands
    }

    private companion object {
        /** A plain GPS fix: 20 m, four orders of magnitude inside a 2 km fence. */
        const val GOOD_FIX_M = 20f
    }
}
