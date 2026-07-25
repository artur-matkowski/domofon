package pl.bitforge.domofon.domain

/**
 * The reconnect backoff schedule — the same 1 s → 30 s doubling the HiveMQ built-in
 * reconnector used, extracted so the schedule is a testable value rather than two mutable
 * fields inside the connection code. The connection owner asks for the next delay on every
 * failure and resets on every successful connect.
 */
class ReconnectPolicy(
    private val initialMs: Long = INITIAL_RECONNECT_MS,
    private val maxMs: Long = MAX_RECONNECT_MS,
) {

    private var currentMs = initialMs

    /** The delay to wait before the next attempt; doubles for the one after, up to [maxMs]. */
    @Synchronized
    fun nextDelayMs(): Long {
        val delay = currentMs
        currentMs = (currentMs * 2).coerceAtMost(maxMs)
        return delay
    }

    /** Back to the floor — call on every successful connect. */
    @Synchronized
    fun reset() {
        currentMs = initialMs
    }

    companion object {
        const val INITIAL_RECONNECT_MS = 1_000L
        const val MAX_RECONNECT_MS = 30_000L
    }
}
