package pl.bitforge.domofon.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFenceCrossingTest {

    private val radius = 2_000f
    private val fence = HomeFenceCrossing(radius)

    @Test
    fun `the first reading only establishes which side we are on`() {
        // The in-app mirror of omitting INITIAL_TRIGGER_ENTER: at 2 km the house is inside
        // the fence, so firing on the first reading would announce an arrival every time the
        // car screen opened on the driveway.
        assertFalse(fence.onReading(50f))
    }

    @Test
    fun `first reading outside does not fire either`() {
        assertFalse(fence.onReading(9_000f))
    }

    @Test
    fun `crossing inward fires exactly once`() {
        fence.onReading(9_000f)
        assertFalse(fence.onReading(4_000f))     // still outside
        assertTrue(fence.onReading(1_500f))      // crossed
        assertFalse(fence.onReading(900f))       // still inside — not a new arrival
        assertFalse(fence.onReading(50f))
    }

    @Test
    fun `leaving and returning fires again`() {
        fence.onReading(9_000f)
        assertTrue(fence.onReading(1_000f))
        assertFalse(fence.onReading(9_000f))     // outward crossings are not arrivals
        assertTrue(fence.onReading(1_000f))
    }

    @Test
    fun `the radius itself counts as inside`() {
        fence.onReading(radius + 1f)
        assertTrue(fence.onReading(radius))
    }

    @Test
    fun `reset makes the next reading establish the side again`() {
        // The tracker stopped outside and restarted inside. Nothing observed the crossing,
        // so nothing may claim one.
        fence.onReading(9_000f)
        fence.reset()
        assertFalse(fence.onReading(100f))
        // ...and it is armed again from there.
        fence.onReading(9_000f)
        assertTrue(fence.onReading(100f))
    }
}
