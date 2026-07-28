package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two-id rule for the gate-event notification.
 *
 * Every case here is the same defect in a different position: posting onto an id that still
 * holds a live notification is an *update*, and the car host draws no heads-up for an update.
 */
class NotificationSlotTest {

    private val primary = 1001
    private val alternate = 1004

    private fun slot(vararg live: Int) = freeNotificationSlot(primary, alternate, live.toSet())

    @Test
    fun `an isolated event lands on the primary id`() {
        // Nothing on screen: the common case must look exactly like it did with one id.
        assertEquals(primary, slot())
    }

    @Test
    fun `the second half of a gate cycle does not land on the first half`() {
        // `opening`, then `opened` twenty seconds later. This is the whole bug: the heads-up
        // saying the gate has finished moving was the one being dropped, on every cycle.
        assertEquals(alternate, slot(primary))
    }

    @Test
    fun `and the one after that comes back to the primary`() {
        assertEquals(primary, slot(alternate))
    }

    @Test
    fun `other notifications never displace an event`() {
        // The arrival pop-up and the failure report have their own ids and must not push a
        // state change onto the spare slot — that would leave the real previous one live.
        assertEquals(primary, slot(1002, 1003))
    }

    @Test
    fun `a live event alongside an arrival still alternates`() {
        assertEquals(alternate, slot(primary, 1002))
    }

    @Test
    fun `both slots live is survivable and self-heals`() {
        // Only reachable if a cancel was lost. Posting onto the alternate is degraded — that
        // one is an update — but the caller cancels the primary as it goes, so the very next
        // event finds the primary free rather than the pair being stuck for good.
        assertEquals(alternate, slot(primary, alternate))
        assertEquals(primary, slot(alternate))
    }
}
