package pl.bitforge.domofon.domain

import org.json.JSONException
import org.json.JSONObject
import pl.bitforge.domofon.domain.config.DomofonConfig
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** One MQTT subscription: a concrete topic and the QoS to request for it. */
data class Subscription(val topic: String, val qos: Int)

/** A message decoded off the wire, already classified. See [GateProtocol.decode]. */
sealed interface GateEvent {

    /** The bridge's availability topic (its LWT) spoke. */
    data class Availability(val status: BridgeStatus) : GateEvent

    /** A gate signal, parsed and timestamped. Feed it to [GateStateReducer]. */
    data class Signal(
        /** UI vocabulary ("opened", "closing", …), already mapped from the signal name. */
        val state: String,
        val ts: Instant,
        /** The timestamp as published — [GateState.changedAt] renders it verbatim. */
        val rawTs: String,
        /**
         * Retained replays prove nothing about the bridge being alive and lose timestamp
         * ties; a live message is proof of life and wins them. Both rules key off this.
         */
        val retained: Boolean,
    ) : GateEvent

    /** Not actionable: a foreign topic, junk JSON, a bad timestamp. [why] is for the log. */
    data class Ignored(val why: String) : GateEvent
}

/**
 * The hc12 wire vocabulary and framing, pinned to one connection's [DomofonConfig.Topics].
 *
 * Pinned, not read live: the user can edit the topic prefixes while a connection is open,
 * and a message arriving mid-edit must be matched against the prefixes we actually
 * subscribed with, not the half-typed new ones. The connection owner builds a fresh
 * instance per connection from the config it pinned.
 */
class GateProtocol(private val topics: DomofonConfig.Topics) {

    /** Everything one connection subscribes to: each rx signal, plus availability. */
    fun subscriptions(mqtt: DomofonConfig.Mqtt): List<Subscription> =
        SIGNAL_TO_STATE.keys.map { Subscription(topics.rxPrefix + it, mqtt.qosState) } +
            Subscription(topics.availability, mqtt.qosAvailability)

    /** Classifies one inbound message. Never throws; unparseable input becomes [GateEvent.Ignored]. */
    fun decode(topic: String, payload: String, retained: Boolean): GateEvent {
        if (topic == topics.availability) {
            return GateEvent.Availability(
                when (payload) {
                    "online" -> BridgeStatus.ONLINE
                    "offline" -> BridgeStatus.OFFLINE
                    // Junk on the availability topic is not evidence in either direction.
                    else -> BridgeStatus.UNKNOWN
                }
            )
        }

        val state = SIGNAL_TO_STATE[topic.removePrefix(topics.rxPrefix)]
            ?: return GateEvent.Ignored("unknown signal topic: $topic")
        val rawTs = try {
            JSONObject(payload).optString("ts")
        } catch (e: JSONException) {
            return GateEvent.Ignored("rx payload is not JSON: $topic")
        }
        val ts = parseTs(rawTs)
            ?: return GateEvent.Ignored("rx $topic has unparseable ts '$rawTs'")

        return GateEvent.Signal(state, ts, rawTs, retained)
    }

    /**
     * Builds a command publish, or null for an action outside the protocol's vocabulary.
     *
     * There is deliberately no `retain` field on [Command]: a command is **never** retained.
     * hc12-web-service drops retained tx outright (its replay guard), so a retained command
     * would be silently ignored — and without that guard it would re-key the transmitter on
     * every service restart. The transport publishes every command with retain=false.
     */
    fun encodeCommand(action: String, nodeId: Int, payloadKey: String): Command? {
        val signal = ACTION_TO_SIGNAL[action] ?: return null
        return Command(
            topic = topics.txPrefix + signal,
            payload = JSONObject().put(payloadKey, nodeId).toString(),
        )
    }

    data class Command(val topic: String, val payload: String)

    private fun parseTs(raw: String): Instant? {
        if (raw.isEmpty()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                Instant.parse(raw)
            } catch (e2: DateTimeParseException) {
                null
            }
        }
    }

    companion object {
        /**
         * Signal name -> UI label. Deliberately *not* user-configurable: these are the
         * protocol's vocabulary rather than a property of one deployment, and exposing
         * seven more text fields would make the settings screen hostile for no practical
         * gain. The topic prefixes around them are configurable, which is what actually
         * varies.
         */
        val SIGNAL_TO_STATE = mapOf(
            "GateOpened" to GatePolicy.STATE_OPENED,
            "GateClosed" to "closed",
            "GateOpening" to "opening",
            "GateClosing" to "closing",
            "GateStopped" to "stopped",
            "GateStuckOpening" to "stuck_opening",
            "GateStuckClosing" to "stuck_closing",
        )

        val ACTION_TO_SIGNAL = mapOf(
            "open" to "OpenGate",
            "close" to "CloseGate",
            "stop" to "StopGate",
        )
    }
}
