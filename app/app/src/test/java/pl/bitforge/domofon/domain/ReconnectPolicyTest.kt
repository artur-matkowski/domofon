package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `doubles from the floor and caps at the ceiling`() {
        val policy = ReconnectPolicy()
        val delays = (1..7).map { policy.nextDelayMs() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun `reset returns to the floor`() {
        val policy = ReconnectPolicy()
        repeat(4) { policy.nextDelayMs() }
        policy.reset()
        assertEquals(1_000L, policy.nextDelayMs())
    }
}
