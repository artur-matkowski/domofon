package pl.bitforge.domofon.domain

/**
 * The gate's last reported state and the raw timestamp it was reported with.
 * [state] is protocol vocabulary ("opened", "closing", …) — see [GateProtocol] — with
 * [GatePolicy.STATE_UNKNOWN] standing in whenever nothing has been heard on the current
 * connection.
 */
data class GateState(val state: String, val changedAt: String)

/** A button label paired with the action it sends, so every surface agrees on both. */
data class PrimaryAction(val label: String, val action: String)

/**
 * What we know about the bridge, which is genuinely three-valued. A boolean here was the
 * VPN bug: it defaulted to "offline", and a bridge whose birth message is not retained
 * never corrects a freshly connected client — so every away-from-home session opened with
 * "unreachable" as a *default*, presented as a fact.
 */
enum class BridgeStatus {
    /** No availability message seen on this connection — includes "not connected at all". */
    UNKNOWN,

    /** The availability topic said "online", or a live state message proved it. */
    ONLINE,

    /** The availability topic said "offline" — the bridge's LWT fired. */
    OFFLINE,
}

/**
 * What the app is doing about the broker connection.
 *
 * Distinct from [BridgeStatus], which describes the *gate service*. This describes our own
 * socket, and it exists because without it every one of these renders identically on the
 * phone — "Gate: unknown", no error. A rejected password, a broker with nothing retained
 * on it, and a denied subscription ACL were one indistinguishable screen.
 */
enum class ConnectionStatus {
    /** Nothing holds the connection; we are not trying. */
    DISCONNECTED,

    /** A handshake is in flight. */
    CONNECTING,

    /** CONNACK accepted and the subscriptions were granted. */
    CONNECTED,

    /** Connected, but the broker refused a subscription — we are deaf to some or all state. */
    DEGRADED,

    /** The attempt failed; [ConnectionState.reason] says how. */
    FAILED,
}

/**
 * [ConnectionStatus] plus a line the user can act on.
 *
 * The reason is deliberately free of the host, username and password. It is rendered on the
 * phone, on the car screen and in Settings — the same reasoning that keeps the broker
 * address out of the log lines in the MQTT layer applies with more force to something on
 * screen.
 */
data class ConnectionState(val status: ConnectionStatus, val reason: String = "")
