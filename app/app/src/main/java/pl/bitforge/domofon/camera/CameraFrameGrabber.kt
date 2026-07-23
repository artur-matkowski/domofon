package pl.bitforge.domofon.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.bitforge.domofon.config.ConfigStore
import java.io.ByteArrayOutputStream

/**
 * Pulls single frames out of the gate camera's RTSP stream, for surfaces that can only
 * show a picture — the Android Auto screen renders Car App Library templates, never QML,
 * so the phone's (future) live video view is structurally impossible there. A still every
 * few seconds is what a template can carry, and for "is someone at the gate" it is enough.
 *
 * Deliberately not part of [pl.bitforge.domofon.gate.GateRepository]: that object owns
 * MQTT and nothing else. This class owns the RTSP pull, and its only consumer — the car
 * session — owns *it*, creating and stopping it with the session lifecycle. Nothing keeps
 * the stream running when no screen wants pictures; over a metered VPN link that would be
 * paying for video nobody sees.
 *
 * Threading: an ExoPlayer instance is single-threaded by contract. All player access is
 * confined to a private [HandlerThread]; [start] and [stop] merely post onto it, so they
 * are safe from any thread. Frames surface through [frame], a main-safe StateFlow.
 */
// androidx.annotation.OptIn, not Kotlin's: media3's @UnstableApi is lint-checked, not a
// Kotlin @RequiresOptIn — the Kotlin annotation compiles but silences nothing. Opting in
// rather than propagating: the RTSP module has carried the annotation since media3 1.0
// while shipping in production apps everywhere; the instability is contained here so the
// car code doesn't inherit it.
@androidx.annotation.OptIn(UnstableApi::class)
class CameraFrameGrabber(private val context: Context) {

    enum class Status {
        /** Not started, or no RTSP URL configured. */
        IDLE,
        /** Started, no frame yet on this attempt. */
        CONNECTING,
        /** Frames are flowing; [frame] holds a current picture. */
        STREAMING,
        /** Stream failed; retrying. [frame] keeps the last good picture, if any. */
        ERROR,
    }

    private val _frame = MutableStateFlow<Bitmap?>(null)

    /** Newest snapshot, downscaled for template use. Null until the first frame arrives. */
    val frame: StateFlow<Bitmap?> = _frame.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    // All of the below is confined to [thread]'s looper.
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var player: ExoPlayer? = null
    private var reader: ImageReader? = null
    private var started = false
    private var lastSnapshotAt = 0L

    private val retryRunnable = Runnable { startAttempt() }
    private val watchdogRunnable = Runnable {
        // Started, connected-ish, but not a single frame: a camera that answers RTSP yet
        // sends no video (or a decoder that silently refused our surface) looks exactly
        // like success to the player. Treat it as failure and go through the retry path.
        if (started && _status.value == Status.CONNECTING) {
            Log.w(TAG, "camera: no frame within ${WATCHDOG_MS / 1000}s")
            failAttempt()
        }
    }

    /** Begin pulling frames. No-op when no camera is configured. Safe to call repeatedly. */
    @Synchronized
    fun start() {
        if (!ENABLED) return
        if (thread != null) return
        if (!ConfigStore.current.camera.isConfigured) {
            _status.value = Status.IDLE
            return
        }
        val t = HandlerThread("camera-grab").also { it.start() }
        thread = t
        handler = Handler(t.looper).also { h ->
            h.post {
                started = true
                startAttempt()
            }
        }
    }

    /** Stop pulling and release everything. The last frame is kept for instant redisplay. */
    @Synchronized
    fun stop() {
        val t = thread ?: return
        val h = handler ?: return
        thread = null
        handler = null
        h.post {
            started = false
            h.removeCallbacks(retryRunnable)
            h.removeCallbacks(watchdogRunnable)
            releaseAttempt()
            _status.value = Status.IDLE
            t.quitSafely()
        }
    }

    // --- player-thread internals ------------------------------------------------------

    private fun startAttempt() {
        val h = handler ?: return
        if (!started || player != null) return
        val url = ConfigStore.current.camera.rtspUrl
        if (url.isBlank()) return
        _status.value = Status.CONNECTING

        // The configured size is nominal: MediaCodec sets the real buffer geometry from
        // the stream, and each Image reports its actual size. YUV_420_888 is the decoder
        // output format with the widest device support for CPU-readable surfaces; if a
        // device's decoder refuses it (no frame, no error — the watchdog catches it),
        // docs/10 lists the go2rtc snapshot endpoint as the escape hatch.
        //
        // USAGE_CPU_READ_OFTEN is load-bearing, not a hint. Without it the buffers behind
        // this reader are allocated for the GPU alone, and on an Exynos decoder (tested:
        // SM-G990B2) Image.getPlanes() then hands back null plane pointers with a bogus
        // capacity. That is a JNI abort inside the framework — "non-zero capacity for
        // nullptr pointer" — which kills the process outright; the try/catch in onImage()
        // cannot see it, because it is not a Java exception. Requesting CPU read at
        // allocation time is what makes the planes real.
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ImageReader.newInstance(
                640, 360, ImageFormat.YUV_420_888, 2,
                HardwareBuffer.USAGE_CPU_READ_OFTEN,
            )
        } else {
            // API 28 has no usage overload. The old behaviour, and the old risk — the
            // guard in onImage() is what keeps that path from aborting.
            ImageReader.newInstance(640, 360, ImageFormat.YUV_420_888, 2)
        }
        reader = r
        r.setOnImageAvailableListener({ onImage(it) }, h)

        val p = ExoPlayer.Builder(context).setLooper(h.looper).build()
        player = p
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Never the URL, never the exception message (it can embed the URL —
                // credentials inline): the error *code* is all diagnosis needs.
                Log.w(TAG, "camera: playback error ${error.errorCodeName}")
                failAttempt()
            }
        })
        // Interleaved TCP: RTP-over-UDP has to cross the VPN as loose datagrams and
        // rarely survives it; over TCP the stream rides the session that RTSP itself
        // already proved works.
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.setVideoSurface(r.surface)
        p.playWhenReady = true
        p.prepare()

        lastSnapshotAt = 0L
        h.postDelayed(watchdogRunnable, WATCHDOG_MS)
    }

    private fun failAttempt() {
        val h = handler ?: return
        releaseAttempt()
        if (!started) return
        _status.value = Status.ERROR
        h.removeCallbacks(retryRunnable)
        h.postDelayed(retryRunnable, RETRY_MS)
    }

    private fun releaseAttempt() {
        handler?.removeCallbacks(watchdogRunnable)
        player?.release()
        player = null
        reader?.close()
        reader = null
    }

    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val now = System.currentTimeMillis()
        // The decoder produces every frame; a consumer wants one every snapshotSecs. Read
        // fresh so a settings change takes effect on the next frame without a restart.
        // Everything in between is acquired and dropped — acquiring is what keeps the
        // reader's queue from stalling the decoder.
        val snapshotMs = ConfigStore.current.camera.snapshotSecs * 1000L
        if (_status.value == Status.STREAMING && now - lastSnapshotAt < snapshotMs) {
            image.close()
            return
        }
        if (!cpuReadable(image)) {
            // Nothing to be done with this stream on this device: every frame will be
            // GPU-only, and reading one would abort the process rather than throw. Give up
            // on the attempt instead of retrying into the same wall each frame.
            Log.w(TAG, "camera: decoder frames are not CPU-readable — giving up on this attempt")
            image.close()
            failAttempt()
            return
        }
        val bitmap = try {
            toBitmap(image)
        } catch (e: Exception) {
            // A single bad buffer is not a broken stream; the next one may be fine.
            Log.w(TAG, "camera: frame conversion failed", e)
            null
        } finally {
            image.close()
        } ?: return
        lastSnapshotAt = now
        _frame.value = bitmap
        _status.value = Status.STREAMING
    }

    /**
     * Whether this image's memory can really be mapped for CPU reads.
     *
     * Checked on the buffer itself rather than trusting what we asked [ImageReader] for,
     * because getting it wrong is not survivable: touching a plane on a GPU-only buffer
     * aborts the process from native code, underneath any catch block. Anything we cannot
     * determine is treated as readable — an image with no HardwareBuffer behind it is
     * ordinary CPU memory, which is the case this whole check exists to allow.
     */
    private fun cpuReadable(image: Image): Boolean = try {
        image.hardwareBuffer?.use { (it.usage and HardwareBuffer.USAGE_CPU_READ_OFTEN) != 0L }
            ?: true
    } catch (e: Exception) {
        true
    }

    /**
     * YUV_420_888 → NV21 → JPEG → downscaled Bitmap. The JPEG hop looks like a detour but
     * [YuvImage] is the only stock conversion that respects row/pixel strides without a
     * hand-rolled loop per plane layout, and it happens at most once per snapshot interval.
     */
    private fun toBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val nv21 = yuv420ToNv21(image)
        val jpeg = ByteArrayOutputStream().use { out ->
            YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                .compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            out.toByteArray()
        }
        // Power-of-two downscale to ≤ MAX_EDGE px. The bitmap crosses the binder inside
        // the car template bundle, where a full 1080p frame courts TransactionTooLarge.
        var sample = 1
        while (maxOf(image.width, image.height) / (sample * 2) >= MAX_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val out = ByteArray(ySize + ySize / 2)

        // Y plane, row by row: rowStride may exceed width (alignment padding).
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yPlane.rowStride)
            yBuffer.get(out, pos, width)
            pos += width
        }

        // Chroma, interleaved as NV21 expects (V first). pixelStride is 1 (planar) or 2
        // (semi-planar); indexing handles both.
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[pos++] = vBuffer.get(row * vPlane.rowStride + col * vPlane.pixelStride)
                out[pos++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
            }
        }
        return out
    }

    companion object {
        /**
         * Master switch for the RTSP snapshot, **off** because the current implementation
         * crashes the whole process on at least one real device.
         *
         * ExoPlayer renders into [ImageReader]'s surface, and MediaCodec is free to hand
         * back buffers that live only on the GPU. Reading a plane from one of those is a
         * JNI abort inside the framework ("non-zero capacity for nullptr pointer", from
         * `nativeCreatePlanes`) rather than a Java exception, so it cannot be caught, and
         * there is no reliable way to ask an Image whether it is safe to read *before*
         * reading it — `getHardwareBuffer()` returns nothing useful here. Requesting
         * `USAGE_CPU_READ_OFTEN` at allocation time does not change the outcome.
         * Reproduced on a Samsung SM-G990B2 (Exynos), Android 16: crash ~3 s after launch,
         * every launch, with an RTSP URL configured.
         *
         * A snapshot pulled over HTTP (go2rtc, or the camera's own JPEG endpoint) sidesteps
         * the decoder entirely and is the intended replacement — see docs/10. Everything
         * around this class is still wired up and working, so re-enabling is one line once
         * frames come from somewhere safe.
         *
         * Consumers must gate the *UI* on this too, not just [start] — see
         * [pl.bitforge.domofon.MainActivity] and [pl.bitforge.domofon.car.GateScreen] —
         * otherwise the panel and the car template render a placeholder that waits forever
         * for a frame that is never coming.
         */
        const val ENABLED = false

        private const val TAG = "Domofon"

        // Snapshot cadence is now ConfigStore.current.camera.snapshotSecs, read per frame.

        /** Longest edge of an emitted bitmap. */
        const val MAX_EDGE = 640

        const val WATCHDOG_MS = 15_000L
        const val RETRY_MS = 30_000L
    }
}
