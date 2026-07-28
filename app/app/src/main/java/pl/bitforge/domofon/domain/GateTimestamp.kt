package pl.bitforge.domofon.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * The hc12 wire timestamp: one parser, and the one way a notification is allowed to render it.
 *
 * The bridge publishes `ts` as ISO-8601, and [GateState.changedAt] carries that string
 * verbatim — which the state-change notification used to print verbatim too, so a heads-up
 * drawn over Maps read `Changed at 2026-07-27T18:34:12+02:00`. A driver glancing at a pop-up
 * has time for four characters, not twenty-five (Artur, live testing 2026-07-28).
 */

/**
 * Parses a wire `ts`, or null if it is neither an offset date-time nor a bare instant.
 *
 * Two forms because the bridge has published both: `2026-07-27T18:34:12+02:00` and
 * `2026-07-27T16:34:12Z`. [GateProtocol.decode] rejects anything this cannot read, which is
 * what makes the fallback in [hourMinute] unreachable in practice.
 */
fun parseWireTimestamp(raw: String): Instant? {
    if (raw.isEmpty()) return null
    return try {
        java.time.OffsetDateTime.parse(raw).toInstant()
    } catch (e: DateTimeParseException) {
        try {
            Instant.parse(raw)
        } catch (e2: DateTimeParseException) {
            null
        }
    }
}

/**
 * A wire timestamp as `HH:mm` in [zone] — the only form that goes into a notification.
 *
 * A fixed 24-hour pattern rather than a locale short-time format: the latter is 12-hour in
 * several locales, and "6:34 PM" is both longer and harder to read at a glance than "18:34".
 *
 * Unparseable input falls back to the raw string. Defensive only — `GateProtocol` turns an
 * unreadable `ts` into [GateEvent.Ignored], so no such value can reach a [GateState] — but
 * dropping the timestamp entirely would be a worse answer than showing it ugly.
 */
fun hourMinute(rawTs: String, zone: ZoneId = ZoneId.systemDefault()): String {
    val instant = parseWireTimestamp(rawTs) ?: return rawTs
    return HH_MM.withZone(zone).format(instant)
}

/**
 * The same `HH:mm`, for an event the *app* observed rather than one the bridge reported.
 *
 * The arrival pop-up needs this: a fence crossing has no wire timestamp, only the
 * `System.currentTimeMillis()` the arrival flow read when it decided. Same 24-hour rule as
 * [hourMinute], and no fallback branch — an epoch millisecond cannot fail to parse.
 */
fun hourMinuteAt(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    HH_MM.withZone(zone).format(Instant.ofEpochMilli(epochMs))

private val HH_MM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
