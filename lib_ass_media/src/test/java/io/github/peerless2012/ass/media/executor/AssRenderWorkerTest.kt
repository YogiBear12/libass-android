package io.github.peerless2012.ass.media.executor

import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.media.executor.AssRenderWorker.Outcome
import io.github.peerless2012.ass.media.executor.AssRenderWorker.Result
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AssRenderWorkerTest {
    private fun CountDownLatch.awaitReady() = assertTrue(await(3, TimeUnit.SECONDS))

    @Test
    fun requestWithoutTrackAllocatesNothingUntilTrackRenders() {
        val publications = LinkedBlockingQueue<Unit>()
        val checked = LinkedBlockingQueue<Unit>()
        val trackPresent = AtomicBoolean(false)
        val allocations = AtomicInteger()
        val preparations = AtomicInteger()
        val worker = AssRenderWorker(
            hasTrack = {
                checked.add(Unit)
                trackPresent.get()
            },
            render = { _, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                Outcome.CHANGED
            },
            allocateBuffer = {
                allocations.incrementAndGet()
                ByteBuffer.allocateDirect(it)
            },
            prepare = { preparations.incrementAndGet() },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 1920, 1080, true)
            assertNotNull(checked.poll(3, TimeUnit.SECONDS))
            assertNull(publications.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(0, allocations.get())
            assertEquals(0, preparations.get())
            trackPresent.set(true)
            worker.request(3, 2, 2, true)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val visible = worker.acquire()!!
            assertEquals(1, visible.width)
            assertEquals(2, allocations.get())
            trackPresent.set(false)
            worker.request(4, 2, 2, true)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val empty = worker.acquire()!!
            assertEquals(0, empty.width)
            assertEquals(0, empty.height)
            assertEquals(0, empty.buffer.capacity())
            assertEquals(2, empty.renderWidth)
            assertNull(empty.bitmap)
            worker.release(visible)
            worker.release(empty)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun losingTrackDropsSlotsWithoutChangingOutstandingBuffers() {
        for (lostDuringRender in listOf(false, true)) {
            val publications = LinkedBlockingQueue<Unit>()
            val trackPresent = AtomicBoolean(true)
            val allocations = AtomicInteger()
            val worker = AssRenderWorker(
                hasTrack = { trackPresent.get() },
                render = { request, result ->
                    if (request.timeUs == 2L) {
                        Outcome.NO_TRACK
                    } else {
                        result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                        result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                        result.buffer.put(0, request.timeUs.toByte())
                        Outcome.CHANGED
                    }
                },
                allocateBuffer = {
                    allocations.incrementAndGet()
                    ByteBuffer.allocateDirect(it)
                },
                published = { publications.add(Unit) },
            )
            try {
                worker.request(1, 2, 2)
                assertNotNull(publications.poll(3, TimeUnit.SECONDS))
                val held = worker.acquire()!!
                assertEquals(2, allocations.get())
                trackPresent.set(lostDuringRender)
                worker.request(2, 2, 2, true)
                assertNotNull(publications.poll(3, TimeUnit.SECONDS))
                val empty = worker.acquire()!!
                assertEquals(0, empty.width)
                assertEquals(0, empty.height)
                assertEquals(0, empty.buffer.capacity())
                assertEquals(2, empty.renderWidth)
                assertEquals(2, empty.renderHeight)
                assertEquals(2, allocations.get())
                assertEquals(1.toByte(), held.buffer.get(0))
                trackPresent.set(true)
                worker.request(3, 2, 2, true)
                assertNotNull(publications.poll(3, TimeUnit.SECONDS))
                val next = worker.acquire()!!
                assertEquals(4, allocations.get())
                assertNotSame(held.buffer, next.buffer)
                assertEquals(3.toByte(), next.buffer.get(0))
                assertEquals(1.toByte(), held.buffer.get(0))
                worker.release(empty)
                worker.release(held)
                worker.release(next)
            } finally {
                worker.shutdown()
            }
        }
    }

    @Test
    fun dialogueGapKeepsSlotsForNextContent() {
        val publications = LinkedBlockingQueue<Unit>()
        val allocations = AtomicInteger()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = if (request.timeUs == 2L) 0 else 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = result.width
                Outcome.CHANGED
            },
            allocateBuffer = {
                allocations.incrementAndGet()
                ByteBuffer.allocateDirect(it)
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val first = worker.acquire()!!
            worker.release(first)
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val gap = worker.acquire()!!
            assertEquals(0, gap.width)
            assertEquals(0, gap.buffer.capacity())
            worker.release(gap)
            worker.request(3, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val next = worker.acquire()!!
            assertSame(first, next)
            assertEquals(2, allocations.get())
            worker.release(next)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun noTrackClearFollowsInFlightContentAndBypassesHeldSlots() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val publications = LinkedBlockingQueue<Unit>()
        val trackPresent = AtomicBoolean(true)
        val worker = AssRenderWorker(
            hasTrack = { trackPresent.get() },
            render = { request, result ->
                if (request.timeUs == 2L) {
                    entered.countDown()
                    finish.awaitReady()
                }
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                result.buffer.put(0, request.timeUs.toByte())
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            worker.request(2, 2, 2)
            entered.awaitReady()
            trackPresent.set(false)
            worker.request(3, 2, 2, true)
            finish.countDown()
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val empty = worker.acquire()!!
            assertEquals(0, empty.width)
            assertEquals(0, empty.buffer.capacity())
            assertEquals(1.toByte(), held.buffer.get(0))
            worker.release(held)
            worker.release(empty)
        } finally {
            finish.countDown()
            worker.shutdown()
        }
    }

    @Test
    fun newestWinsAndPendingForceSurvivesOverwrite() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val rendered = LinkedBlockingQueue<AssRenderWorker.Request>()
        val errors = LinkedBlockingQueue<Exception>()
        val worker = AssRenderWorker(
            render = { request, _ ->
                if (request.timeUs == 1L) {
                    entered.countDown()
                    finish.awaitReady()
                }
                rendered.add(request)
                Outcome.CHANGED
            },
            published = {},
            failed = { errors.add(it) },
        )
        try {
            worker.request(1, 2, 2)
            entered.awaitReady()
            worker.request(2, 2, 2, true)
            worker.request(3, 2, 2)
            worker.request(4, 2, 2)
            finish.countDown()
            assertEquals(1L, rendered.poll(3, TimeUnit.SECONDS)!!.timeUs)
            val newest = rendered.poll(3, TimeUnit.SECONDS)!!
            assertEquals(4L, newest.timeUs)
            assertTrue(newest.force)
        } finally {
            finish.countDown()
            worker.shutdown()
        }
        assertTrue(errors.isEmpty())
        assertTrue(rendered.isEmpty())
    }

    @Test
    fun heldBufferBlocksWorkerWithoutDroppingPendingRequest() {
        val publications = LinkedBlockingQueue<Unit>()
        val starts = LinkedBlockingQueue<Long>()
        val worker = AssRenderWorker(
            render = { request, result ->
                starts.add(request.timeUs)
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                result.buffer.put(0, request.timeUs.toByte())
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertEquals(1L, starts.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertEquals(2L, starts.poll(3, TimeUnit.SECONDS))
            worker.request(3, 2, 2)
            assertNull(starts.poll(100, TimeUnit.MILLISECONDS))
            worker.request(4, 2, 2, true)
            assertEquals(1.toByte(), held.buffer.get(0))
            worker.release(held)
            assertEquals(4L, starts.poll(3, TimeUnit.SECONDS))
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val newest = worker.acquire()!!
            assertEquals(4.toByte(), newest.buffer.get(0))
            worker.release(newest)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun fourSlotsRenderAheadWithoutWritingOutstandingSlotsUntilReleased() {
        val publications = LinkedBlockingQueue<Unit>()
        val writes = LinkedBlockingQueue<AssRenderWorker.Result>()
        val worker = AssRenderWorker(
            render = { request, result ->
                writes.add(result)
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                result.buffer.put(0, request.timeUs.toByte())
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
            slotCount = 4,
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            assertSame(held, writes.poll(3, TimeUnit.SECONDS))
            assertNull(worker.acquire())
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val newer = worker.acquire()!!
            assertSame(newer, writes.poll(3, TimeUnit.SECONDS))
            assertNotSame(held, newer)
            for (time in 3L..5L) {
                worker.request(time, 2, 2)
                assertNotNull(publications.poll(3, TimeUnit.SECONDS))
                val written = writes.poll(3, TimeUnit.SECONDS)
                assertNotSame(held, written)
                assertNotSame(newer, written)
                assertEquals(1.toByte(), held.buffer.get(0))
            }
            worker.release(held)
            worker.request(6, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertSame(held, writes.poll(3, TimeUnit.SECONDS))
            assertEquals(2.toByte(), newer.buffer.get(0))
            assertSame(held, worker.acquire())
            worker.release(newer)
            worker.release(held)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun outstandingSlotsBlockWorkerUntilReleaseMakesOneWritable() {
        val publications = LinkedBlockingQueue<Unit>()
        val writes = LinkedBlockingQueue<Result>()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                result.buffer.put(0, request.timeUs.toByte())
                writes.add(result)
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val previous = worker.acquire()!!
            assertSame(previous, writes.poll(3, TimeUnit.SECONDS))
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val current = worker.acquire()!!
            assertSame(current, writes.poll(3, TimeUnit.SECONDS))
            worker.request(3, 2, 2)
            assertNull(writes.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1.toByte(), previous.buffer.get(0))
            worker.release(previous)
            assertSame(previous, writes.poll(3, TimeUnit.SECONDS))
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertEquals(3.toByte(), previous.buffer.get(0))
            assertEquals(2.toByte(), current.buffer.get(0))
            assertSame(previous, worker.acquire())
            worker.release(current)
            worker.release(previous)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun unconsumedEmptyFrameSurvivesPoolGrowth() {
        val publications = LinkedBlockingQueue<Unit>()
        val writes = LinkedBlockingQueue<Result>()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = if (request.timeUs == 1L) 1 else 0
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = result.width
                writes.add(result)
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
            slotCount = 4,
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val visible = worker.acquire()!!
            assertSame(visible, writes.poll(3, TimeUnit.SECONDS))
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertNotNull(writes.poll(3, TimeUnit.SECONDS))
            worker.request(3, 4, 4)
            val grown = writes.poll(3, TimeUnit.SECONDS)!!
            assertEquals(4, grown.capacityWidth)
            val empty = worker.acquire()!!
            assertEquals(2, empty.renderWidth)
            assertEquals(2, empty.renderHeight)
            assertEquals(0, empty.width)
            assertEquals(0, empty.height)
            assertEquals(0, empty.buffer.capacity())
            worker.release(visible)
            worker.release(empty)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun outstandingBufferSurvivesGrowthAndLaterAcquisition() {
        val publications = LinkedBlockingQueue<Unit>()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                result.buffer.put(0, request.timeUs.toByte())
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
            slotCount = 3,
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            worker.request(2, 4, 4)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            assertEquals(1.toByte(), held.buffer.get(0))
            assertEquals(16, held.buffer.capacity())
            val newer = worker.acquire()!!
            assertNotSame(held, newer)
            assertEquals(64, newer.buffer.capacity())
            assertEquals(2.toByte(), newer.buffer.get(0))
            worker.release(held)
            worker.release(newer)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun shrinkingAndRegrowingReusePoolWithinCapacity() {
        val publications = LinkedBlockingQueue<Unit>()
        val renders = LinkedBlockingQueue<AssRenderWorker.Request>()
        val worker = AssRenderWorker(
            render = { request, result ->
                renders.add(request)
                result.box[AssRender.CANVAS_RESULT_WIDTH] = request.width
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = request.height
                result.buffer.put(0, request.timeUs.toByte())
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 4, 4)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            renders.poll(3, TimeUnit.SECONDS)
            val first = worker.acquire()!!
            worker.release(first)
            for (size in listOf(2, 3, 4)) {
                worker.request(size.toLong(), size, size)
                assertNotNull(publications.poll(3, TimeUnit.SECONDS))
                assertTrue(renders.poll(3, TimeUnit.SECONDS)!!.force)
                val next = worker.acquire()!!
                assertSame(first, next)
                assertEquals(64, next.buffer.capacity())
                assertEquals(size * size * BYTES_PER_PIXEL, next.buffer.limit())
                assertEquals(size, next.renderWidth)
                assertEquals(size, next.renderHeight)
                worker.release(next)
            }
            worker.request(5, 8, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val grown = worker.acquire()!!
            assertNotSame(first, grown)
            assertEquals(8, grown.capacityWidth)
            assertEquals(4, grown.capacityHeight)
            assertEquals(128, grown.buffer.capacity())
            assertEquals(64, grown.buffer.limit())
            worker.release(grown)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun emptyFramesOnlyPublishToClearVisibleContentAndNeverPrepare() {
        val publications = LinkedBlockingQueue<Unit>()
        val renders = LinkedBlockingQueue<Long>()
        val preparations = AtomicInteger()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = if (request.timeUs == 2L) 1 else 0
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = result.width
                renders.add(request.timeUs)
                Outcome.CHANGED
            },
            prepare = { preparations.incrementAndGet() },
            published = { publications.add(Unit) },
            slotCount = 3,
        )
        try {
            worker.request(1, 2, 2, true)
            assertEquals(1L, renders.poll(3, TimeUnit.SECONDS))
            assertNull(publications.poll(100, TimeUnit.MILLISECONDS))
            worker.request(2, 2, 2)
            assertEquals(2L, renders.poll(3, TimeUnit.SECONDS))
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val visible = worker.acquire()!!
            worker.request(3, 2, 2)
            assertEquals(3L, renders.poll(3, TimeUnit.SECONDS))
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val empty = worker.acquire()!!
            assertNotSame(visible, empty)
            assertEquals(0, empty.width)
            assertNull(empty.bitmap)
            worker.request(4, 2, 2, true)
            assertEquals(4L, renders.poll(3, TimeUnit.SECONDS))
            assertNull(publications.poll(100, TimeUnit.MILLISECONDS))
            assertNull(worker.acquire())
            assertEquals(1, preparations.get())
            worker.release(visible)
            worker.release(empty)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun emptyResultReplacesVisibleFrameAndRemainsOutstanding() {
        val publications = LinkedBlockingQueue<Unit>()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = if (request.timeUs == 1L) 1 else 0
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = result.box[AssRender.CANVAS_RESULT_WIDTH]
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
            slotCount = 3,
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            worker.request(2, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val empty = worker.acquire()!!
            assertNotSame(held, empty)
            assertEquals(0, empty.width)
            assertEquals(0, empty.height)
            assertEquals(0, empty.buffer.limit())
            assertNull(worker.acquire())
            worker.release(held)
            worker.release(empty)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun shutdownWithOutstandingSlotRejectsAcquireAndStillAllowsRelease() {
        val publications = LinkedBlockingQueue<Unit>()
        val stopped = CountDownLatch(1)
        val worker = AssRenderWorker(
            render = { _, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                Outcome.CHANGED
            },
            published = { publications.add(Unit) },
            slotCount = 3,
        )
        val shutdown = Thread {
            worker.shutdown()
            stopped.countDown()
        }
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val held = worker.acquire()!!
            shutdown.start()
            stopped.awaitReady()
            assertNull(worker.acquire())
            worker.release(held)
        } finally {
            worker.shutdown()
            shutdown.join(3000)
        }
    }

    @Test
    fun unchangedPreservesPublishedResultAndGrowthDoesNotWaitForOutstandingBuffer() {
        val publications = LinkedBlockingQueue<Unit>()
        val starts = LinkedBlockingQueue<AssRenderWorker.Request>()
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                starts.add(request)
                if (request.timeUs != 2L) Outcome.CHANGED else Outcome.UNCHANGED
            },
            published = { publications.add(Unit) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            starts.poll(3, TimeUnit.SECONDS)
            worker.request(2, 2, 2)
            assertEquals(2L, starts.poll(3, TimeUnit.SECONDS)!!.timeUs)
            val held = worker.acquire()!!
            assertEquals(16, held.buffer.capacity())
            worker.request(3, 4, 4)
            val resized = starts.poll(3, TimeUnit.SECONDS)!!
            assertTrue(resized.force)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
            val newest = worker.acquire()!!
            assertEquals(64, newest.buffer.capacity())
            assertEquals(16, held.buffer.capacity())
            worker.release(held)
            worker.release(newest)
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun failedPreparationForcesNextRequestEvenWhenMailboxWasEmpty() {
        val renders = LinkedBlockingQueue<AssRenderWorker.Request>()
        val errors = LinkedBlockingQueue<Exception>()
        val publications = LinkedBlockingQueue<Unit>()
        var first = true
        val worker = AssRenderWorker(
            render = { request, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                renders.add(request)
                Outcome.CHANGED
            },
            prepare = {
                if (first) {
                    first = false
                    throw IllegalStateException("Preparation failed")
                }
            },
            published = { publications.add(Unit) },
            failed = { errors.add(it) },
        )
        try {
            worker.request(1, 2, 2)
            assertNotNull(errors.poll(3, TimeUnit.SECONDS))
            renders.poll(3, TimeUnit.SECONDS)
            worker.request(2, 2, 2)
            val retry = renders.poll(3, TimeUnit.SECONDS)!!
            assertEquals(2L, retry.timeUs)
            assertTrue(retry.force)
            assertNotNull(publications.poll(3, TimeUnit.SECONDS))
        } finally {
            worker.shutdown()
        }
    }

    @Test
    fun shutdownDoesNotWaitForActiveRenderAndRejectsLaterRequests() {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val renders = AtomicInteger()
        val publications = AtomicInteger()
        val worker = AssRenderWorker(
            render = { _, result ->
                result.box[AssRender.CANVAS_RESULT_WIDTH] = 1
                result.box[AssRender.CANVAS_RESULT_HEIGHT] = 1
                renders.incrementAndGet()
                entered.countDown()
                finish.awaitReady()
                finished.countDown()
                Outcome.CHANGED
            },
            published = { publications.incrementAndGet() },
        )
        val shutdown = Thread {
            worker.shutdown()
            stopped.countDown()
        }
        try {
            worker.request(1, 2, 2)
            entered.awaitReady()
            shutdown.start()
            stopped.awaitReady()
            finish.countDown()
            finished.awaitReady()
            worker.request(2, 2, 2)
            assertEquals(1, renders.get())
            assertEquals(0, publications.get())
            assertNull(worker.acquire())
        } finally {
            finish.countDown()
            worker.shutdown()
            shutdown.join(3000)
        }
    }
}
