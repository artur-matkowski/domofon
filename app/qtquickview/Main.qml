import QtQuick
import QtQuick.Controls

// The property + signal surface of this root object is the ENTIRE Kotlin<->QML
// contract. Keep it small and explicit; later chapters add the camera view here.
Rectangle {
    id: root
    color: "#1e1e2e"

    // Kotlin -> QML. GateRepository pushes state in.
    property string gateState: "unknown"
    property bool bridgeOnline: false

    // QML -> Kotlin. Button presses flow out to GateRepository.sendCommand().
    signal commandRequested(string action)

    // Everything sizes off the short edge rather than Qt's default control metrics, which
    // render thumbnail-sized on a 1080p phone. One knob, so the layout follows the screen
    // (and the tablet, and the landscape rotation) instead of being pinned to pixels.
    readonly property real unit: Math.min(width, height) / 100

    Column {
        anchors.centerIn: parent
        width: parent.width * 0.88
        spacing: root.unit * 4

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            text: "Gate: " + root.gateState
            color: "white"
            font.pixelSize: root.unit * 9
            font.bold: true
            elide: Text.ElideRight
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            // hc12/available said offline: the state above is the last thing we heard, not
            // necessarily the truth. Saying so beats showing a stale label as fact.
            visible: !root.bridgeOnline
            text: "Gate system unreachable"
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
