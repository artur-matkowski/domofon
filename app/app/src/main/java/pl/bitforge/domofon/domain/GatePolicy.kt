package pl.bitforge.domofon.domain

import pl.bitforge.domofon.domain.camera.AudioStatus

/**
 * The presentation rules every surface shares — worded and decided once, so the phone
 * panel, the car screen and the notification can never disagree.
 */
object GatePolicy {

    /**
     * The gate vocabulary, in one place. These are the values of
     * [GateProtocol.SIGNAL_TO_STATE] — the protocol owns which *signal* maps to which, this
     * owns the strings themselves, so a rule below can name a state instead of repeating a
     * literal. [STATE_UNKNOWN] is the only one with no signal behind it: it is what
     * [GateState.state] holds when nothing has been heard on the current connection.
     */
    const val STATE_UNKNOWN = "unknown"
    const val STATE_OPENED = "opened"
    const val STATE_CLOSED = "closed"
    const val STATE_OPENING = "opening"
    const val STATE_CLOSING = "closing"
    const val STATE_STOPPED = "stopped"
    const val STATE_STUCK_OPENING = "stuck_opening"
    const val STATE_STUCK_CLOSING = "stuck_closing"

    /**
     * Open, or travelling toward open. The rule below is the only thing that reads this.
     *
     * [STATE_STUCK_OPENING] belongs here: a gate jammed on its way open is an *open* gate as
     * far as anyone driving up to it is concerned, and retracting it is the move that clears
     * the jam. [STATE_STOPPED] deliberately does not — halted mid-travel it is neither, and
     * Open is the safer default for a driver who wants in.
     */
    private val OPEN_ISH = setOf(STATE_OPENED, STATE_OPENING, STATE_STUCK_OPENING)

    /**
     * The single home of the button rule: a gate that is open or heading open offers Close,
     * everything else offers Open. The car screen and the notification action both call
     * this, which is what keeps them from contradicting each other.
     *
     * It used to be `state == STATE_OPENED`, which made the mid-travel states offer an
     * action that does nothing — a gate already `opening` showed "Open gate", and the one
     * button on the car screen was the wrong one for the entire time the gate was moving
     * (Artur, live testing 2026-07-27).
     */
    fun primaryAction(state: String): PrimaryAction =
        if (state in OPEN_ISH) PrimaryAction("Close gate", "close")
        else PrimaryAction("Open gate", "open")

    /**
     * The single gate/connection status line, rendered verbatim by every surface. It exists
     * because the phone and the car used to derive this text independently and had already
     * drifted ("Gate system unreachable" vs "Gate — unreachable"); with one function there
     * is no second place for the wording to disagree.
     *
     * Two independent questions in priority order, mirroring [ConnectionState] then
     * [BridgeStatus]: can we reach the broker at all, and if so what does the broker say
     * about the gate service. The command error is deliberately *not* folded in here — a
     * refused command is never a normal state and gets its own line on each surface, so
     * this function stays about connection-and-state only.
     */
    fun gateStatusLine(
        connection: ConnectionState,
        bridge: BridgeStatus,
        state: String,
    ): String = when (connection.status) {
        // A hard failure names itself; "connecting…" for a rejected password made a dead
        // connection look like a slow VPN for the length of a drive.
        ConnectionStatus.FAILED -> "Gate — ${connection.reason}"

        // UNKNOWN is the normal opening state of a fresh (VPN) connection and must not read
        // as an outage; once retained state or a live message arrives the line carries real
        // content.
        ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTED ->
            if (state == STATE_UNKNOWN) "Gate — connecting…" else "Gate — $state"

        ConnectionStatus.CONNECTED, ConnectionStatus.DEGRADED ->
            when (bridge) {
                // The bridge's own LWT — a fact worth alarming about.
                BridgeStatus.OFFLINE -> "Gate — unreachable"

                // Socket up but the service has not said "online". Silence from the gate
                // service is its own answer, and a different one from "the service is here
                // and the gate simply has not moved" — folding the two together is what let
                // a bridge that was not on the broker at all read as a healthy connection.
                BridgeStatus.UNKNOWN ->
                    if (state == STATE_UNKNOWN) "Gate — waiting for the service"
                    else "Gate — $state"

                BridgeStatus.ONLINE ->
                    if (state == STATE_UNKNOWN) "Gate — no state reported"
                    else "Gate — $state"
            }
    }

    /**
     * Why the gate is silent, when silence is a fault rather than a quiet gate.
     *
     * Only the HTTP camera path can produce this: its audio is a separate stream that can die
     * on its own while stills keep arriving, and nothing else on screen would change. On the
     * RTSP path audio shares the picture's session, so a failure there is already the camera
     * status and this stays empty.
     *
     * Empty means "render nothing", the same convention as the distance and error lines.
     * [AudioStatus.CONNECTING] is deliberately silent too: an audio stream takes a moment to
     * come up, and announcing that would put a line on screen during every normal start.
     */
    fun cameraAudioNotice(audio: AudioStatus): String = when (audio) {
        AudioStatus.ERROR -> "Gate audio unavailable"
        AudioStatus.NONE, AudioStatus.CONNECTING, AudioStatus.PLAYING -> ""
    }
}
