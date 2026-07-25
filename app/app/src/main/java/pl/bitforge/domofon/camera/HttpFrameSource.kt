package pl.bitforge.domofon.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.bitforge.domofon.config.ConfigStore
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Stills from an HTTP endpoint that returns a JPEG — the **optional** camera source.
 *
 * [RtspFrameSource] is the one the app is built around, because RTSP is the single address
 * every camera speaks and every user already has. This exists for the cases where pulling
 * frames from the stream is not what you want: a camera whose decoder the device dislikes, a
 * go2rtc or Frigate deployment that is already producing frames anyway, or simply a link
 * that costs less over a metered tunnel than a video stream does.
 *
 * It is second, not first, and that ordering is a product decision rather than a technical
 * one. Snapshot endpoints are vendor-specific (`/ISAPI/…` on Hikvision, `/cgi-bin/…` on
 * Dahua, something else on the next brand) and frequently Digest-only, which
 * `HttpURLConnection` does not speak. An app that *required* one would only ever work for
 * people who know their camera's firmware — see docs/04 §1.
 */
class HttpFrameSource(
    private val configStore: ConfigStore,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (CameraFrameGrabber.Status) -> Unit,
) {

    private var scope: CoroutineScope? = null

    @Synchronized
    fun start() {
        if (scope != null) return
        onStatus(CameraFrameGrabber.Status.CONNECTING)
        val started = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = started
        started.launch { poll() }
    }

    @Synchronized
    fun stop() {
        val running = scope ?: return
        scope = null
        running.cancel()
        onStatus(CameraFrameGrabber.Status.IDLE)
    }

    private suspend fun poll() {
        while (coroutineContext.isActive) {
            // Read per iteration, so an edit in Settings takes effect on the next fetch
            // rather than at the next start().
            val camera = configStore.current.camera
            if (camera.snapshotUrl.isBlank()) {
                onStatus(CameraFrameGrabber.Status.IDLE)
                delay(RETRY_MS)
                continue
            }
            val bitmap = fetch(camera.snapshotUrl)
            if (bitmap != null) {
                onFrame(bitmap)
                onStatus(CameraFrameGrabber.Status.STREAMING)
                delay(camera.snapshotSecs * 1000L)
            } else {
                // The last good frame stays on screen. A gate picture from thirty seconds
                // ago is worth more than a placeholder, as long as the status says so.
                onStatus(CameraFrameGrabber.Status.ERROR)
                delay(RETRY_MS)
            }
        }
    }

    private fun fetch(rawUrl: String): Bitmap? {
        val request = Request.of(rawUrl) ?: run {
            Log.w(TAG, "camera: the snapshot URL is not a valid http(s) URL")
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
                connection.inputStream.use { readCapped(it) }?.let { decodeDownscaled(it) }
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
            Log.w(TAG, "camera: the snapshot endpoint requires Digest auth, which this " +
                "client does not speak — clear the snapshot URL and use RTSP, or put " +
                "go2rtc in front (docs/04)")
        } else {
            Log.w(TAG, "camera: snapshot HTTP $code")
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
     * Decodes to at most [CameraFrameGrabber.MAX_EDGE] on the long side.
     *
     * The bitmap crosses the binder inside the car template bundle, where a full-resolution
     * frame courts `TransactionTooLarge` — the camera this was written against answers its
     * snapshot endpoint with 1.3 MB of main-stream JPEG. Bounds pass first so the sample size
     * comes from the real geometry.
     */
    private fun decodeDownscaled(jpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 200 OK with an HTML error page is the usual cause — a wrong path on a camera
            // that answers everything, or a captive portal on the way.
            Log.w(TAG, "camera: the response was not a decodable image")
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= CameraFrameGrabber.MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
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
