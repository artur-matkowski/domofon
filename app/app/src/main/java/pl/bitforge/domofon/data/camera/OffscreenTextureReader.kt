package pl.bitforge.domofon.data.camera

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A video sink you can read pixels back from, safely.
 *
 * This exists because the obvious way to turn decoded video into a `Bitmap` — point the
 * player at an `ImageReader` and read `Image.getPlanes()` — is a **native abort** whenever
 * MediaCodec decides to hand back a buffer that lives only on the GPU. Not an exception: a
 * SIGABRT out of `nativeCreatePlanes`, underneath any `try/catch`, and there is no way to
 * ask an `Image` whether reading it is safe beforehand. It killed the app on every launch
 * on the test phone. See docs/troubleshooting.md → `nativeCreatePlanes`.
 *
 * GPU-only buffers are entirely fine if you read them *with the GPU*. So: the decoder
 * renders into a [SurfaceTexture] backed by an external-OES texture, we draw that texture
 * into an offscreen framebuffer at whatever size we actually want, and `glReadPixels` gives
 * us ordinary CPU memory. Nothing ever touches decoder memory directly.
 *
 * Scaling is free here, which is a real bonus over the old path: the FBO is sized to the
 * target directly, so a 4 MP camera frame is scaled by the GPU during the draw instead of
 * being decoded at full size and shrunk on the CPU afterwards.
 *
 * **Threading**: an EGL context belongs to the thread that made it current. Every method
 * here must be called on the one thread that called [setup] — in practice the player's
 * `HandlerThread`. Nothing here is synchronised, deliberately: the confinement is the
 * design, and locks would only hide a violation of it.
 */
class OffscreenTextureReader : AutoCloseable {

    /** RAII alias for [release] — the owner ([RtspFrameSource]) closes this from its own close. */
    override fun close() = release()

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var textureId = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var texMatrixHandle = 0

    private var framebuffer = 0
    private var targetTexture = 0
    private var targetWidth = 0
    private var targetHeight = 0
    private var pixels: ByteBuffer? = null

    private val texMatrix = FloatArray(16)

    private var surfaceTexture: SurfaceTexture? = null

    /** The surface to hand the player. Valid between [setup] and [release]. */
    var surface: Surface? = null
        private set

    /**
     * Builds the EGL context, the external texture and the surface.
     *
     * @param onFrameAvailable invoked by [SurfaceTexture] when the decoder has produced a
     *   frame. It arrives on whatever thread the SurfaceTexture was told to use, and the
     *   handler is *not* set here — the caller owns that, because it also owns the throttle.
     */
    fun setup(): Boolean = try {
        eglSetup()
        program = buildProgram()
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGl("texture setup")

        val texture = SurfaceTexture(textureId)
        surfaceTexture = texture
        surface = Surface(texture)
        true
    } catch (e: Exception) {
        release()
        false
    }

    /** Register the decoder's "new frame" callback. See [setup] for why this is separate. */
    fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener, handler: Handler) {
        surfaceTexture?.setOnFrameAvailableListener(listener, handler)
    }

    /**
     * Takes the pending frame off the queue, optionally converting it to a [Bitmap].
     *
     * [capture] is what implements the snapshot interval, and `false` is not the same as
     * skipping this call: `updateTexImage` **must** run for every frame regardless, because
     * that is what returns the buffer to the decoder. Miss it and the decoder's queue fills
     * and the stream stalls — which looks exactly like a dead camera.
     *
     * @param maxEdge longest edge of the returned bitmap; the frame is scaled during the draw.
     */
    fun consumeFrame(capture: Boolean, videoWidth: Int, videoHeight: Int, maxEdge: Int): Bitmap? {
        val texture = surfaceTexture ?: return null
        texture.updateTexImage()
        if (!capture) return null
        texture.getTransformMatrix(texMatrix)

        val (width, height) = fit(videoWidth, videoHeight, maxEdge)
        if (!ensureTarget(width, height)) return null

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        val buffer = pixels ?: return null
        buffer.rewind()
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        checkGl("readPixels")

        buffer.rewind()
        // ARGB_8888's in-memory byte order is R,G,B,A — the same as what glReadPixels just
        // wrote — so this is a straight copy with no channel shuffling.
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    fun release() {
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            if (targetTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(targetTexture), 0)
            if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            if (program != 0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(
                display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        framebuffer = 0
        targetTexture = 0
        textureId = 0
        program = 0
        targetWidth = 0
        targetHeight = 0
        pixels = null
    }

    // --- internals ---------------------------------------------------------------------

    private fun eglSetup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        // A pbuffer config, because nothing is ever displayed: the only consumer of this
        // context is glReadPixels out of an FBO. The 1x1 surface exists purely because
        // eglMakeCurrent needs *a* surface; its size is irrelevant.
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0
        ) { "no suitable EGL config" }

        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        eglSurface = EGL14.eglCreatePbufferSurface(
            display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
    }

    /** (Re)allocates the offscreen target when the frame geometry changes. */
    private fun ensureTarget(width: Int, height: Int): Boolean {
        if (width == targetWidth && height == targetHeight && framebuffer != 0) return true

        if (framebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (targetTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(targetTexture), 0)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        targetTexture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        val buffers = IntArray(1)
        GLES20.glGenFramebuffers(1, buffers, 0)
        framebuffer = buffers[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, targetTexture, 0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) return false

        targetWidth = width
        targetHeight = height
        pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        return true
    }

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { "program link failed: " + GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { "shader compile failed: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} at $stage" }
    }

    private companion object {
        /**
         * Fit inside [maxEdge] keeping the aspect ratio, never scaling *up*, and always
         * landing on even dimensions — odd sizes trip row-alignment assumptions in some
         * drivers' `glReadPixels`, and the default pack alignment is 4.
         */
        fun fit(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
            val w = if (width > 0) width else 1280
            val h = if (height > 0) height else 720
            val scale = minOf(1f, maxEdge.toFloat() / maxOf(w, h))
            fun even(value: Float) = maxOf(2, (value.toInt() / 2) * 2)
            return even(w * scale) to even(h * scale)
        }

        /**
         * A full-viewport triangle strip: bottom-left, bottom-right, top-left, top-right.
         *
         * The Y flip is in [TEX_COORDS], not here, and it is not decorative.
         * `glReadPixels` returns rows bottom-up, while a `Bitmap` wants row 0 to be the top
         * of the picture. Pairing framebuffer-bottom with the *top* of the texture makes the
         * readback come out upright without a second CPU pass over the pixels. (If a device
         * ever renders this upside down, swapping the V values here is the whole fix.)
         */
        val POSITIONS: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        /** V inverted against [POSITIONS] — see above. */
        val TEX_COORDS: FloatBuffer = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        /**
         * The texture matrix from [SurfaceTexture] is mandatory, not optional: it carries the
         * decoder's crop rectangle (the difference between the coded size — padded to
         * macroblocks — and the display size) as well as any orientation the buffer producer
         * applied. Ignoring it shows the padding as a green edge on the right or bottom.
         */
        val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """.trimIndent()

        /**
         * `samplerExternalOES`, not `sampler2D`: a SurfaceTexture is backed by an external
         * image the driver may hold in any format it likes (typically still YUV), and the
         * extension is what lets the sampler convert it to RGB for us. That conversion is the
         * work the old CPU NV21 loop was doing by hand.
         *
         * The `#extension` line must be the first line of the source — hence trimIndent().
         */
        val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()
    }
}
