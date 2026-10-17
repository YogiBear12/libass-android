package io.github.peerless2012.ass.media.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.media3.common.util.Size
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.executor.AssRenderWorker
import io.github.peerless2012.ass.media.executor.createOverlayWorker

// One slot each: on screen, retiring until the next draw, published, and being written.
private const val CANVAS_SLOT_COUNT = 4

/**
 * @Author peerless2012
 * @Email peerless2012@126.com
 * @DateTime 12/02/25 9:58 PM
 * @Version V1.0
 * @Description
 */
class AssSubtitleCanvasView : View, AssSubtitleRender {

    private val assHandler: AssHandler

    private val paint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_OVER)
    }

    @Volatile
    private var worker: AssRenderWorker? = null

    private var bitmapX = 0

    private var bitmapY = 0

    private var bitmap: Bitmap? = null

    private var displayed: AssRenderWorker.Result? = null

    @Volatile
    private var renderSize = Size.ZERO

    constructor(context: Context, assHandler: AssHandler) : this(context, null, assHandler)

    constructor(context: Context, attrs: AttributeSet?, assHandler: AssHandler) : this(
        context,
        attrs,
        0,
        assHandler
    )

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, assHandler: AssHandler) :
            super(context, attrs, defStyleAttr) {
        setWillNotDraw(false)
        this.assHandler = assHandler
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            renderSize = assHandler.computeRenderSize(w, h)
            requestRender(assHandler.videoTime, assHandler.render != null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        worker = createOverlayWorker(assHandler, { postInvalidate() }, slotCount = CANVAS_SLOT_COUNT) { result ->
            val bitmap = result.bitmap ?: Bitmap.createBitmap(
                result.capacityWidth, result.capacityHeight, Bitmap.Config.ARGB_8888
            ).also { result.bitmap = it }
            bitmap.apply {
                reconfigure(result.width, result.height, Bitmap.Config.ARGB_8888)
                setPremultiplied(true)
                copyPixelsFromBuffer(result.buffer)
                prepareToDraw()
            }
        }
        assHandler.renderCallback = { requestRender(assHandler.videoTime, true) }
        requestRender(assHandler.videoTime, false)
    }

    override fun requestRender(timeUs: Long, force: Boolean) {
        if (!force && assHandler.render == null) return
        val size = renderSize
        worker?.request(timeUs, size.width, size.height, force)
    }

    override fun onDraw(canvas: Canvas) {
        val activeWorker = worker
        var retiring: AssRenderWorker.Result? = null
        activeWorker?.acquire()?.let { result ->
            retiring = displayed
            displayed = result
            bitmap = null
            if (result.renderWidth == renderSize.width && result.renderHeight == renderSize.height) {
                bitmapX = result.x
                bitmapY = result.y
                if (result.width > 0 && result.height > 0) bitmap = result.bitmap
            }
        }
        super.onDraw(canvas)
        drawBitmap(canvas)
        // HWUI may still use the previous display list until the replacement draw has been submitted.
        retiring?.let { previous -> post { activeWorker?.release(previous) } }
    }

    private fun drawBitmap(canvas: Canvas) {
        val bitmap = bitmap ?: return
        val scaleX = if (renderSize.width > 0) width.toFloat() / renderSize.width else 1f
        val scaleY = if (renderSize.height > 0) height.toFloat() / renderSize.height else 1f
        if (scaleX == 1f && scaleY == 1f) {
            canvas.drawBitmap(bitmap, bitmapX.toFloat(), bitmapY.toFloat(), paint)
        } else {
            val dst = RectF(
                bitmapX * scaleX,
                bitmapY * scaleY,
                (bitmapX + bitmap.width) * scaleX,
                (bitmapY + bitmap.height) * scaleY
            )
            canvas.drawBitmap(bitmap, null, dst, paint)
        }
    }

    override fun onDetachedFromWindow() {
        assHandler.renderCallback = null
        worker?.shutdown()
        worker = null
        bitmap = null
        displayed = null
        super.onDetachedFromWindow()
    }
}