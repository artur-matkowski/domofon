package pl.bitforge.domofon.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification rule's whole table. Every case here is a bug that shipped or a rule Artur
 * asked for, so nothing in it is decoration.
 */
class StateChangeAnnouncerTest {

    private val announcer = StateChangeAnnouncer()

    /**
     * A message the bridge published while we were listening, with nothing on screen — the
     * default the whole table is read in, because it is the only combination that announces.
     */
    private fun live(state: String, surfaceVisible: Boolean = false) =
        announcer.shouldAnnounce(state, live = true, surfaceVisible = surfaceVisible)

    /** A retained message: the broker replaying what it was already holding. */
    private fun retained(state: String, surfaceVisible: Boolean = false) =
        announcer.shouldAnnounce(state, live = false, surfaceVisible = surfaceVisible)

    /** The reset [GateService.teardown] writes; it carries no message, so it is never live. */
    private fun disconnect() = retained(GatePolicy.STATE_UNKNOWN)

    // --- retained is never news --------------------------------------------------------

    @Test
    fun `the state a fresh connection learns is not announced`() {
        // Exactly the bug: opening the car app acquires a lease, the retained rx topics
        // replay, and gateState moves unknown -> closed without the gate moving at all.
        assertFalse(disconnect())
        assertFalse(retained(GatePolicy.STATE_CLOSED))
    }

    @Test
    fun `a retained burst that moves the state twice announces nothing`() {
        // The reason this rule replaced "a transition out of unknown is learning". Retained
        // rx topics are last-value-per-signal and arrive in arbitrary order, so the burst can
        // move the state more than once — and every move after the first looked like news.
        assertFalse(retained(GatePolicy.STATE_STOPPED))
        assertFalse(retained(GatePolicy.STATE_CLOSED))
        assertFalse(retained(GatePolicy.STATE_OPENED))
    }

    @Test
    fun `a retained message is silent even mid-connection`() {
        assertFalse(retained(GatePolicy.STATE_CLOSED))
        assertTrue(live(GatePolicy.STATE_OPENING))
        // A late retained arrival is still the broker's memory, not the gate moving.
        assertFalse(retained(GatePolicy.STATE_CLOSED))
    }

    @Test
    fun `what a retained message taught is still remembered`() {
        // Silent, but not ignored: the burst is how this connection knows where the gate is,
        // so the first live change must be judged against it.
        assertFalse(retained(GatePolicy.STATE_CLOSED))
        assertTrue(live(GatePolicy.STATE_OPENING))
    }

    // --- a live move between two known states -----------------------------------------

    @Test
    fun `a real move between known states is announced`() {
        retained(GatePolicy.STATE_CLOSED)
        assertTrue(live(GatePolicy.STATE_OPENING))
        assertTrue(live(GatePolicy.STATE_OPENED))
    }

    @Test
    fun `the first message of a connection is learning even when it is live`() {
        // We connected while the bridge happened to be publishing. It still only tells us
        // where the gate already was.
        assertFalse(live(GatePolicy.STATE_CLOSED))
        assertTrue(live(GatePolicy.STATE_OPENING))
    }

    @Test
    fun `teardown then reconnect announces nothing`() {
        retained(GatePolicy.STATE_CLOSED)
        assertTrue(live(GatePolicy.STATE_OPENED))

        // teardown() resets gateState to unknown — GateService invariant 4.
        assertFalse(disconnect())
        // ...and the next connection learns the same state it already had. Not news, and
        // not a move backwards either.
        assertFalse(retained(GatePolicy.STATE_OPENED))
    }

    @Test
    fun `the reset itself is never announced`() {
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(disconnect())
    }

    @Test
    fun `an unchanged state is not a change`() {
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(live(GatePolicy.STATE_CLOSED))
    }

    // --- nothing is news to someone already reading it --------------------------------

    @Test
    fun `a change is not announced while a surface is in front`() {
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(live(GatePolicy.STATE_OPENING, surfaceVisible = true))
    }

    @Test
    fun `backgrounding the app puts the notifications back`() {
        // The head unit switching to Maps is the moment a notification becomes the only way
        // to reach the driver, so it must engage on the very next change.
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(live(GatePolicy.STATE_OPENING, surfaceVisible = true))
        assertTrue(live(GatePolicy.STATE_OPENED, surfaceVisible = false))
    }

    @Test
    fun `a suppressed change still counts as seen`() {
        // Ordering inside shouldAnnounce: the surface check is last, so the state it
        // suppressed is still recorded. Otherwise the gate could move away and back while the
        // app was open and the return would read as a change from the state before it all.
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(live(GatePolicy.STATE_OPENED, surfaceVisible = true))
        assertFalse(live(GatePolicy.STATE_OPENED, surfaceVisible = false))
    }

    @Test
    fun `learning is still silent when a surface is in front`() {
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_UNKNOWN, false, true))
        assertFalse(retained(GatePolicy.STATE_CLOSED, surfaceVisible = true))
    }

    // --- the notification-tap path -----------------------------------------------------

    @Test
    fun `the gate cycle you asked for from a notification is announced`() {
        // The whole point of dropping the own-tap silence (Artur, live testing 2026-07-29).
        // Nothing is on screen — the notification that offered the button dismissed itself on
        // the tap — so the movement it caused is the only feedback there is.
        assertFalse(retained(GatePolicy.STATE_CLOSED))   // the command lease connects, learns
        assertTrue(live(GatePolicy.STATE_OPENING))       // ~1-2 s later: it heard you
        assertTrue(live(GatePolicy.STATE_OPENED))        // ~20 s later: it finished
    }

    @Test
    fun `the gate cycle you asked for from a screen is not announced`() {
        // The same taps with the car screen up: the pane already shows both of these, and a
        // heads-up would cover the screen the driver chose.
        retained(GatePolicy.STATE_CLOSED)
        assertFalse(live(GatePolicy.STATE_OPENING, surfaceVisible = true))
        assertFalse(live(GatePolicy.STATE_OPENED, surfaceVisible = true))
    }
}
