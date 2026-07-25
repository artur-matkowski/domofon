package pl.bitforge.domofon.data.mqtt

import pl.bitforge.domofon.config.DomofonConfig
import pl.bitforge.domofon.domain.Subscription

/**
 * The seam between the gate service and the MQTT client library — the one interface a test
 * fakes. [GateService] owns *when* a connection exists; an implementation owns *how*.
 *
 * The contract, sized to what the app actually needs:
 *
 * - [connect] builds a client for [DomofonConfig.Wire] and starts connecting. It returns
 *   the [Handle] immediately and **never throws** — a malformed host or an unreachable
 *   broker arrives as [Listener.onConnectFailed], because callers hold locks and lifecycle
 *   state that must stay consistent whatever the config says.
 * - Callbacks are never invoked synchronously from inside [connect]; the returned handle is
 *   always assigned before the first callback can observe it.
 * - Every callback carries the [Handle] it belongs to. **Handle identity is the staleness
 *   token**: the service compares it against the handle it currently owns and ignores
 *   callbacks from a retired one — a client mid-handshake when the connection was torn down
 *   completes into silence instead of writing to flows that describe no connection at all.
 *   (The old epoch counter, as an object identity.)
 * - There is deliberately no automatic reconnect anywhere behind this interface — see
 *   [HiveMqTransport] for the measured reason. The service schedules rebuilds itself.
 */
interface MqttTransport {

    fun connect(wire: DomofonConfig.Wire, listener: Listener): Handle

    interface Listener {
        /** CONNACK accepted. Subscribe from here — the session is clean on every connect. */
        fun onConnected(handle: Handle)

        /**
         * The connection is gone. [byUser] is true only for the handle's own [Handle.close]
         * — that path reports its own state; everything else is a failure to recover from.
         */
        fun onDisconnected(handle: Handle, byUser: Boolean, cause: Throwable?)

        /** The connect attempt itself failed. [onDisconnected] may fire for it as well. */
        fun onConnectFailed(handle: Handle, cause: Throwable)

        /** One inbound message on any subscribed topic. Payload decoded as UTF-8. */
        fun onMessage(handle: Handle, topic: String, payload: String, retained: Boolean)

        /** The broker refused one subscription — the connection itself is still up. */
        fun onSubscribeFailed(handle: Handle, topic: String)
    }

    interface Handle : AutoCloseable {
        /** Requests the subscriptions; failures per topic arrive as [Listener.onSubscribeFailed]. */
        fun subscribe(subscriptions: List<Subscription>)

        /**
         * Publishes and suspends until the broker acks (or refuses). Returns whether the
         * ack arrived. Callers bound the wait with their own timeout.
         */
        suspend fun publish(topic: String, payload: String, qos: Int, retain: Boolean): Boolean

        /** Asynchronous DISCONNECT. Idempotent; safe on a handle that never connected. */
        override fun close()
    }
}
