import QtQuick
import QtQuick.Controls

// The property + signal surface of this root object is the ENTIRE Kotlin<->QML
// contract. Keep it small and explicit; later chapters add the camera view here.
Rectangle {
    id: root
    color: "#1e1e2e"

    // Kotlin -> QML. GateRepository pushes state in.
    property string gateState: "unknown"

    // QML -> Kotlin. Button presses flow out to GateRepository.sendCommand().
    signal commandRequested(string action)

    Column {
        anchors.centerIn: parent
        spacing: 32

        Text {
            text: "Gate: " + root.gateState
            color: "white"
            font.pixelSize: 48
            anchors.horizontalCenter: parent.horizontalCenter
        }

        Row {
            spacing: 24
            anchors.horizontalCenter: parent.horizontalCenter

            Button {
                text: "Open"
                onClicked: root.commandRequested("open")
            }
            Button {
                text: "Close"
                onClicked: root.commandRequested("close")
            }
            Button {
                text: "Stop"
                onClicked: root.commandRequested("stop")
            }
        }
    }
}
