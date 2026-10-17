package io.github.peerless2012.ass

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock

/**
 * Lets the loading thread hand chunks to libass without waiting out a render: if the lock is
 * busy the chunk is queued, and the next lock holder feeds it in first.
 */
internal class AssChunkQueue(
    private val lock: ReentrantLock,
    private val released: () -> Boolean,
    private val process: (Long, Long, ByteArray, Int, Int) -> Unit,
) {

    private class Chunk(val start: Long, val duration: Long, val bytes: ByteArray)

    @Volatile
    private var pending = ConcurrentLinkedQueue<Chunk>()

    fun readChunk(start: Long, duration: Long, array: ByteArray, offset: Int, length: Int) {
        val queue = pending
        if (released()) return
        require(offset >= 0 && length >= 0 && offset <= array.size - length)
        if (!lock.tryLock()) {
            queue.add(Chunk(start, duration, array.copyOfRange(offset, offset + length)))
            return
        }
        try {
            if (released() || queue !== pending) return
            drain()
            process(start, duration, array, offset, length)
        } finally {
            lock.unlock()
        }
    }

    fun drain() {
        check(lock.isHeldByCurrentThread)
        if (released()) return
        // Order does not matter: libass drops duplicate ReadOrders and sorts events by layer and ReadOrder.
        while (true) {
            val chunk = pending.poll() ?: return
            process(chunk.start, chunk.duration, chunk.bytes, 0, chunk.bytes.size)
        }
    }

    fun clear() {
        check(lock.isHeldByCurrentThread)
        // Swapping also discards writes racing with a clear or release on the old queue.
        pending = ConcurrentLinkedQueue()
    }

}
