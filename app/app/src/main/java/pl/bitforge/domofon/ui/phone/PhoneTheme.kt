package pl.bitforge.domofon.ui.phone

/**
 * The Android-side mirror of the two Theme.qml tokens the host UI also needs. There is no
 * compile-time check across the QML bridge, so the contract is documentation: the wiki's
 * ui-qml-contract page holds the canonical token table, and qtquickview/Theme.qml carries
 * the same pointer back here.
 */
object PhoneTheme {

    /** Theme.qml `base` — the scene background the wrapper view and fallbacks must match. */
    const val BASE = 0xFF1E1E2E.toInt()

    /** Theme.qml `muted` — secondary text, used by the restart fallback view. */
    const val MUTED = 0xFFA6ADC8.toInt()
}
