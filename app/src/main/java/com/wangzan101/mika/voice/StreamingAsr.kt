package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * 流式识别(豆包、自建 Fun-ASR、百炼)共用的一个 client,也就只有一个连接池。
 * 独立于对话模型的 client:不跟随重定向(凭据只发往填的那个地址),读不设超时(说话时可能很久没有结果),不自动重试。
 */
internal val streamingAsrHttp: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(0, TimeUnit.MILLISECONDS).followRedirects(false).followSslRedirects(false)
    .retryOnConnectionFailure(false).build()

/** 连好的识别 WebSocket:服务端消息进有界的 [incoming],连接断了就关上(异常断开时带着原因)。 */
internal class AsrWebSocket<T>(val incoming: Channel<T>, private val socket: WebSocket) {
    fun sendText(text: String) { if (!socket.send(text)) throw IOException("ASR send failed") }

    /** 发送队列积压超过 256 KiB(网络卡住了)就算失败,不无限攒音频。 */
    fun sendBinary(data: ByteArray) {
        if (socket.queueSize() > MAX_QUEUE || !socket.send(data.toByteString())) throw IOException("ASR send failed")
    }

    fun close() { socket.cancel(); incoming.cancel() }

    private companion object { const val MAX_QUEUE = 256 * 1024L }
}

/**
 * 连上就返回;握手失败时 401/403 报 [ModelError.Auth]、429 报 [ModelError.RateLimited],其余算网络错误,都不带服务端原话。
 * 收到的消息经 [fromText]/[fromBytes] 转成 [T](可以抛 IOException 拒收);单条超过 1 MiB([size] 量)或缓冲满了就断开。
 */
internal suspend fun <T> openAsrWebSocket(
    client: OkHttpClient,
    request: Request,
    capacity: Int,
    fromText: (String) -> T,
    fromBytes: (ByteString) -> T,
    size: (T) -> Int,
): AsrWebSocket<T> {
    val ready = CompletableDeferred<Unit>()
    val messages = Channel<T>(capacity)
    val socket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
        override fun onMessage(webSocket: WebSocket, text: String) { offer(webSocket) { fromText(text) } }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { offer(webSocket) { fromBytes(bytes) } }
        private inline fun offer(webSocket: WebSocket, read: () -> T) {
            val message = try { read() } catch (e: IOException) { messages.close(e); webSocket.cancel(); return }
            if (size(message) > MAX_MESSAGE || messages.trySend(message).isFailure) {
                messages.close(IOException("ASR response buffer exceeded")); webSocket.cancel()
            }
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            messages.close(); webSocket.close(code, null)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close() }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val error = when (response?.code) {
                401, 403 -> ModelError.Auth()
                429 -> ModelError.RateLimited(null)
                else -> ModelError.Network()
            }
            response?.close()
            ready.completeExceptionally(error)
            messages.close(error)
        }
    })
    try { ready.await() } catch (e: Throwable) { socket.cancel(); messages.cancel(); throw e }
    return AsrWebSocket(messages, socket)
}

private const val MAX_MESSAGE = 1024 * 1024

/**
 * 一种流式识别协议在一次会话里要做的事:连接、开场、等开始、读结果、送音频、收尾。每次会话一个新的实例(可以带状态)。
 * 录音、上传和收结果并行、各段时限、出错怎么报,都由 [StreamingAsrSession] 管。
 */
internal interface AsrStream {
    /** 一包多少个采样。 */
    val packetSamples: Int

    /** 两包音频之间至少隔多少毫秒;0 表示来多少送多少。 */
    val packetGapMs: Long

    /** 最后一包是空的也要发(协议靠它标记音频结束);否则没有剩余音频就不发。 */
    val sendsEmptyLast: Boolean get() = false

    /** 整次会话(连接到终结果)的上限;null 不设。 */
    val totalTimeoutMs: Long? get() = null

    /** 读不懂服务端回复时报的说法。 */
    val invalidResponse: String

    /** 协议自己的错误(服务端报的错误码)报成什么;不是这类错误返回 null。 */
    fun serviceError(e: Exception): ModelError?

    suspend fun connect()

    /** 连上后先发的消息。 */
    fun open()

    /**
     * 等服务端确认可以送音频(在连接时限内);中途的文字经 [partial] 交出。
     * 返回非 null 表示服务端已经给了终结果,不再送音频。
     */
    suspend fun awaitStart(partial: (stable: String, tail: String) -> Unit): String?

    /** 读到服务端说结束为止,返回最终文字;连接先断了抛 IOException。 */
    suspend fun awaitFinal(partial: (stable: String, tail: String) -> Unit): String

    /** 送 [pcm] 的前 [count] 个采样;[last] 是录音结束后的最后一包。 */
    fun sendAudio(pcm: ShortArray, count: Int, last: Boolean)

    /** 音频全部送完之后要发的收尾消息。 */
    fun endAudio() {}

    /** 断开;还没连上也可以调。 */
    fun close()
}

/**
 * 一次按住说话:录音和上传、收结果同时进行;松开后送完剩下的音频和收尾消息,等服务端说结束才给 Final。
 * 断开、超时都算失败,不把没收完的中间结果当最终文字交出去(交出去的字会接着发给模型)。
 * 连接、等开始、等终结果各有时限;整次会话的上限看协议([AsrStream.totalTimeoutMs])。
 */
internal class StreamingAsrSession(
    private val recorder: StreamingRecorder,
    private val stream: AsrStream,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val connectTimeoutMs: Long = 10_000,
    private val finalTimeoutMs: Long = 10_000,
    override val unlimited: Boolean = false,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true
    private var work: Job? = null
    private var levels: Job? = null
    private var stopping: Job? = null
    private var released = false

    init {
        if (!recorder.start()) out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
            work = scope.launch { runSession() }
        }
    }

    private fun partial(stable: String, tail: String) = out.send(SpeechEvent.Partial(stable, tail))

    private suspend fun runSession() {
        try {
            val total = stream.totalTimeoutMs
            if (total == null) converse() else withTimeout(total) { converse() }
        } catch (e: TimeoutCancellationException) {
            out.finish(SpeechEvent.Failed(SpeechFailure.Network))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out.finish(SpeechEvent.Failed(failure(e)))
        } finally {
            stream.close()
            levels?.cancel()
            recorder.cancel()
        }
    }

    private fun failure(e: Exception): SpeechFailure = stream.serviceError(e)?.let(SpeechFailure::Provider) ?: when (e) {
        is ModelError -> SpeechFailure.Provider(e)
        is IOException -> SpeechFailure.Network
        else -> SpeechFailure.Provider(ModelError.BadResponse(stream.invalidResponse))
    }

    private suspend fun converse() {
        withTimeout(connectTimeoutMs) { stream.connect() }
        stream.open()
        // 服务端确认前不送音频;这期间的录音留在录音器的队列里
        val early = withTimeout(connectTimeoutMs) { stream.awaitStart(::partial) }
        if (early != null) { out.finish(SpeechEvent.Final(early)); return }
        coroutineScope {
            val result = async { stream.awaitFinal(::partial) }
            val audioEnded = CompletableDeferred<Unit>()
            val sender = launch {
                val buffer = ShortArray(stream.packetSamples)
                var size = 0
                var next = 0L
                suspend fun send(count: Int, last: Boolean) {
                    val wait = next - System.nanoTime()
                    if (wait > 0) delay((wait + 999_999) / 1_000_000)
                    stream.sendAudio(buffer, count, last)
                    next = System.nanoTime() + stream.packetGapMs * 1_000_000
                }
                for (chunk in recorder.chunks) {
                    var offset = 0
                    while (offset < chunk.size) {
                        val count = minOf(buffer.size - size, chunk.size - offset)
                        chunk.copyInto(buffer, size, offset, offset + count)
                        size += count; offset += count
                        if (size == buffer.size) { send(size, last = false); size = 0 }
                    }
                }
                if (size > 0 || stream.sendsEmptyLast) send(size, last = true)
                stream.endAudio()
                audioEnded.complete(Unit)
            }
            try {
                val finalText = select<String> {
                    result.onAwait { it }
                    audioEnded.onAwait { withTimeout(finalTimeoutMs) { result.await() } }
                }
                out.finish(SpeechEvent.Final(finalText))
            } finally { sender.cancelAndJoin() }
        }
    }

    @Synchronized override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        out.send(SpeechEvent.Converting)
        levels?.cancel()
        stopping = scope.launch { recorder.stop() }
    }

    @Synchronized override fun cancel() {
        if (!out.cancel()) return
        work?.cancel(); levels?.cancel(); stopping?.cancel()
        recorder.cancel()
    }
}
