package pl.bitforge.domofon.data.camera

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * One camera frame, in the two shapes its two consumers need.
 *
 * The car pane wants a [Bitmap] (`IconCompat.createWithBitmap`, straight across the binder);
 * the QML bridge wants JPEG bytes, because a Bitmap cannot cross it and the frame is handed
 * over as a `data:` URI. Carrying both on one object is what keeps the grabber's flow single
 * — the alternative was a second flow that could disagree with the first about which frame
 * is current.
 *
 * [sourceJpeg] is the point of the class. The HTTP path *fetched* a JPEG; decoding it and
 * re-encoding it for QML would be a generation loss on top of pure waste, and the author's
 * endpoint already serves exactly what is wanted (`?h=540&quality=70`). Passing those bytes
 * through delivers the server's own quality choice untouched. The RTSP path has no such
 * bytes — `glReadPixels` yields ARGB — so it encodes, once, lazily.
 */
class CameraFrame(
    /** Downscaled to at most [CameraFrameGrabber.MAX_EDGE] on the long edge. */
    val bitmap: Bitmap,
    /**
     * The bytes this frame arrived as, when they are usable verbatim, else null.
     *
     * "Usable" means the source did no downscaling: a caller that had to shrink the image
     * must not hand on the original, or the data URI would carry the full-resolution frame
     * the shrink existed to avoid.
     */
    private val sourceJpeg: ByteArray? = null,
) {

    /**
     * JPEG bytes for the QML bridge — the source's own when it had them, else encoded here.
     *
     * Lazy because only the phone surface asks: a car session never touches this, and
     * encoding a frame nobody renders would be pure cost on the head unit's cadence.
     */
    val jpeg: ByteArray by lazy {
        sourceJpeg ?: ByteArrayOutputStream().also {
            bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it)
        }.toByteArray()
    }

    private companion object {
        /** What the file-based predecessor used; visually lossless at this frame size. */
        const val QUALITY = 85
    }
}
