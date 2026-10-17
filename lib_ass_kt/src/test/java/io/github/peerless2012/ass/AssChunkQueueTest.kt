package io.github.peerless2012.ass

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AssChunkQueueTest {

    private val lock = ReentrantLock()
    private val processed = mutableListOf<Triple<Long, Long, ByteArray>>()
    private var released = false
    private val chunks = AssChunkQueue(lock, { released }) { start, duration, array, offset, length ->
        assertTrue(lock.isHeldByCurrentThread)
        processed.add(Triple(start, duration, array.copyOfRange(offset, offset + length)))
    }

    private fun whileHeld(action: () -> Unit) {
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val holder = Thread {
            lock.withLock {
                entered.countDown()
                finish.await()
            }
        }
        holder.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            action()
        } finally {
            finish.countDown()
            holder.join(3000)
            assertFalse(holder.isAlive)
        }
    }

    @Test
    fun freeLockProcessesDirectly() {
        chunks.readChunk(10, 20, byteArrayOf(0, 1, 2, 3), 1, 2)
        assertEquals(1, processed.size)
        assertEquals(10L, processed[0].first)
        assertEquals(20L, processed[0].second)
        assertArrayEquals(byteArrayOf(1, 2), processed[0].third)
        assertFalse(lock.isLocked)
    }

    @Test(timeout = 5000)
    fun heldLockQueuesUntilNextHolderDrains() {
        whileHeld {
            chunks.readChunk(10, 20, byteArrayOf(1), 0, 1)
            assertTrue(processed.isEmpty())
        }
        lock.withLock { chunks.drain() }
        assertEquals(1, processed.size)
        assertEquals(10L, processed[0].first)
        assertEquals(20L, processed[0].second)
    }

    @Test(timeout = 5000)
    fun successfulReadDrainsBeforeDirectChunk() {
        whileHeld { chunks.readChunk(10, 20, byteArrayOf(1), 0, 1) }
        chunks.readChunk(30, 40, byteArrayOf(2), 0, 1)
        assertEquals(listOf(10L, 30L), processed.map { it.first })
    }

    @Test(timeout = 5000)
    fun clearDropsQueuedChunks() {
        whileHeld { chunks.readChunk(10, 20, byteArrayOf(1), 0, 1) }
        lock.withLock { chunks.clear() }
        lock.withLock { chunks.drain() }
        assertTrue(processed.isEmpty())
        chunks.readChunk(30, 40, byteArrayOf(2), 0, 1)
        assertEquals(listOf(30L), processed.map { it.first })
    }

    @Test(timeout = 5000)
    fun queuedBytesCopyOnlyRequestedRange() {
        val bytes = byteArrayOf(0, 1, 2, 3)
        whileHeld { chunks.readChunk(10, 20, bytes, 1, 2) }
        bytes.fill(9)
        lock.withLock { chunks.drain() }
        assertArrayEquals(byteArrayOf(1, 2), processed.single().third)
    }

    @Test(timeout = 5000)
    fun releaseDiscardsPendingAndLaterChunks() {
        whileHeld { chunks.readChunk(10, 20, byteArrayOf(1), 0, 1) }
        lock.withLock {
            released = true
            chunks.clear()
            chunks.drain()
        }
        chunks.readChunk(30, 40, byteArrayOf(2), 0, 1)
        assertTrue(processed.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun drainRequiresLock() {
        chunks.drain()
    }

    @Test(timeout = 5000)
    fun clearDiscardsEnqueueAlreadyInFlight() {
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val queue = AssChunkQueue(lock, {
            checked.countDown()
            resume.await()
            false
        }) { _, _, _, _, _ -> fail("Cleared chunk was processed") }
        val writer = Thread { queue.readChunk(10, 20, byteArrayOf(1), 0, 1) }
        lock.withLock {
            try {
                writer.start()
                assertTrue(checked.await(3, TimeUnit.SECONDS))
                queue.clear()
            } finally {
                resume.countDown()
                writer.join(3000)
            }
            assertFalse(writer.isAlive)
            queue.drain()
        }
    }

    @Test(timeout = 5000)
    fun clearDiscardsStaleWriterThatAcquiresLockAfterClearUnlocks() {
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val processed = mutableListOf<Long>()
        val queue = AssChunkQueue(lock, {
            captured.countDown()
            assertTrue(resume.await(3, TimeUnit.SECONDS))
            false
        }) { start, _, _, _, _ -> processed.add(start) }
        val writer = Thread {
            try {
                queue.readChunk(10, 20, byteArrayOf(1), 0, 1)
            } catch (error: Throwable) {
                failure.set(error)
            }
        }
        try {
            writer.start()
            assertTrue(captured.await(3, TimeUnit.SECONDS))
            lock.withLock { queue.clear() }
        } finally {
            resume.countDown()
            writer.join(3000)
        }
        assertFalse(writer.isAlive)
        failure.get()?.let { throw it }
        lock.withLock { queue.drain() }
        assertTrue(processed.isEmpty())
        queue.readChunk(30, 40, byteArrayOf(2), 0, 1)
        assertEquals(listOf(30L), processed)
    }

}
