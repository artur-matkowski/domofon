package pl.bitforge.domofon.data.camera

import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.bitforge.domofon.domain.config.CameraFeed
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Stills polled from an HTTP endpoint that returns one JPEG per request.
 *
 * The picture half of the HTTP camera path; [HttpCameraSource] pairs it with the audio half.
 * It is not an override of [RtspFrameSource] any more — the user picks between the two — but
 * it is still second in the sense that matters: RTSP is the only path that works with nothing
 * but the camera, so it stays the default for a fresh install.
 *
 * The reason this path exists at all is a restreamer in front of the camera. Snapshot
 * endpoints straight off a camera are vendor-specific (`/ISAPI/…` on Hikvision, `/cgi-bin/…`
 * on Dahua, something else on the next brand) and frequently Digest-only, which
 * `HttpURLConnection` does not speak. Point it at Frigate or go2rtc and both problems belong
 * to something else — see docs/modules/camera.md.
 */
class HttpFrameSource(
    private val feed: CameraFeed.Http,
    /**
     * The poll gap, read per iteration rather than captured: retuning the interval in
     * Settings must take effect on the next fetch without tearing the session down.
     */
    private val intervalMs: () -> Long,
    private val onFrame: (CameraFrame) -> Unit,
    private val onStatus: (CameraFrameGrabber.Status) -> Unit,
) : FrameSource {

    private var scope: CoroutineScope? = null

    @Synchronized
    override fun start() {
        if (scope != null) return
        onStatus(CameraFrameGrabber.Status.CONNECTING)
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        started.launch { poll() }
    }

    /** Idempotent. Cancels the poll loop and any in-flight fetch. */
    @Synchronized
    override fun close() {
        val running = scope ?: return
        scope = null
        running.cancel()
        onStatus(CameraFrameGrabber.Status.IDLE)
    }

    private suspend fun poll() {
        while (coroutineContext.isActive) {
            val frame = fetch(feed.imageUrl)
            if (frame != null) {
                onFrame(frame)
                onStatus(CameraFrameGrabber.Status.STREAMING)
                delay(intervalMs())
            } else {
                // The last good frame stays on screen. A gate picture from thirty seconds
                // ago is worth more than a placeholder, as long as the status says so.
                onStatus(CameraFrameGrabber.Status.ERROR)
                delay(RETRY_MS)
            }
        }
    }

    private fun fetch(rawUrl: String): CameraFrame? {
        val request = Request.of(rawUrl) ?: run {
            Log.w(TAG, "camera: the image URL is not a valid http(s) URL")
            return null
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (request.url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "image/jpeg,image/*")
                request.authorization?.let { setRequestProperty("Authorization", it) }
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                logHttpFailure(code, connection)
                null
            } else {
                connection.inputStream.use { readCapped(it) }?.let { decode(it) }
            }
        } catch (e: Exception) {
            // The class name, never the message and never the URL: both can carry the
            // camera credentials, and logcat is readable by adb and by anything holding
            // READ_LOGS. SSLHandshakeException, ConnectException and SocketTimeoutException
            // are each distinct enough to diagnose from the name alone.
            Log.w(TAG, "camera: snapshot fetch failed (${e.javaClass.simpleName})")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun logHttpFailure(code: Int, connection: HttpURLConnection) {
        val challenge = connection.getHeaderField("WWW-Authenticate").orEmpty()
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED && challenge.startsWith("Digest", true)) {
            // Worth its own line: the credentials are *correct* and the picture will still
            // never arrive. HttpURLConnection speaks Basic only, and most Hikvision/Dahua
            // firmware insists on Digest — which is the whole reason RTSP is the default
            // source and this one is the override.
            Log.w(TAG, "camera: the image endpoint requires Digest auth, which this " +
                "client does not speak — switch the camera source to RTSP, or put " +
                "go2rtc or Frigate in front of it (docs/modules/camera.md)")
        } else {
            Log.w(TAG, "camera: image HTTP $code")
        }
    }

    /**
     * Reads the body, refusing anything implausibly large.
     *
     * The URL is user-supplied and nothing guarantees it points at a still: aimed at an MJPEG
     * stream or a video file it would never end, and an unbounded read would grow until the
     * process died.
     */
    private fun readCapped(stream: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BYTES) {
                Log.w(TAG, "camera: snapshot exceeded ${MAX_BYTES / 1024} KB — is that URL a " +
                    "still image, or a stream?")
                return null
            }
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    /**
     * Decodes to at most [CameraFrameGrabber.MAX_EDGE] on the long side, keeping the fetched
     * bytes when they are already small enough to hand on verbatim.
     *
     * The bitmap crosses the binder inside the car template bundle, where a full-resolution
     * frame courts `TransactionTooLarge` — the camera this was written against answers its
     * snapshot endpoint with 1.3 MB of main-stream JPEG. Bounds pass first so the sample size
     * comes from the real geometry.
     *
     * The bytes are reusable only when nothing was scaled away, which the bounds pass settles
     * before any pixels are decoded: at or under the cap, `inSampleSize` stays 1 and the
     * bitmap *is* the response, so [CameraFrame] can carry the server's own encoding to QML
     * instead of a re-compression of it. Above the cap they are dropped — passing on the
     * original would ship the full-resolution frame the downscale existed to avoid.
     */
    private fun decode(jpeg: ByteArray): CameraFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 200 OK with an HTML error page is the usual cause — a wrong path on a camera
            // that answers everything, or a captive portal on the way.
            Log.w(TAG, "camera: the response was not a decodable image")
            return null
        }
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= CameraFrameGrabber.MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        return CameraFrame(
            bitmap = bitmap,
            sourceJpeg = if (longEdge <= CameraFrameGrabber.MAX_EDGE) jpeg else null,
        )
    }

    /** A snapshot URL split into what [HttpURLConnection] can actually act on. */
    private class Request(val url: URL, val authorization: String?) {
        companion object {
            /**
             * Null when [raw] is not a usable http(s) URL.
             *
             * Credentials are moved from the URL into an `Authorization` header, because
             * `HttpURLConnection` silently *ignores* userinfo: `http://user:pass@cam/snap.jpg`
             * pasted from a working browser session would go out unauthenticated and come
             * back 401, which reads exactly like a wrong password.
             */
            fun of(raw: String): Request? = try {
                val uri = URI(raw.trim())
                val scheme = uri.scheme
                if (!scheme.equals("http", true) && !scheme.equals("https", true)) null
                else when (val userInfo = uri.userInfo) {
                    null -> Request(uri.toURL(), null)
                    else -> {
                        val stripped = URI(
                            uri.scheme, null, uri.host, uri.port, uri.path, uri.query, uri.fragment,
                        )
                        // getUserInfo() is the *decoded* form, which is what Basic wants: a
                        // password written %40 in the URL must go on the wire as @.
                        val token = Base64.getEncoder().encodeToString(userInfo.toByteArray())
                        Request(stripped.toURL(), "Basic $token")
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private companion object {
        const val TAG = "Domofon"

        /** Refusal threshold for a response body — a JPEG still is orders of magnitude under. */
        const val MAX_BYTES = 8 * 1024 * 1024

        /** Matches the MQTT connect timeout: long enough for a VPN, short enough to retry. */
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 10_000

        /** Backoff after a failed fetch, independent of the configured snapshot interval. */
        const val RETRY_MS = 30_000L
    }
}
