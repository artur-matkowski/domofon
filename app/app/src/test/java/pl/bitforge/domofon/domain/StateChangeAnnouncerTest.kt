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

    /** No command has ever been sent: the silence branch must never engage. */
    private fun change(state: String, nowMs: Long = 0L) =
        announcer.shouldAnnounce(state, lastCommandAtMs = 0L, nowMs = nowMs)

    // --- rule 1: learning is not news -------------------------------------------------

    @Test
    fun `the state a fresh connection learns is not announced`() {
        // Exactly the bug: opening the car app acquires a lease, the retained rx topics
        // replay, and gateState moves unknown -> closed without the gate moving at all.
        assertFalse(change(GatePolicy.STATE_UNKNOWN))
        assertFalse(change(GatePolicy.STATE_CLOSED))
    }

    @Test
    fun `a real move between known states is announced`() {
        change(GatePolicy.STATE_CLOSED)
        assertTrue(change(GatePolicy.STATE_OPENING))
        assertTrue(change(GatePolicy.STATE_OPENED))
    }

    @Test
    fun `teardown then reconnect announces nothing`() {
        change(GatePolicy.STATE_CLOSED)
        assertTrue(change(GatePolicy.STATE_OPENED))

        // teardown() resets gateState to unknown — GateService invariant 4.
        assertFalse(change(GatePolicy.STATE_UNKNOWN))
        // ...and the next connection learns the same state it already had. Not news, and
        // not a move backwards either.
        assertFalse(change(GatePolicy.STATE_OPENED))
    }

    @Test
    fun `the reset itself is never announced`() {
        change(GatePolicy.STATE_CLOSED)
        assertFalse(change(GatePolicy.STATE_UNKNOWN))
    }

    @Test
    fun `an unchanged state is not a change`() {
        change(GatePolicy.STATE_CLOSED)
        assertFalse(change(GatePolicy.STATE_CLOSED))
    }

    // --- rule 2: your own tap silences exactly one change -----------------------------

    @Test
    fun `a command silences the change it caused and only that one`() {
        val tap = 10_000L
        announcer.shouldAnnounce(GatePolicy.STATE_CLOSED, 0L, 0L)   // learned on connect

        // You tapped Open. "opening" is the gate doing what you just told it to.
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_OPENING, tap, tap + 1_000))
        // "opened" is the thing you were actually waiting for — Artur's rule: the silence is
        // released by the first change, not by the clock.
        assertTrue(announcer.shouldAnnounce(GatePolicy.STATE_OPENED, tap, tap + 4_000))
    }

    @Test
    fun `a stale command does not silence anything`() {
        val tap = 10_000L
        announcer.shouldAnnounce(GatePolicy.STATE_CLOSED, 0L, 0L)

        // Nothing happened for longer than the window; whatever moves the gate now is not us.
        assertTrue(
            announcer.shouldAnnounce(
                GatePolicy.STATE_OPENING, tap,
                tap + StateChangeAnnouncer.COMMAND_SILENCE_MS + 1,
            )
        )
    }

    @Test
    fun `each command gets its own silence`() {
        announcer.shouldAnnounce(GatePolicy.STATE_CLOSED, 0L, 0L)

        val first = 10_000L
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_OPENING, first, first + 500))
        assertTrue(announcer.shouldAnnounce(GatePolicy.STATE_OPENED, first, first + 3_000))

        val second = 60_000L
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_CLOSING, second, second + 500))
        assertTrue(announcer.shouldAnnounce(GatePolicy.STATE_CLOSED, second, second + 3_000))
    }

    @Test
    fun `a command sent while disconnected does not eat the state the reconnect learns`() {
        // The notification-button path: tap with the app closed, which acquires a lease and
        // connects. Learning is already silent for its own reason, and the tap's one silence
        // must still be there for the change that follows.
        val tap = 10_000L
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_UNKNOWN, tap, tap + 100))
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_CLOSED, tap, tap + 2_000))
        assertFalse(announcer.shouldAnnounce(GatePolicy.STATE_OPENING, tap, tap + 3_000))
        assertTrue(announcer.shouldAnnounce(GatePolicy.STATE_OPENED, tap, tap + 9_000))
    }
}
