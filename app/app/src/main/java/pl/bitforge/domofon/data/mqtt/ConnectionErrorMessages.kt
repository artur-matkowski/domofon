package pl.bitforge.domofon.data.mqtt

import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A connection-failure cause turned into one line worth showing a user.
 *
 * The line is deliberately free of the host, username and password — it is rendered on the
 * phone, on the car screen and in Settings, and a user's broker address is theirs, not ours
 * to scatter around.
 */
object ConnectionErrorMessages {

    /**
     * The cause chain is walked because the interesting exception is usually wrapped —
     * HiveMQ reports a `ConnectionFailedException` whose cause is the socket or TLS error,
     * and the CONNACK rejection that names a bad password sits a level down too. Bounded,
     * because a self-referential cause would otherwise spin here forever.
     */
    fun describe(cause: Throwable?): String {
        var e: Throwable = cause ?: return "Connection lost"
        repeat(8) {
            when (val current = e) {
                is Mqtt3ConnAckException -> return when (current.mqttMessage.returnCode) {
                    Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD ->
                        "Broker rejected the username or password"
                    Mqtt3ConnAckReturnCode.NOT_AUTHORIZED -> "Broker refused authorization"
                    Mqtt3ConnAckReturnCode.IDENTIFIER_REJECTED -> "Broker rejected the client id"
                    Mqtt3ConnAckReturnCode.SERVER_UNAVAILABLE -> "Broker is unavailable"
                    Mqtt3ConnAckReturnCode.UNSUPPORTED_PROTOCOL_VERSION ->
                        "Broker does not speak MQTT 3.1.1"
                    else -> "Broker refused the connection"
                }
                // The classic misconfiguration: TLS on, pointed at the plaintext port.
                is SSLException -> return "TLS handshake failed — check the TLS setting and port"
                is UnknownHostException -> return "Cannot resolve the broker address"
                is NoRouteToHostException -> return "No route to the broker"
                is ConnectException -> return "Broker refused the connection or is unreachable"
                is SocketTimeoutException -> return "Timed out reaching the broker"
            }
            // Netty's ConnectTimeoutException lives under the shaded package, and naming a
            // relocated internal class here would be a rule that silently stops matching on
            // the next HiveMQ bump. The name is the stable part.
            if (e.javaClass.simpleName.contains("Timeout")) return "Timed out reaching the broker"
            e = e.cause ?: return e.javaClass.simpleName
        }
        return cause.javaClass.simpleName
    }
}
