package pl.bitforge.domofon.data.mqtt

import pl.bitforge.domofon.domain.config.DomofonConfig
import pl.bitforge.domofon.domain.Subscription

/**
 * Scriptable [MqttTransport]. Connects synchronously into a recorded [FakeHandle]; the test
 * drives the connection by calling the handle's `deliver*` methods — never from inside
 * [connect], per the transport contract.
 */
class FakeTransport : MqttTransport {

    val handles = mutableListOf<FakeHandle>()

    override fun connect(
        wire: DomofonConfig.Wire,
        listener: MqttTransport.Listener,
    ): MqttTransport.Handle = FakeHandle(wire, listener).also { handles += it }

    class FakeHandle(
        val wire: DomofonConfig.Wire,
        private val listener: MqttTransport.Listener,
    ) : MqttTransport.Handle {

        val subscriptions = mutableListOf<Subscription>()
        val published = mutableListOf<Publish>()
        var closed = false
            private set

        /** What [publish] answers — the broker ack. */
        var ackPublishes = true

        data class Publish(val topic: String, val payload: String, val qos: Int, val retain: Boolean)

        // --- test controls: play the broker ------------------------------------------

        fun connectComplete() = listener.onConnected(this)

        fun connectFailed(cause: Throwable) = listener.onConnectFailed(this, cause)

        fun dropConnection(cause: Throwable? = null) =
            listener.onDisconnected(this, byUser = false, cause = cause)

        fun deliver(topic: String, payload: String, retained: Boolean = false) =
            listener.onMessage(this, topic, payload, retained)

        fun refuseSubscription(topic: String) = listener.onSubscribeFailed(this, topic)

        // --- Handle -------------------------------------------------------------------

        override fun subscribe(subscriptions: List<Subscription>) {
            this.subscriptions += subscriptions
        }

        override suspend fun publish(topic: String, payload: String, qos: Int, retain: Boolean): Boolean {
            published += Publish(topic, payload, qos, retain)
            return ackPublishes
        }

        override fun close() {
            closed = true
            listener.onDisconnected(this, byUser = true, cause = null)
        }
    }
}
