package io.github.peerless2012.ass.media.executor

import android.graphics.Bitmap
import android.util.Log
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.AssHandler
import java.nio.ByteBuffer

internal const val BYTES_PER_PIXEL = 4

internal class AssRenderWorker(
    private val render: (Request, Result) -> Outcome,
    private val published: () -> Unit,
    private val prepare: (Result) -> Unit = {},
    private val failed: (Exception) -> Unit = {},
    private val slotCount: Int = 2,
    private val hasTrack: () -> Boolean = { true },
    private val allocateBuffer: (Int) -> ByteBuffer = ByteBuffer::allocateDirect,
) {
    init {
        require(slotCount >= 2) { "At least two render slots are required" }
    }

    enum class Outcome { CHANGED, UNCHANGED, NO_TRACK }

    data class Request(val timeUs: Long, val width: Int, val height: Int, val force: Boolean)

    class Result(val buffer: ByteBuffer, val capacityWidth: Int, val capacityHeight: Int) {
        var renderWidth = capacityWidth
        var renderHeight = capacityHeight
        val box = IntArray(AssRender.CANVAS_RESULT_FIELD_COUNT)
        val x get() = box[AssRender.CANVAS_RESULT_X]
        val y get() = box[AssRender.CANVAS_RESULT_Y]
        val width get() = box[AssRender.CANVAS_RESULT_WIDTH]
        val height get() = box[AssRender.CANVAS_RESULT_HEIGHT]
        val changed get() = box[AssRender.CANVAS_RESULT_CHANGED]
        var bitmap: Bitmap? = null
    }

    private val monitor = Object()
    private var running = true
    private var pending: Request? = null
    private var forceNextRender = false
    private var slots = emptyArray<Result>()
    // Render input while there is no track; never published, so reuse cannot alias outstanding results.
    private val idle = emptyResult()
    private var newest: Result? = null
    private val outstanding = mutableSetOf<Result>()
    private val thread = Thread({ run() }, "AssRenderWorker").apply { start() }

    fun request(timeUs: Long, width: Int, height: Int, force: Boolean = false) {
        if (width <= 0 || height <= 0) return
        synchronized(monitor) {
            if (!running) return
            pending = Request(timeUs, width, height, force || forceNextRender || pending?.force == true)
            monitor.notifyAll()
        }
    }

    fun acquire(): Result? = synchronized(monitor) {
        if (!running) return null
        newest?.also {
            newest = null
            outstanding.add(it)
            monitor.notifyAll()
        }
    }

    fun release(result: Result) {
        synchronized(monitor) {
            check(outstanding.remove(result))
            monitor.notifyAll()
        }
    }

    fun shutdown() {
        synchronized(monitor) {
            running = false
            pending = null
            monitor.notifyAll()
        }
    }

    private fun run() {
        var lastWidth = 0
        var lastHeight = 0
        var wasEmpty = true
        try {
            while (true) {
                lateinit var request: Request
                lateinit var result: Result
                var trackPresent = false
                synchronized(monitor) {
                    while (running) {
                        val next = pending
                        if (next == null) {
                            monitor.wait()
                            continue
                        }
                        trackPresent = hasTrack()
                        val free = if (trackPresent) {
                            val grow = slots.firstOrNull()?.let {
                                it.capacityWidth < next.width || it.capacityHeight < next.height
                            } ?: true
                            if (grow) {
                                val width = maxOf(next.width, slots.firstOrNull()?.capacityWidth ?: 0)
                                val height = maxOf(next.height, slots.firstOrNull()?.capacityHeight ?: 0)
                                val bytes = width.toLong() * height * BYTES_PER_PIXEL
                                require(bytes <= Int.MAX_VALUE) { "Render size exceeds direct buffer limit" }
                                // Outstanding results keep their old buffers until explicitly released.
                                slots = Array(slotCount) { Result(allocateBuffer(bytes.toInt()), width, height) }
                            }
                            // Only the worker writes free slots; published and outstanding slots are never writable.
                            slots.firstOrNull { it !== newest && it !in outstanding }
                        } else {
                            idle
                        }
                        if (free == null) {
                            monitor.wait()
                            continue
                        }
                        val resize = next.width != lastWidth || next.height != lastHeight
                        request = if (resize) next.copy(force = true) else next
                        result = free
                        result.renderWidth = next.width
                        result.renderHeight = next.height
                        lastWidth = next.width
                        lastHeight = next.height
                        pending = null
                        forceNextRender = false
                        break
                    }
                    if (!running) return
                }
                try {
                    if (trackPresent) {
                        result.buffer.clear()
                        when (render(request, result)) {
                            Outcome.NO_TRACK -> trackPresent = false
                            Outcome.UNCHANGED -> continue
                            Outcome.CHANGED -> Unit
                        }
                    }
                    if (!trackPresent) slots = emptyArray()
                    val empty = !trackPresent || result.width == 0 || result.height == 0
                    if (empty && wasEmpty) continue
                    // An empty frame never holds a slot, so a view showing nothing pins no pixels.
                    if (empty) {
                        result = emptyResult().apply {
                            renderWidth = request.width
                            renderHeight = request.height
                        }
                    }
                    result.buffer.position(0)
                    result.buffer.limit(result.width * result.height * BYTES_PER_PIXEL)
                    if (!empty) prepare(result)
                    synchronized(monitor) {
                        if (!running) return
                        newest = result
                        wasEmpty = empty
                    }
                    published()
                } catch (exception: Exception) {
                    failed(exception)
                    // libass already treats this frame as delivered, so the next one must be forced.
                    synchronized(monitor) {
                        forceNextRender = true
                        pending = pending?.copy(force = true)
                    }
                }
            }
        } catch (exception: Exception) {
            failed(exception)
        } finally {
            synchronized(monitor) {
                running = false
                pending = null
                newest = null
                slots = emptyArray()
                monitor.notifyAll()
            }
        }
    }
}

private fun emptyResult() = AssRenderWorker.Result(ByteBuffer.allocate(0), 0, 0)

internal fun createOverlayWorker(
    handler: AssHandler,
    published: () -> Unit,
    slotCount: Int = 2,
    prepare: (AssRenderWorker.Result) -> Unit = {},
): AssRenderWorker = AssRenderWorker(
    render = { request, result ->
        val renderer = handler.render
        val valid = renderer?.renderCanvasInto(
            request.timeUs / 1000, request.width, request.height, request.force, result.buffer, result.box
        ) == true
        when {
            !valid -> AssRenderWorker.Outcome.NO_TRACK
            result.changed != 0 || request.force -> AssRenderWorker.Outcome.CHANGED
            else -> AssRenderWorker.Outcome.UNCHANGED
        }
    },
    hasTrack = { handler.render != null && handler.track != null },
    published = published,
    prepare = prepare,
    failed = { Log.e("AssRenderWorker", "Overlay render failed", it) },
    slotCount = slotCount,
)
