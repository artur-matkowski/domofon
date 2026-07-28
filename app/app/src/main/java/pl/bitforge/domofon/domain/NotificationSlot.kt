package pl.bitforge.domofon.domain

/**
 * Which of two interchangeable notification ids the next announcement of a repeating event
 * should be posted onto — the rule on its own, with no Android in it.
 *
 * **Why an event needs two ids at all.** `notify()` onto an id that still holds a live
 * notification is an *update*, and the Android Auto host draws no heads-up for an update: it
 * changes the shade entry and says nothing. For the arrival pop-up that is solved by time —
 * the pop-up expires long before another arrival may be announced. For gate **state changes**
 * it cannot be, because the two announcements that matter most are the two halves of one gate
 * cycle. Press the wall button and the gate reports `opening`, then `opened` fifteen to
 * twenty-five seconds later; both are news, both are announced, and the second lands on the
 * still-live id of the first. So the heads-up that says the gate has finished moving — the
 * one worth having — was the one silently dropped, on *every* cycle.
 *
 * Shortening the event timeout cannot fix that: it would have to be under a travel time
 * nobody can predict, and guessing low trades a missing heads-up for a notification that
 * vanishes while the driver is reaching for it.
 *
 * **Why not cancel-then-repost onto one id.** `cancel` and `notify` are asynchronous, and
 * against the *same* id the cancel can land after the notify and swallow it — trading a
 * missing heads-up for a missing notification. Against two *different* ids there is no such
 * interaction: cancelling the old slot cannot affect the post to the new one, whatever order
 * they arrive in. That is the whole reason this is two ids rather than a retry.
 *
 * @param primary the id used whenever it is free — so a single, isolated event always lands
 *   on the same one and nothing observable changes for the common case.
 * @param alternate used only while [primary] is still on screen.
 * @param liveIds the ids this app currently has posted.
 *
 * If **both** are somehow live — only reachable if a cancel was lost — this returns
 * [alternate] and the caller's cancel of [primary] frees it, so the next event finds
 * [primary] free again and the pair self-heals rather than staying stuck.
 */
fun freeNotificationSlot(primary: Int, alternate: Int, liveIds: Set<Int>): Int =
    if (primary in liveIds) alternate else primary
