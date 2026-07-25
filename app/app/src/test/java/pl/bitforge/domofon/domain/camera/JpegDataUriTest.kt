package pl.bitforge.domofon.domain.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class JpegDataUriTest {

    /** The first bytes of any JPEG: SOI marker plus a JFIF APP0 header. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10)

    @Test
    fun `the uri carries the mime type QML needs to decode it`() {
        assertTrue(jpegDataUri(jpeg).startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `the payload round-trips - what QML decodes is the frame we encoded`() {
        val payload = jpegDataUri(jpeg).substringAfter("base64,")
        assertArrayEquals(jpeg, Base64.getDecoder().decode(payload))
    }

    @Test
    fun `distinct frames produce distinct uris`() {
        // Load-bearing, not incidental: assigning an identical string to a QML property
        // emits no change signal, so an unchanged URI would freeze the panel.
        assertNotEquals(jpegDataUri(jpeg), jpegDataUri(jpeg + 0x01))
    }

    @Test
    fun `the encoding is unpadded by line breaks`() {
        // MIME base64 wraps at 76 chars; a newline inside a URI truncates it in QML.
        val long = ByteArray(512) { it.toByte() }
        assertTrue(jpegDataUri(long).none { it == '\n' || it == '\r' })
    }
}
