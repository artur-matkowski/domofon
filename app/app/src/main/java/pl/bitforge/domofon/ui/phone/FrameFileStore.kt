package pl.bitforge.domofon.ui.phone

import android.graphics.Bitmap
import java.io.File

/**
 * The camera frame's road across the QML bridge: a Bitmap cannot cross the QtQuickView
 * property bridge, so each frame is written to a private cache file and QML gets a
 * `file://` URL.
 *
 * **Two files, used alternately, rather than one path plus a `?v=` cache-buster.** Both
 * schemes give QML a URL that differs from the last one — required, because setting an
 * identical string on a QML property emits no change and QtQuick caches Image sources by
 * URL — but only alternating names guarantee we are never *rewriting the file Qt's loader
 * thread is currently reading*, which can fail the load outright and leave the panel blank
 * until the next snapshot. The QML side holds up its half of the deal by loading into the
 * hidden Image and swapping only on Ready.
 *
 * The bitmap is already ≤960 px on its longest edge (downscaled in the grabber), so the
 * JPEG is a few tens of KB.
 */
class FrameFileStore(private val cacheDir: File) {

    private var version = 0

    /** Persists [bitmap] into the *other* file of the pair and returns its fresh URL. */
    fun write(bitmap: Bitmap): String {
        val file = File(cacheDir, "camera-frame-${++version % 2}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return "file://${file.absolutePath}"
    }

    /** Cache hygiene for the owner's close path; the frames are worthless without the UI. */
    fun deleteAll() {
        repeat(2) { File(cacheDir, "camera-frame-$it.jpg").delete() }
    }
}
