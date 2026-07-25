import QtQuick
import QtQuick.Controls

// The property + signal surface of this root object is the ENTIRE Kotlin<->QML
// contract. Keep it small and explicit; later chapters add the camera view here.
Rectangle {
    id: root
    color: Theme.base

    // Kotlin -> QML. The single gate/connection status line, already composed by the backend
    // (GateRepository, via gateStatusLine) so the phone and the Android Auto screen word it
    // identically — e.g. "Gate — opened", "Gate — waiting for the service", "Gate — connecting…".
    // Empty hides the line. The raw fields it is derived from no longer cross the bridge: the
    // wording lives in one place so the two frontends can never disagree.
    property string statusText: ""

    // Kotlin -> QML. The camera snapshot. cameraFrame is a `data:image/jpeg;base64,…` URI
    // holding the still itself (a Bitmap can't cross the QtQuickView bridge, and a string
    // can): every frame is inherently a new URI, so the change signal always fires, and no
    // gate picture ever touches the disk. It used to be a file:// URL alternating between two
    // cache files — same guarantee, but the files outlived the app whenever the process was
    // killed. The Images below must keep `cache: false`, or one entry per frame accumulates
    // in QtQuick's pixmap cache forever.
    // cameraStatus mirrors CameraFrameGrabber.Status; cameraConfigured gates the whole panel
    // so a build with no camera configured shows just the gate controls, exactly as before.
    property string cameraFrame: ""
    property string cameraStatus: "idle"
    property bool cameraConfigured: false

    // Kotlin -> QML. Why the gate is silent, when silence is a fault rather than a quiet
    // gate — worded by GatePolicy. Only ever set on the HTTP camera path, whose audio is a
    // stream of its own that can die while the pictures keep arriving; on the RTSP path a
    // dead audio track is a dead session and cameraStatus already says so. Empty hides it.
    property string audioNotice: ""

    // Kotlin -> QML. Why the last command never left the phone — no connection to the broker,
    // or a publish the broker would not take. Empty when there is nothing to report; it
    // clears itself after ~20 s so it can never be read as the state of a later command.
    property string lastError: ""

    // Kotlin -> QML. Current distance to the home geofence centre, already formatted
    // ("1.5 km · approaching home", "120 m · at home"). Empty whenever there is nothing to show — the geofence
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
            id: cameraPanel
            width: parent.width
            height: root.cameraConfigured ? width * 9 / 16 : 0
            visible: root.cameraConfigured
            color: Theme.surface
            radius: root.unit
            clip: true

            // Which of the two Images below is the one on screen. Double-buffering, because
            // a single Image pointed at a new URL drops its pixmap immediately and then
            // decodes asynchronously — so every snapshot flashed the empty box behind it.
            // The new still is loaded into whichever Image is hidden and only becomes
            // visible once it reports Ready, which means there is never a moment with
            // nothing to paint. A load that fails simply never swaps: the last good still
            // stays up.
            property bool showA: true

            Connections {
                target: root
                function onCameraFrameChanged() {
                    if (root.cameraFrame === "") {
                        imgA.source = ""
                        imgB.source = ""
                        return
                    }
                    if (cameraPanel.showA)
                        imgB.source = root.cameraFrame
                    else
                        imgA.source = root.cameraFrame
                }
            }

            Image {
                id: imgA
                anchors.fill: parent
                cache: false            // one shot per URL; the two URLs alternate
                asynchronous: true
                fillMode: Image.PreserveAspectFit
                opacity: cameraPanel.showA ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: 150 } }
                onStatusChanged: if (status === Image.Ready && !cameraPanel.showA) cameraPanel.showA = true
            }

            Image {
                id: imgB
                anchors.fill: parent
                cache: false
                asynchronous: true
                fillMode: Image.PreserveAspectFit
                opacity: cameraPanel.showA ? 0 : 1
                Behavior on opacity { NumberAnimation { duration: 150 } }
                onStatusChanged: if (status === Image.Ready && cameraPanel.showA) cameraPanel.showA = false
            }

            Text {
                anchors.centerIn: parent
                visible: root.cameraFrame === ""
                text: root.cameraStatus === "error" ? "Camera unreachable" : "Connecting…"
                color: Theme.muted
                font.pixelSize: root.unit * 4
            }
        }

        // The gate audio is broken while the picture is fine — only reachable on the HTTP
        // camera path, where sound arrives on a stream of its own. Muted rather than red:
        // the gate itself is working and still openable, so this is information, not a
        // failure of the thing the user came here to do.
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            visible: root.audioNotice !== ""
            text: root.audioNotice
            color: Theme.muted
            font.pixelSize: root.unit * 4
        }

        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            visible: root.statusText !== ""
            text: root.statusText
            color: Theme.text
            font.pixelSize: root.unit * 9
            font.bold: true
            wrapMode: Text.WordWrap
        }

        // How far from home. Only present when HomeDistanceTracker has something — the
        // geofence feature is on, located, and a fix is in — so the layout is unchanged for
        // everyone who never enabled it.
        Text {
            width: parent.width
            horizontalAlignment: Text.AlignHCenter
            visible: root.homeDistance !== ""
            text: root.homeDistance
            color: Theme.muted
            font.pixelSize: root.unit * 4
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
            color: Theme.error
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
