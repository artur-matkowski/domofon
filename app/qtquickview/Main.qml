import QtQuick
import QtQuick.Controls

// The property + signal surface of this root object is the ENTIRE Kotlin<->QML
// contract. Keep it small and explicit; later chapters add the camera view here.
Rectangle {
    id: root
    color: "#1e1e2e"

    // Kotlin -> QML. GateRepository pushes state in. bridgeStatus is one of
    // "online" / "offline" / "unknown" — mirroring GateRepository.BridgeStatus. "unknown"
    // is the normal state of a fresh connection, not an error, so only "offline" (the
    // bridge's own LWT) gets the red banner.
    property string gateState: "unknown"
    property string bridgeStatus: "unknown"

    // Kotlin -> QML. The camera snapshot. cameraFrame is a file:// URL to the latest still
    // (a Bitmap can't cross the QtQuickView bridge), refreshed with a changing ?v= query.
    // cameraStatus mirrors CameraFrameGrabber.Status; cameraConfigured gates the whole panel
    // so a build with no RTSP URL shows just the gate controls, exactly as before.
    property string cameraFrame: ""
    property string cameraStatus: "idle"
    property bool cameraConfigured: false

    // Kotlin -> QML. Our own broker connection, mirroring GateRepository.ConnectionStatus:
    // disconnected | connecting | connected | degraded | failed. connectionReason carries a
    // user-facing explanation on failed/degraded and is empty otherwise. Separate from
    // bridgeStatus, which is about the gate service rather than about our socket.
    property string connectionStatus: "disconnected"
    property string connectionReason: ""

    // Kotlin -> QML. Why the last command did not reach the gate — either the bridge refused
    // it (hc12/error) or it never left the phone. Empty when there is nothing to report; it
    // clears itself after ~20 s so it can never be read as the state of a later command.
    property string lastError: ""

    // Kotlin -> QML. Current distance to the home geofence centre, already formatted
    // ("2.3 km from home", "At home"). Empty whenever there is nothing to show — the geofence
    // feature is off, location was not granted, or no fix has arrived yet — which hides the
    // line. HomeDistanceTracker rides the geofence's own location grant, so this only ever
    // appears for a user who turned that feature on.
    property string homeDistance: ""

    // QML -> Kotlin. Button presses flow out to GateRepository.sendCommand().
    signal commandRequested(string action)

    // QML -> Kotlin. Opens SettingsActivity. The activity theme has no action bar, so this
    // is the only way back into setup once the first run is over.
    signal settingsRequested()

    // Everything sizes off the short edge rather than Qt's default control metrics, which
    // render thumbnail-sized on a 1080p phone. One knob, so the layout follows the screen
    // (and the tablet, and the landscape rotation) instead of being pinned to pixels.
    readonly property real unit: Math.min(width, height) / 100

    Button {
        id: settingsButton
        anchors.top: parent.top
        anchors.right: parent.right
        anchors.margins: root.unit * 2
        width: root.unit * 12
        height: root.unit * 12
        text: "⚙"                  // gear
        font.pixelSize: root.unit * 6
        flat: true
        onClicked: root.settingsRequested()
    }

    Column {
        anchors.centerIn: parent
        width: parent.width * 0.88
        spacing: root.unit * 4

        // Camera snapshot. An invisible item is skipped by Column layout, so with no
        // camera configured the controls below center exactly as they did before this panel
        // existed. 16:9 box; the still is letterboxed inside it (PreserveAspectFit).
        Rectangle {
            width: parent.width
            height: root.cameraConfigured ? width * 9 / 16 : 0
            visible: root.cameraConfigured
            color: "#11111a"
            radius: root.unit
            clip: true

            Image {
                anchors.fill: parent
                source: root.cameraFrame
                cache: false            // the URL is reused; the ?v= query is what differs
                asynchronous: true
                fillMode: Image.PreserveAspectFit
                visible: root.cameraFrame !== ""
            }

            Text {
                anchors.centerIn: parent
                visible: root.cameraFrame === ""
                text: root.cameraStatus === "error" ? "Camera unreachable" : "Connecting…"
                color: "#a6adc8"
                font.pixelSize: root.unit * 4
            }
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            text: "Gate: " + root.gateState
            color: "white"
            font.pixelSize: root.unit * 9
            font.bold: true
            elide: Text.ElideRight
        }

        // How far from home. Only present when HomeDistanceTracker has something — the
        // geofence feature is on, located, and a fix is in — so the layout is unchanged for
        // everyone who never enabled it.
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            visible: root.homeDistance !== ""
            text: root.homeDistance
            color: "#a6adc8"
            font.pixelSize: root.unit * 4
        }

        // One status line for two independent questions, in priority order: can we reach
        // the broker at all, and if so what does the broker say about the gate service?
        //
        // Before this existed the answer to the first question was never rendered anywhere.
        // A rejected password, a broker holding no retained state and a healthy connection
        // were the same screen — "Gate: unknown", silence — which is what sent a working
        // connection to the troubleshooting docs.
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap

            readonly property bool connected: root.connectionStatus === "connected"
                                              || root.connectionStatus === "degraded"

            // hc12/available said offline: the state above is the last thing we heard, not
            // necessarily the truth. Saying so beats showing a stale label as fact.
            // "unknown" deliberately shows nothing — before the tri-state, a fresh VPN
            // session wore this banner permanently just because no birth message arrived.
            readonly property bool bridgeDown: connected && root.bridgeStatus === "offline"

            // Muted grey for "this is fine, just letting you know"; red only for something
            // the user may need to act on.
            readonly property bool informational: root.connectionStatus === "connecting"
                                                  || (connected && !bridgeDown
                                                      && root.connectionStatus !== "degraded")

            visible: text !== ""
            text: {
                if (root.connectionStatus === "connecting")
                    return "Connecting to the broker…"
                if (root.connectionStatus === "failed")
                    return root.connectionReason !== "" ? root.connectionReason
                                                        : "Cannot reach the broker"
                if (bridgeDown)
                    return "Gate system unreachable"
                if (root.connectionStatus === "degraded")
                    return root.connectionReason
                // Our socket is up but the gate service has not said "online". Normal for
                // the first moment of a session; if it persists, the bridge is not on the
                // broker and no button will ever do anything — which is a completely
                // different problem from the line below, and used to render identically.
                if (connected && root.bridgeStatus === "unknown")
                    return "Connected — waiting for the gate service"
                if (connected && root.gateState === "unknown")
                    // Connected, subscribed, and the broker simply has no gate state to
                    // give us. Saying so is the entire difference between "it works, the
                    // gate has not moved since the broker last restarted" and "it's broken".
                    return "Connected — no gate state reported yet"
                return ""
            }
            color: informational ? "#a6adc8" : "#f38ba8"
            font.pixelSize: root.unit * 4
            bottomPadding: root.unit * 2
        }

        // Why the last tap did nothing. Always red: unlike the line above, this is never a
        // normal state of affairs, and it is the answer to the question the user is actually
        // asking when they press Open a second time.
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.WordWrap
            visible: root.lastError !== ""
            text: root.lastError
            color: "#f38ba8"
            font.pixelSize: root.unit * 4
            bottomPadding: root.unit * 2
        }

        Repeater {
            model: [
                { label: "Open",  action: "open"  },
                { label: "Close", action: "close" },
                { label: "Stop",  action: "stop"  }
            ]

            delegate: Button {
                required property var modelData

                width: parent.width
                height: root.unit * 15
                text: modelData.label
                font.pixelSize: root.unit * 6
                onClicked: root.commandRequested(modelData.action)
            }
        }
    }
}
