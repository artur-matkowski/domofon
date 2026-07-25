package pl.bitforge.domofon.data.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.CompletableDeferred
import pl.bitforge.domofon.domain.config.DomofonConfig
import pl.bitforge.domofon.domain.Subscription
import java.util.concurrent.TimeUnit

/**
 * [MqttTransport] over the HiveMQ MQTT3 client — the only file that imports `com.hivemq.*`.
 *
 * Everything HiveMQ-specific that was learned the hard way lives here:
 *
 * - **Transport timeouts.** HiveMQ's defaults assume a wire that is either up or down. A
 *   VPN link is neither: SYNs can hang for the platform default (minutes), so the
 *   handshake is capped at 10 s on both the socket and the MQTT layer.
 * - **Deliberately NOT `.automaticReconnect()`.** Measured on device: after the network is
 *   yanked out from under a live connection, every one of HiveMQ's own reconnect attempts
 *   is refused by the broker with CONNACK NOT_AUTHORIZED, for as long as it keeps trying —
 *   while a cold start of the app, with the *same* credentials and the *same* client id,
 *   connects instantly at that same moment. Whatever the reconnector re-sends is not what
 *   we handed the builder, so the only trustworthy reconnect is a client built from
 *   scratch. [GateService] does that, with the backoff the built-in one was configured
 *   for. (This was the original "the app does not connect to my broker" report: one
 *   network transition and the app could never reach the broker again, silently, until it
 *   was force-stopped.)
 * - **Clean session** — every rx topic is retained, so a queued backlog would only replay
 *   state the broker is about to hand us anyway.
 * - **Anonymous means no simpleAuth block at all**: sending an empty one makes anonymous
 *   brokers reject the connection.
 *
 * Log lines never carry the host, the username or the password: logcat is readable by adb
 * and by anything holding READ_LOGS, and a user's broker address is theirs.
 */
class HiveMqTransport : MqttTransport {

    override fun connect(
        wire: DomofonConfig.Wire,
        listener: MqttTransport.Listener,
    ): MqttTransport.Handle {
        val handle = HiveMqHandle(listener)
        try {
            start(handle, wire, listener)
        } catch (e: Exception) {
            // The builder throws synchronously on a malformed host. The contract says
            // failures arrive through the listener, so the caller's lock/lifecycle state
            // never has to survive an exception from here.
            Log.w(TAG, "MQTT client build failed", e)
            listener.onConnectFailed(handle, e)
        }
        return handle
    }

    private fun start(
        handle: HiveMqHandle,
        wire: DomofonConfig.Wire,
        listener: MqttTransport.Listener,
    ) {
        val builder = MqttClient.builder()
            .useMqttVersion3()
            .identifier(wire.broker.clientId)
            .serverHost(wire.broker.host)
            .serverPort(wire.broker.port)
            .transportConfig()
            .socketConnectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .mqttConnectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .applyTransportConfig()
            .addConnectedListener { listener.onConnected(handle) }
            .addDisconnectedListener { context ->
                listener.onDisconnected(
                    handle,
                    byUser = context.source == MqttDisconnectSource.USER,
                    cause = context.cause,
                )
            }

        // TLS is the user's choice because a broker reachable only inside their own VPN is
        // a legitimate setup, but it is the one they should be making deliberately — the
        // settings screen spells out what "off" costs.
        if (wire.broker.tls) builder.sslWithDefaultConfig()

        val client = builder.buildAsync()
        handle.client = client

        val connect = client.connectWith()
        val authenticated =
            if (wire.broker.username.isNotEmpty() || wire.broker.password.isNotEmpty()) {
                connect.simpleAuth()
                    .username(wire.broker.username)
                    .password(wire.broker.password.toByteArray())
                    .applySimpleAuth()
            } else {
                connect
            }

        authenticated
            .cleanSession(true)
            .keepAlive(wire.mqtt.keepAliveSeconds)
            .send()
            .whenComplete { _, err ->
                if (err != null) {
                    Log.w(TAG, "MQTT connect failed", err)
                    listener.onConnectFailed(handle, err)
                }
            }
    }

    private class HiveMqHandle(
        private val listener: MqttTransport.Listener,
    ) : MqttTransport.Handle {

        @Volatile
        var client: Mqtt3AsyncClient? = null

        override fun subscribe(subscriptions: List<Subscription>) {
            val c = client ?: return
            subscriptions.forEach { sub ->
                c.subscribeWith()
                    .topicFilter(sub.topic)
                    .qos(qos(sub.qos))
                    .callback { publish ->
                        listener.onMessage(
                            this,
                            topic = publish.topic.toString(),
                            payload = String(publish.payloadAsBytes),
                            retained = publish.isRetain,
                        )
                    }
                    .send()
                    .whenComplete { _, err ->
                        if (err != null) {
                            Log.w(TAG, "subscribe ${sub.topic} failed", err)
                            listener.onSubscribeFailed(this, sub.topic)
                        }
                    }
            }
        }

        override suspend fun publish(
            topic: String,
            payload: String,
            qos: Int,
            retain: Boolean,
        ): Boolean {
            val c = client ?: return false
            val acked = CompletableDeferred<Boolean>()
            c.publishWith()
                .topic(topic)
                .qos(qos(qos))
                .retain(retain)
                .payload(payload.toByteArray())
                .send()
                .whenComplete { _, err ->
                    // The resolved topic, not just the signal name: a wrong prefix is the
                    // one failure this line can prove or rule out at a glance, and it
                    // carries no host and no credentials.
                    if (err != null) Log.w(TAG, "publish $topic failed", err)
                    else Log.i(TAG, "tx -> $topic")
                    acked.complete(err == null)
                }
            return acked.await()
        }

        override fun close() {
            val c = client ?: return
            client = null
            c.disconnect().whenComplete { _, err ->
                if (err != null) Log.i(TAG, "disconnect completed with error", err)
            }
        }

        /**
         * A clamped config value to the client's enum. The fallback can only trigger if a
         * caller bypasses ConfigStore's clamping, but `fromCode` returns null and a crash
         * in the subscribe path would take the whole connection down with it.
         */
        private fun qos(code: Int): MqttQos = MqttQos.fromCode(code) ?: MqttQos.AT_LEAST_ONCE
    }

    private companion object {
        const val TAG = "Domofon"
        const val CONNECT_TIMEOUT_S = 10L
    }
}
