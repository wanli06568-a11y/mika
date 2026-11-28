package com.wangzan101.mika.cap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture

// 阻塞读进程输出用的小工具:读不能取消,所以放在自己的守护线程上,并且限长。

/** 在单独的守护线程 [name] 上做阻塞的事(不占公共线程池);结果或抛出的异常交给返回的 future。 */
internal fun <T> onDaemonThread(name: String, block: () -> T): CompletableFuture<T> = CompletableFuture<T>().also { f ->
    Thread({ runCatching(block).fold({ f.complete(it) }, { f.completeExceptionally(it) }) }, name).apply { isDaemon = true }.start()
}

/** 把 [stream] 读到结束,最多留 [limit] 字节,多的读掉丢弃并计数;读出错就停在那里,已读到的照样返回。 */
internal fun drainBounded(stream: InputStream, limit: Int): Pair<ByteArray, Long> =
    OutputCollector(limit).also { runCatching { it.drain(stream) } }.snapshot()

/** 读到流结束;最多留 [limit] 字节,多的读掉丢弃并计数(不读完命令会卡在写输出上)。读的途中也能取快照。 */
internal class OutputCollector(private val limit: Int) {
    private val kept = ByteArrayOutputStream(minOf(limit, READ_CHUNK))
    private var dropped = 0L

    fun drain(input: InputStream) {
        val buf = ByteArray(READ_CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            synchronized(this) {
                val room = limit - kept.size()
                if (room > 0) kept.write(buf, 0, minOf(n, room))
                if (n > room) dropped += n - maxOf(room, 0)
            }
        }
    }

    @Synchronized fun snapshot(): Pair<ByteArray, Long> = kept.toByteArray() to dropped

    private companion object {
        const val READ_CHUNK = 64 * 1024
    }
}
