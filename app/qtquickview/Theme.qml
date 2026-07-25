pragma Singleton
import QtQuick

// The QML side of the app's palette (Catppuccin Mocha), declared once. The wiki's
// ui-qml-contract page holds the canonical token table; PhoneTheme.kt mirrors the two
// values the Android side also needs (there is no compile-time check across the bridge —
// change a value here and check the mirror).
QtObject {
    // Scene background. Mirrored: PhoneTheme.BASE and ic_launcher_background.
    readonly property color base: "#1e1e2e"

    // Inset panels (the camera box).
    readonly property color surface: "#11111a"

    // Primary text.
    readonly property color text: "white"

    // Secondary text (distance line, camera placeholder). Mirrored: PhoneTheme.MUTED.
    readonly property color muted: "#a6adc8"

    // Error text — never a normal state of affairs.
    readonly property color error: "#f38ba8"
}
