package pl.bitforge.domofon.data.mqtt

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One holder's claim on the shared MQTT connection.
 *
 * This replaces a plain `owners: Int` that four independent components incremented and
 * decremented — and repeatedly drifted: a double release drove the count below what the
 * phone UI held and tore down a connection in use; a leaked slot kept a dead connection
 * "owned" forever with no socket behind it. A lease makes both structurally impossible:
 * releasing is [close], closing twice is a no-op ([closed] flips exactly once), and a lease
 * that is never closed names its holder in [tag] when [GateService] logs the survivors.
 *
 * Idiomatic use: hold it in a `val` for a lifecycle (`onStart` acquire / `onStop` close),
 * or `acquire(tag).use { … }` for one-shot work.
 */
class ConnectionLease internal constructor(
    /** Who holds this — "phone-ui", "settings", "car-session", "arrival", "command". */
    val tag: String,
    private val release: (ConnectionLease) -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) release(this)
    }
}
