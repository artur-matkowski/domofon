package pl.bitforge.domofon.domain.camera

import java.util.Base64

/** The prefix a QML `Image` needs before base64 JPEG bytes to load them as a picture. */
private const val PREFIX = "data:image/jpeg;base64,"

/**
 * A camera frame as something QML can render without the frame ever reaching the disk.
 *
 * A `Bitmap` cannot cross the QtQuickView property bridge, so a frame has to arrive as one
 * of the primitives that can — and a `data:` URI is a string. The predecessor wrote the JPEG
 * to a private cache file and handed QML a `file://` URL, which worked but meant every gate
 * picture was briefly a file on disk, and outlived the app entirely whenever the process was
 * killed before `close()` ran (the dominant restart mode here — Qt loads once per process).
 *
 * Two properties of the QML side make this a drop-in for that scheme:
 *
 * - Each frame produces a *different* string, which is required either way: assigning an
 *   identical value to a QML property emits no change signal, and QtQuick caches `Image`
 *   sources by URL.
 * - `Main.qml` already sets `cache: false` on both Images, so a stream of unique URIs cannot
 *   grow Qt's pixmap cache the way it otherwise would.
 *
 * The size is the reason this is affordable at all: the frame is capped at
 * `CameraFrameGrabber.MAX_EDGE`, so the JPEG is a few tens of KB and base64 costs a third
 * more. At one frame every few seconds that is nothing; at video rates it would not be.
 */
fun jpegDataUri(jpeg: ByteArray): String =
    PREFIX + Base64.getEncoder().encodeToString(jpeg)
