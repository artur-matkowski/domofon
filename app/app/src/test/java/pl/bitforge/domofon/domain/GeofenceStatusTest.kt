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

    @Test
    fun `the first arrival is always allowed`() {
        assertNull(arrivalRefusal(GeofenceStatus(), nowMs = 100_000))
    }

    @Test
    fun `the second trigger noticing the same approach is suppressed`() {
        // The native fence and the in-app fence will routinely both see one arrival, seconds
        // apart. One pop-up per arrival is the point.
        val first = 100_000L
        val status = GeofenceStatus(lastAnnouncedAtMs = first)
        assertNotNull(arrivalRefusal(status, first + 3_000))
        assertNotNull(arrivalRefusal(status, first + GeofenceStatus.ARRIVAL_COOLDOWN_MS - 1))
    }

    @Test
    fun `a genuinely separate arrival is allowed once the cooldown passes`() {
        val first = 100_000L
        assertNull(
            arrivalRefusal(
                GeofenceStatus(lastAnnouncedAtMs = first),
                first + GeofenceStatus.ARRIVAL_COOLDOWN_MS,
            )
        )
    }

    @Test
    fun `the recorded side never refuses an arrival`() {
        // Artur's correction, 2026-07-28: crossing direction is universal, and the trigger
        // already carries it. Requiring the app to have *also* seen the departure refuses a
        // real arrival whenever it was not running for one — phone switched off at home,
        // switched on again already on the way back. Both triggers must pass here regardless
        // of what the readout says.
        val now = 9_000_000L
        for (side in FenceSide.entries) {
            assertNull(
                "side=$side",
                arrivalRefusal(GeofenceStatus(side = side, sideAtMs = now - 60_000), now),
            )
        }
    }
}
