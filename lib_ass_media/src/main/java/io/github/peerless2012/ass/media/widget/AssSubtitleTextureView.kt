package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.util.AttributeSet
import android.util.Log
import android.view.TextureView
import androidx.media3.common.C
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.executor.BYTES_PER_PIXEL
import io.github.peerless2012.ass.media.executor.createOverlayWorker
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * @Author peerless2012
 * @Email peerless2012@126.com
 * @DateTime 12/02/25 9:59 PM
 * @Version V1.0
 * @Description
 */
@UnstableApi
class AssSubtitleTextureView : TextureView, AssSubtitleRender, TextureView.SurfaceTextureListener {

    private val assHandler: AssHandler

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(context: Context, attrs: AttributeSet?, assHandler: AssHandler) : this(
        context,
        attrs,
        0,
        assHandler
    )

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, assHandler: AssHandler) :
            super(context, attrs, defStyleAttr) {
        this.assHandler = assHandler
        isOpaque = false
        surfaceTextureListener = this
    }

    @Volatile
    private var renderThread: AssRenderThread? = null

    override fun requestRender(timeUs: Long, force: Boolean) {
        renderThread?.requestRender(timeUs, force)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        AssRenderThread(surface, width, height, assHandler).also {
            it.start()
            renderThread = it
        }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderThread?.onSurfaceSizeChanged(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderThread?.release()
        renderThread = null
        // The GL thread owns the SurfaceTexture until EGL teardown completes.
        return false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        assHandler.renderCallback = { requestRender(assHandler.videoTime, true) }
    }

    override fun onDetachedFromWindow() {
        assHandler.renderCallback = null
        renderThread?.release()
        renderThread = null
        super.onDetachedFromWindow()
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
    }

    companion object {

    }

    private class AssRenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        assHandler: AssHandler
    ) : HandlerThread("AssTexRenderThread"), Handler.Callback {

        private val TAG = "AssTexRenderThread"

        private val MSG_INIT = 1
        private val MSG_DRAW = 2
        private val MSG_SURFACE_SIZE_CHANGED = 3
        private val MSG_RELEASE = 4

        private lateinit var handler: Handler

        @Volatile
        private var stopping = false

        private var released = false

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val render = AssRender(assHandler) { requestDraw() }

        override fun start() {
            super.start()
            handler = Handler(looper, this)
            handler.sendEmptyMessage(MSG_INIT)
        }

        fun requestRender(timeUs: Long, force: Boolean) {
            render.requestRender(timeUs, force)
        }

        private fun requestDraw() {
            if (stopping) return
            handler.removeMessages(MSG_DRAW)
            handler.sendEmptyMessage(MSG_DRAW)
        }

        fun onSurfaceSizeChanged(width: Int, height: Int) {
            this.width = width
            this.height = height
            handler.sendEmptyMessage(MSG_SURFACE_SIZE_CHANGED)
        }

        fun release() {
            if (stopping) return
            stopping = true
            render.stopWorker()
            handler.removeCallbacksAndMessages(null)
            handler.sendEmptyMessage(MSG_RELEASE)
        }

        override fun handleMessage(msg: Message): Boolean {
            if (stopping && msg.what != MSG_RELEASE) return true
            try {
                when (msg.what) {
                    MSG_INIT -> initInternal()
                    MSG_DRAW -> drawInternal()
                    MSG_SURFACE_SIZE_CHANGED -> sizeChangedInternal(width, height)
                    MSG_RELEASE -> releaseInternal()
                }
            } catch (e: Exception) {
                Log.e(TAG, "GL thread error", e)
                releaseInternal()
            }
            return true
        }

        private fun initInternal() {
            try {
                eglDisplay = GlUtil.getDefaultEglDisplay()
                eglContext = GlUtil.createEglContext(eglDisplay)
                eglSurface = GlUtil.createEglSurface(eglDisplay, surfaceTexture, C.COLOR_TRANSFER_SDR, false)
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                render.onSurfaceCreated()
                this.sizeChangedInternal(width, height)
            } catch (e: GlUtil.GlException) {
                Log.e(TAG, "Failed to initialize EGL", e)
                throw IllegalStateException("EGL initialization failed", e)
            }
        }

        private fun sizeChangedInternal(width: Int, height: Int) {
            render.onSurfaceChanged(width, height)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                GlUtil.clearFocusedBuffers()
                EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            }
        }

        private fun drawInternal() {
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return
            render.onDrawFrame { EGL14.eglSwapBuffers(eglDisplay, eglSurface) }
        }

        private fun releaseInternal() {
            if (released) return
            released = true
            stopping = true
            render.stopWorker()
            try {
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                    try {
                        render.onSurfaceDestroyed()
                        GlUtil.destroyEglSurface(eglDisplay, eglSurface)
                        GlUtil.destroyEglContext(eglDisplay, eglContext)
                    } catch (e: GlUtil.GlException) {
                        Log.e(TAG, "Failed to release EGL resources", e)
                    } finally {
                        eglDisplay = EGL14.EGL_NO_DISPLAY
                        eglContext = EGL14.EGL_NO_CONTEXT
                        eglSurface = EGL14.EGL_NO_SURFACE
                    }
                }
            } finally {
                surfaceTexture.release()
                quit()
            }
        }
    }

    private class AssRender(private val assHandler: AssHandler, published: () -> Unit) {

        private val worker = createOverlayWorker(assHandler, published)

        private val vertexShaderCode = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord.xy;
            }
        """.trimIndent()

        private val fragmentShaderCode = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 v_TexCoord;
            uniform sampler2D u_Texture;
            uniform vec2 u_TextureScale;
            uniform vec2 u_TextureMargin;
            void main() {
                vec2 coord = clamp(v_TexCoord * u_TextureScale,
                    u_TextureMargin, u_TextureScale - u_TextureMargin);
                gl_FragColor = texture2D(u_Texture, coord);
            }
        """.trimIndent()

        private val rectangleCoords = floatArrayOf(
            -1f,  1f,  // Top left
            1f,  1f,  // Top right
            -1f, -1f,  // Bottom left
            1f, -1f   // Bottom right
        )

        private val textureCoords = floatArrayOf(
            0f, 0f,  // Top left
            1f, 0f,  // Top right
            0f, 1f,  // Bottom left
            1f, 1f   // Bottom right
        )

        private var surfaceDirty = true

        private var surfaceSize = Size.ZERO

        @Volatile
        private var renderSize = Size.ZERO

        private lateinit var glProgram: GlProgram

        private var vertexBufferId = 0

        private var texCoordBufferId = 0

        private var textureId = 0

        private var textureWidth = 0

        private var textureHeight = 0

        fun onSurfaceCreated() {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            textureWidth = 0
            textureHeight = 0

            glProgram = GlProgram(vertexShaderCode, fragmentShaderCode)
            GlUtil.checkGlError()

            val vertexBuffer = ByteBuffer.allocateDirect(rectangleCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(rectangleCoords)
            vertexBuffer.position(0)

            val texCordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(textureCoords)
            texCordBuffer.position(0)

            val buffers = IntArray(2)
            GLES20.glGenBuffers(2, buffers, 0)
            vertexBufferId = buffers[0]
            texCoordBufferId = buffers[1]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, rectangleCoords.size * 4, vertexBuffer, GLES20.GL_STATIC_DRAW)
            GlUtil.checkGlError()
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordBufferId)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, textureCoords.size * 4, texCordBuffer, GLES20.GL_STATIC_DRAW)
            GlUtil.checkGlError()
            // Canvas rows are tightly packed; no row-length extension is needed.
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

            // enable blend
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            surfaceSize = Size(width, height)
            renderSize = assHandler.computeRenderSize(width, height)
            GLES20.glViewport(0, 0, width, height)
            requestRender(assHandler.videoTime, assHandler.render != null)
        }

        fun requestRender(timeUs: Long, force: Boolean) {
            if (!force && assHandler.render == null) return
            val size = renderSize
            worker.request(timeUs, size.width, size.height, force)
        }

        fun stopWorker() {
            worker.shutdown()
        }

        fun onDrawFrame(swap: () -> Unit) {
            val canvas = worker.acquire() ?: return
            try {
                if (canvas.renderWidth != renderSize.width || canvas.renderHeight != renderSize.height) return
                if (canvas.width == 0 && !surfaceDirty) return
                // clear surface content
                GlUtil.clearFocusedBuffers()
                surfaceDirty = false
                if (canvas.width > 0 && canvas.height > 0) {
                    val buffer = canvas.buffer
                    buffer.position(0)
                    buffer.limit(canvas.width * canvas.height * BYTES_PER_PIXEL)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
                    if (canvas.width > textureWidth || canvas.height > textureHeight) {
                        textureWidth = maxOf(textureWidth, canvas.width)
                        textureHeight = maxOf(textureHeight, canvas.height)
                        GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, textureWidth, textureHeight,
                            0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
                        )
                    }
                    GLES20.glTexSubImage2D(
                        GLES20.GL_TEXTURE_2D, 0, 0, 0, canvas.width, canvas.height,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
                    )
                    GlUtil.checkGlError()

                    glProgram.use()
                    GLES20.glUniform1i(glProgram.getUniformLocation("u_Texture"), 0)
                    GLES20.glUniform2f(
                        glProgram.getUniformLocation("u_TextureScale"),
                        canvas.width.toFloat() / textureWidth, canvas.height.toFloat() / textureHeight
                    )
                    // Linear filtering must not sample stale pixels outside the uploaded box.
                    GLES20.glUniform2f(
                        glProgram.getUniformLocation("u_TextureMargin"),
                        0.5f / textureWidth, 0.5f / textureHeight
                    )
                    val aPosition = glProgram.getAttributeArrayLocationAndEnable("a_Position")
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vertexBufferId)
                    GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, 0)
                    val aTexCoord = glProgram.getAttributeArrayLocationAndEnable("a_TexCoord")
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordBufferId)
                    GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, 0)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

                    // Scale coordinates from render size to surface size
                    val scaleX = surfaceSize.width.toFloat() / renderSize.width
                    val scaleY = surfaceSize.height.toFloat() / renderSize.height
                    val scaledX = (canvas.x * scaleX).toInt()
                    val scaledY = (canvas.y * scaleY).toInt()
                    val scaledW = (canvas.width * scaleX).toInt()
                    val scaledH = (canvas.height * scaleY).toInt()
                    GLES20.glViewport(scaledX, surfaceSize.height - scaledY - scaledH, scaledW, scaledH)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    GlUtil.checkGlError()
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
                    surfaceDirty = true
                }
                GLES20.glViewport(0, 0, surfaceSize.width, surfaceSize.height)
                swap()
            } finally {
                worker.release(canvas)
            }
        }

        fun onSurfaceDestroyed() {
            GlUtil.deleteTexture(textureId)
            textureId = 0
            GlUtil.deleteBuffer(vertexBufferId)
            GlUtil.deleteBuffer(texCoordBufferId)
            if (::glProgram.isInitialized) {
                glProgram.delete()
            }
        }

    }
}