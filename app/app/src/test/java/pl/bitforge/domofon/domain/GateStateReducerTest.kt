package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class GateStateReducerTest {

    private val now = Instant.parse("2026-07-25T12:00:00Z")
    private val reducer = GateStateReducer(now = { now })

    private fun signal(
        state: String,
        ts: String,
        retained: Boolean,
    ) = GateEvent.Signal(state, Instant.parse(ts), ts, retained)

    @Test
    fun `first signal always lands`() {
        val out = reducer.reduce(signal("opened", "2026-07-25T11:40:10Z", retained = true))
        assertEquals(GateState("opened", "2026-07-25T11:40:10Z"), out)
    }

    @Test
    fun `retained burst in arbitrary order picks the newest stamp`() {
        // Observed on device: GateClosed 11:40:10 landed before GateOpening 11:40:05.
        assertEquals(
            "closed",
            reducer.reduce(signal("closed", "2026-07-25T11:40:10Z", retained = true))?.state,
        )
        assertNull(reducer.reduce(signal("opening", "2026-07-25T11:40:05Z", retained = true)))
    }

    @Test
    fun `retained needs a strictly newer stamp - a tie is stale`() {
        reducer.reduce(signal("closed", "2026-07-25T11:40:10Z", retained = true))
        assertNull(reducer.reduce(signal("opening", "2026-07-25T11:40:10Z", retained = true)))
    }

    @Test
    fun `live wins ties against retained`() {
        reducer.reduce(signal("closed", "2026-07-25T11:40:10Z", retained = true))
        val out = reducer.reduce(signal("opening", "2026-07-25T11:40:10Z", retained = false))
        assertEquals("opening", out?.state)
    }

    @Test
    fun `live may not move the state backwards`() {
        reducer.reduce(signal("closed", "2026-07-25T11:40:10Z", retained = false))
        assertNull(reducer.reduce(signal("opening", "2026-07-25T11:40:05Z", retained = false)))
    }

    @Test
    fun `future stamp is disbelieved`() {
        assertNull(reducer.reduce(signal("opened", "2026-07-25T12:10:00Z", retained = false)))
    }

    @Test
    fun `future stamp within clock-skew tolerance is accepted`() {
        // 300 s tolerance; 2 minutes ahead is plausibly just skew.
        val out = reducer.reduce(signal("opened", "2026-07-25T12:02:00Z", retained = false))
        assertEquals("opened", out?.state)
    }

    @Test
    fun `rejected future stamp does not poison the newest-ts memory`() {
        assertNull(reducer.reduce(signal("opened", "2026-07-25T12:10:00Z", retained = false)))
        // A genuine message from *now* must still land — the whole point of the guard.
        val out = reducer.reduce(signal("closed", "2026-07-25T11:59:00Z", retained = false))
        assertEquals("closed", out?.state)
    }

    @Test
    fun `reset forgets the connection's memory`() {
        reducer.reduce(signal("closed", "2026-07-25T11:40:10Z", retained = true))
        reducer.reset()
        // Older than the pre-reset newest, but a fresh connection starts from nothing.
        val out = reducer.reduce(signal("opening", "2026-07-25T11:00:00Z", retained = true))
        assertEquals("opening", out?.state)
    }
}
