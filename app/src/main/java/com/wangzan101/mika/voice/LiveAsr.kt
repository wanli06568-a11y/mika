package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 文字控制帧 + 二进制 PCM 音频的流式识别(自建 Fun-ASR、百炼实时识别)。
 * 各家只在 [LiveAsrProtocol] 里不同:开场发什么、收尾发什么、怎么读服务端的消息;连接、上传、等终结果在 [StreamingAsrSession]。
 */
internal interface LiveAsrProtocol {
    /** 连上后先发的文字消息;发完等 [LiveAsrUpdate.Started] 才开始送音频。 */
    fun opening(): List<String>

    /** 录音结束、最后一包音频送完后发的文字消息;之后等 [LiveAsrUpdate.Ended]。 */
    fun closing(): List<String>

    /** 两包音频之间至少隔多少毫秒;0 表示来多少送多少(连上前攒下的音频尽快追上)。 */
    val packetGapMs: Long get() = 0

    /** 读一条服务端消息(二进制消息按 UTF-8 文字读)。服务端报错时抛 [LiveAsrError]。 */
    fun read(message: String): LiveAsrUpdate
}

internal sealed interface LiveAsrUpdate {
    /** 服务端准备好收音频了。 */
    data object Started : LiveAsrUpdate

    /** 到此刻的全文:[stable] 是已经定下的句子,[tail] 是还在变的那句。每次都是整份替换,不是增量。 */
    data class Text(val stable: String, val tail: String) : LiveAsrUpdate

    /** 这次识别结束;[text] 为 null 时用最后一次 [Text] 的全文。 */
    data class Ended(val text: String? = null) : LiveAsrUpdate

    /** 心跳、确认之类,不改文字。 */
    data object None : LiveAsrUpdate
}

/** 服务端报的错:只带错误码,不带服务端原话(可能回显凭据或说的话)。[auth] 是鉴权、额度一类,界面指去设置。 */
internal class LiveAsrError(val code: String, val auth: Boolean = false) : IOException("ASR error $code")

internal interface LiveAsrSocket {
    /** 服务端的消息;连接断了就关上(异常断开时带着原因)。 */
    val incoming: ReceiveChannel<String>
    fun sendText(text: String)
    fun sendAudio(pcm: ByteArray)
    fun close()
}

/** 文字控制帧 + 二进制音频的识别连接;服务端的二进制消息按 UTF-8 文字读。 */
internal class LiveAsrTransport(private val client: OkHttpClient = streamingAsrHttp) {
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap()): LiveAsrSocket {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val socket = openAsrWebSocket(client, request, 64, fromText = { it }, fromBytes = { it.utf8() }, size = String::length)
        return object : LiveAsrSocket {
            override val incoming = socket.incoming
            override fun sendText(text: String) = socket.sendText(text)
            override fun sendAudio(pcm: ByteArray) = socket.sendBinary(pcm)
            override fun close() = socket.close()
        }
    }
}

/**
 * 边说边转时告诉服务说的是哪种语言;[Auto] 不指定,由服务自己判断。存的是 [code](也是百炼 `language_hints` 用的语言代码),
 * 只能往后加。自建 Fun-ASR 服务的提示词按中文语言名写(「语音转写成中文」),所以另有 [funAsrName]。
 * 服务能不能识别某种语言取决于它跑的模型(Fun-ASR-Nano 只有中、英、日,多语种版本更多)。
 */
enum class AsrLanguage(val code: String, val funAsrName: String) {
    Auto("", ""),
    Chinese("zh", "中文"),
    English("en", "英文"),
    Japanese("ja", "日文"),
    Cantonese("yue", "粤语"),
    Korean("ko", "韩文"),
    ;

    companion object {
        /** 读不出来、不认识的一律当 [Auto],不让整条选择失效。 */
        fun of(code: String?): AsrLanguage = entries.firstOrNull { it.code == code } ?: Auto
    }
}

/** 拼文字:前后两段都是拉丁字母或数字时中间补一个空格,中文直接接上。 */
internal fun joinAsr(parts: List<String>): String = parts.filter(String::isNotEmpty).fold("") { acc, s ->
    if (acc.isNotEmpty() && acc.last().isLetterOrDigit() && acc.last().code < 128 && s.first().isLetterOrDigit() && s.first().code < 128) "$acc $s" else acc + s
}

/** 边说边转的一次按住说话,由 [StreamingAsrSession] 跑;按住多久都行(边说边传、不攒整段录音)。 */
internal class LiveSpeech(
    recorder: StreamingRecorder,
    protocol: LiveAsrProtocol,
    connect: suspend () -> LiveAsrSocket,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    connectTimeoutMs: Long = 10_000,
    finalTimeoutMs: Long = 10_000,
    packetSamples: Int = PACKET_SAMPLES,
) : SpeechSession by StreamingAsrSession(
    recorder, LiveAsrStream(protocol, connect, packetSamples), scope, connectTimeoutMs, finalTimeoutMs, unlimited = true,
) {
    companion object {
        /** 100 ms 一包(16 kHz 单声道)。 */
        const val PACKET_SAMPLES = 1600
    }
}

/** [LiveAsrProtocol] 接到会话上:开场、收尾发文字,音频按二进制送,结果是整份替换的全文。 */
private class LiveAsrStream(
    private val protocol: LiveAsrProtocol,
    private val connector: suspend () -> LiveAsrSocket,
    override val packetSamples: Int,
) : AsrStream {
    private var socket: LiveAsrSocket? = null
    private var text = LiveAsrUpdate.Text("", "")
    override val packetGapMs get() = protocol.packetGapMs
    override val invalidResponse = "Invalid ASR response"

    override fun serviceError(e: Exception): ModelError? =
        (e as? LiveAsrError)?.let { if (it.auth) ModelError.Auth() else ModelError.BadResponse("ASR error ${it.code}") }

    override suspend fun connect() { socket = connector() }
    override fun open() { protocol.opening().forEach(socket!!::sendText) }

    override suspend fun awaitStart(partial: (String, String) -> Unit): String? {
        while (true) {
            when (val u = protocol.read(socket!!.incoming.receive())) {
                LiveAsrUpdate.Started -> return null
                is LiveAsrUpdate.Text -> text = u
                is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                LiveAsrUpdate.None -> Unit
            }
        }
    }

    override suspend fun awaitFinal(partial: (String, String) -> Unit): String {
        for (message in socket!!.incoming) {
            when (val u = protocol.read(message)) {
                is LiveAsrUpdate.Text -> if (u != text) {
                    text = u
                    if (u.stable.isNotEmpty() || u.tail.isNotEmpty()) partial(u.stable, u.tail)
                }
                is LiveAsrUpdate.Ended -> return (u.text ?: joinAsr(listOf(text.stable, text.tail))).trim()
                LiveAsrUpdate.Started, LiveAsrUpdate.None -> Unit
            }
        }
        throw IOException("ASR closed before final result")
    }

    override fun sendAudio(pcm: ShortArray, count: Int, last: Boolean) = socket!!.sendAudio(Pcm16.toBytes(pcm, count))
    override fun endAudio() { protocol.closing().forEach(socket!!::sendText) }
    override fun close() { socket?.close() }
}

/**
 * 不说话只走开场:连得上、服务端回了「开始」(地址、鉴权、模型都认)就算通,随后发收尾消息、断开。
 * 设置页保存前用,不送音频,不产生识别时长;不等服务端对空识别的收尾回应(各家对没有音频的识别怎么收尾不一定)。
 * 失败抛 [LiveAsrError]、[ModelError] 或 IOException。
 */
internal suspend fun probeLiveAsr(protocol: LiveAsrProtocol, connect: suspend () -> LiveAsrSocket, timeoutMs: Long = 8_000) {
    withTimeout(timeoutMs) {
        val socket = connect()
        try {
            protocol.opening().forEach(socket::sendText)
            for (message in socket.incoming) {
                when (protocol.read(message)) {
                    LiveAsrUpdate.Started -> {
                        runCatching { protocol.closing().forEach(socket::sendText) }
                        return@withTimeout
                    }
                    is LiveAsrUpdate.Ended -> throw IOException("ASR ended before start")
                    else -> Unit
                }
            }
            throw IOException("ASR closed during probe")
        } finally { socket.close() }
    }
}
