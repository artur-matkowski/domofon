package pl.bitforge.domofon.domain.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSettingsRowsTest {

    @Test
    fun `each source hides the other path's url rows`() {
        val rtsp = CameraSettingsRows.visible(CameraSource.RTSP)
        assertTrue(rtsp.contains(ConfigKeys.RTSP_URL))
        assertFalse(rtsp.contains(ConfigKeys.SNAPSHOT_URL))
        assertFalse(rtsp.contains(ConfigKeys.AUDIO_URL))

        val http = CameraSettingsRows.visible(CameraSource.HTTP)
        assertTrue(http.contains(ConfigKeys.SNAPSHOT_URL))
        assertTrue(http.contains(ConfigKeys.AUDIO_URL))
        assertFalse(http.contains(ConfigKeys.RTSP_URL))
    }

    @Test
    fun `the selector, the interval and the audio switch are on every path`() {
        for (source in CameraSource.entries) {
            val visible = CameraSettingsRows.visible(source)
            assertTrue("$source hides its own selector", visible.contains(ConfigKeys.SOURCE))
            assertTrue("$source hides the interval", visible.contains(ConfigKeys.SNAPSHOT_SECS))
            assertTrue("$source hides the audio switch", visible.contains(ConfigKeys.AUDIO_ENABLED))
        }
    }

    @Test
    fun `every row belongs to ALL, so the fragment's loop can never strand one hidden`() {
        // The screen restores visibility by assigning over ALL. A key that some source shows
        // but ALL omits would be hidden once and never shown again.
        for (source in CameraSource.entries) {
            assertTrue(CameraSettingsRows.ALL.containsAll(CameraSettingsRows.visible(source)))
        }
        assertEquals(
            CameraSettingsRows.ALL,
            CameraSource.entries.flatMap { CameraSettingsRows.visible(it) }.toSet(),
        )
    }
}
