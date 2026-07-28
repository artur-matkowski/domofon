package pl.bitforge.domofon.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceStatusTest {

    /** Deterministic and obvious in a failure message; the real one is locale-aware. */
    private val stamp: (Long) -> String = { "@$it" }

    private fun render(status: GeofenceStatus, inAppFenceOn: Boolean = false) =
        formatGeofenceStatus(status, inAppFenceOn, stamp)

    // --- the three failures this exists to tell apart ---------------------------------

    @Test
    fun `never armed says so rather than looking healthy`() {
        assertEquals(
            "Not registered yet\nPlay Services: no arrival seen yet",
            render(GeofenceStatus()),
        )
    }

    @Test
    fun `the permission refusal names the setting the user has to change`() {
        val text = render(GeofenceStatus(sync = FenceSync.NO_PERMISSION))
        // Worded exactly as the system dialog words it — this is the single most common
        // reason a geofence never fires, and the fix is on a screen the user has to find.
        assertTrue(text, text.startsWith("NOT registered — needs \"Allow all the time\""))
    }

    @Test
    fun `a Play Services failure carries its status code`() {
        val text = render(
            GeofenceStatus(sync = FenceSync.FAILED, syncDetail = "GEOFENCE_NOT_AVAILABLE")
        )
        assertTrue(text, text.startsWith("Registration failed: GEOFENCE_NOT_AVAILABLE"))
    }

    @Test
    fun `armed but never delivered is distinguishable from never armed`() {
        assertEquals(
            "Registered @1000\nPlay Services: no arrival seen yet",
            render(GeofenceStatus(sync = FenceSync.REGISTERED, syncAtMs = 1_000)),
        )
    }

    @Test
    fun `delivered and announced reads as a working trigger`() {
        assertEquals(
            "Registered @1000\n" +
                "Play Services: last arrival @5000\n" +
                "Last pop-up @5100 (Play Services)",
            render(
                GeofenceStatus(
                    sync = FenceSync.REGISTERED,
                    syncAtMs = 1_000,
                    lastNativeEnterAtMs = 5_000,
                    lastAnnouncedAtMs = 5_100,
                    lastAnnouncedBy = GeofenceStatus.SOURCE_NATIVE,
                )
            ),
        )
    }

    // --- the in-app fence -------------------------------------------------------------

    @Test
    fun `the in-app line appears only when the feature is on`() {
        val status = GeofenceStatus(sync = FenceSync.REGISTERED, syncAtMs = 1_000)
        assertFalse(render(status, inAppFenceOn = false).contains("In-app"))
        // With it off, a permanent "no crossing seen yet" would read as a fault.
        assertTrue(render(status, inAppFenceOn = true).contains("In-app: no crossing seen yet"))
    }

    @Test
    fun `the drive that diagnoses the bug`() {
        // What Artur should see if Play Services is the broken half: the fence is armed, it
        // never delivered, and the in-app trigger carried the arrival on its own.
        assertEquals(
            "Registered @1000\n" +
                "Play Services: no arrival seen yet\n" +
                "In-app: last crossing @9000\n" +
                "Last pop-up @9000 (in-app)",
            render(
                GeofenceStatus(
                    sync = FenceSync.REGISTERED,
                    syncAtMs = 1_000,
                    lastInAppCrossingAtMs = 9_000,
                    lastAnnouncedAtMs = 9_000,
                    lastAnnouncedBy = GeofenceStatus.SOURCE_IN_APP,
                ),
                inAppFenceOn = true,
            ),
        )
    }

    // --- rejections --------------------------------------------------------------------

    @Test
    fun `a rejection newer than the last delivery is reported`() {
        val text = render(
            GeofenceStatus(
                sync = FenceSync.REGISTERED,
                syncAtMs = 1_000,
                lastNativeEnterAtMs = 4_000,
                lastRejection = "feature disabled",
                lastRejectionAtMs = 6_000,
            )
        )
        assertTrue(text, text.endsWith("Last event ignored: feature disabled @6000"))
    }

    @Test
    fun `a rejection older than the last delivery is not a current fault`() {
        val text = render(
            GeofenceStatus(
                sync = FenceSync.REGISTERED,
                syncAtMs = 1_000,
                lastNativeEnterAtMs = 8_000,
                lastRejection = "feature disabled",
                lastRejectionAtMs = 6_000,
            )
        )
        assertFalse(text, text.contains("ignored"))
    }

    @Test
    fun `the fence side is reported, because a refused arrival is its consequence`() {
        val text = render(
            GeofenceStatus(
                sync = FenceSync.REGISTERED,
                syncAtMs = 1_000,
                side = FenceSide.INSIDE,
                sideAtMs = 7_000,
            )
        )
        assertTrue(text, text.contains("Last seen: inside the fence @7000"))
    }

    @Test
    fun `no evidence of a side says nothing rather than guessing`() {
        assertFalse(render(GeofenceStatus()).contains("Last seen"))
    }

    // --- the shared arrival cooldown ---------------------------------------------------

    /** The default caller: the native fence, which remembers nothing on its own. */
    private fun refusal(status: GeofenceStatus, nowMs: Long, requireDeparture: Boolean = true) =
        arrivalRefusal(status, nowMs, requireDeparture)

    @Test
    fun `the first arrival is always allowed`() {
        assertNull(refusal(GeofenceStatus(), nowMs = 100_000, requireDeparture = false))
    }

    @Test
    fun `the second trigger noticing the same approach is suppressed`() {
        // The native fence and the in-app fence will routinely both see one arrival, seconds
        // apart. One pop-up per arrival is the point.
        val first = 100_000L
        val status = GeofenceStatus(lastAnnouncedAtMs = first)
        assertNotNull(refusal(status, first + 3_000, requireDeparture = false))
        assertNotNull(
            refusal(status, first + GeofenceStatus.ARRIVAL_COOLDOWN_MS - 1, requireDeparture = false)
        )
    }

    @Test
    fun `a genuinely separate arrival is allowed once the cooldown passes`() {
        val first = 100_000L
        assertNull(
            refusal(
                GeofenceStatus(lastAnnouncedAtMs = first),
                first + GeofenceStatus.ARRIVAL_COOLDOWN_MS,
                requireDeparture = false,
            )
        )
    }

    // --- "you must have been outside" ---------------------------------------------------

    @Test
    fun `an ENTER while the app knows it is already home is refused`() {
        // The defect: getting into the car on the driveway and Play Services re-evaluating
        // the fence around it. Nothing left, so nothing arrived.
        val now = 9_000_000L
        val refusal = refusal(
            GeofenceStatus(side = FenceSide.INSIDE, sideAtMs = now - 60_000),
            now,
        )
        assertEquals("already inside the fence", refusal)
    }

    @Test
    fun `an ENTER after the app saw us leave is an arrival`() {
        val now = 9_000_000L
        assertNull(refusal(GeofenceStatus(side = FenceSide.OUTSIDE, sideAtMs = now - 60_000), now))
    }

    @Test
    fun `with no evidence at all the arrival is allowed`() {
        // A fresh install, or a phone that has not had a usable fix since. Refusing here would
        // trade a false pop-up for a missing one, and the missing one is the worse bug.
        assertNull(refusal(GeofenceStatus(), nowMs = 9_000_000L))
    }

    @Test
    fun `a stale inside stops being believed`() {
        // Play Services can drop an EXIT, and the tracker only runs while a surface is alive.
        // Past the trust window the app admits it does not know, rather than silencing the
        // feature for good.
        val now = 9_000_000L
        val stale = GeofenceStatus(
            side = FenceSide.INSIDE,
            sideAtMs = now - GeofenceStatus.SIDE_TRUST_MS,
        )
        assertNull(refusal(stale, now))
        val fresh = stale.copy(sideAtMs = now - GeofenceStatus.SIDE_TRUST_MS + 1)
        assertNotNull(refusal(fresh, now))
    }

    @Test
    fun `a trigger that saw the crossing itself is not asked again`() {
        // The in-app fence and the debug trigger. Both observed (or stand in for) the
        // outside-then-inside pair themselves; re-asking the persisted side would only give
        // it a second chance to say no — and would refuse every desk test.
        val now = 9_000_000L
        val insideNow = GeofenceStatus(side = FenceSide.INSIDE, sideAtMs = now - 1_000)
        assertNotNull(refusal(insideNow, now, requireDeparture = true))
        assertNull(refusal(insideNow, now, requireDeparture = false))
    }

    @Test
    fun `the cooldown still applies to a trigger that skips the side check`() {
        val now = 9_000_000L
        val justAnnounced = GeofenceStatus(lastAnnouncedAtMs = now - 1_000)
        assertNotNull(refusal(justAnnounced, now, requireDeparture = false))
    }
}
