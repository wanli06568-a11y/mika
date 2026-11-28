package com.wangzan101.mika.voice

import com.wangzan101.mika.core.model.ModelError
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.OkHttpClient
import okhttp3.Request

/** A bounded stream: capture must fail rather than silently drop audio when the network stalls. */
interface StreamingRecorder : Recorder {
    val chunks: ReceiveChannel<ShortArray>
}

internal interface DoubaoSocket {
    val incoming: ReceiveChannel<ByteArray>
    fun send(packet: ByteArray)
    fun close()
}

/** Speech-only connection: Ark/provider headers, keys and retry policies are not reused; binary frames only. */
internal class DoubaoTransport(
    private val client: OkHttpClient = streamingAsrHttp,
    private val endpoint: String = DoubaoProtocol.ENDPOINT,
) {
    suspend fun connect(credentials: DoubaoCredentials, resource: String): DoubaoSocket {
        require(credentials.valid && resource in DoubaoProtocol.resources)
        val request = Request.Builder().url(endpoint)
            .header("X-Api-Resource-Id", resource)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Connect-Id", UUID.randomUUID().toString())
            .apply {
                if (credentials.apiKey.isNotBlank()) header("X-Api-Key", credentials.apiKey)
                else {
                    header("X-Api-App-Key", credentials.appId)
                    header("X-Api-Access-Key", credentials.accessKey)
                }
            }.build()
        val socket = openAsrWebSocket(
            client, request, 32,
            fromText = { throw IOException("Expected binary ASR response") },
            fromBytes = { it.toByteArray() },
            size = ByteArray::size,
        )
        return object : DoubaoSocket {
            override val incoming = socket.incoming
            override fun send(packet: ByteArray) = socket.sendBinary(packet)
            override fun close() = socket.close()
        }
    }
}

/** Live capture/upload and receive run concurrently; only the server's last packet finalizes text. */
internal class DoubaoSpeech(
    recorder: StreamingRecorder,
    connect: suspend () -> DoubaoSocket,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    connectTimeoutMs: Long = 10_000,
    finalTimeoutMs: Long = 10_000,
) : SpeechSession by StreamingAsrSession(recorder, DoubaoStream(connect), scope, connectTimeoutMs, finalTimeoutMs)

/** Doubao v3 binary protocol on the shared session: a 75 s cap on the whole session, and a flagged last audio packet. */
private class DoubaoStream(private val connector: suspend () -> DoubaoSocket) : AsrStream {
    private var socket: DoubaoSocket? = null
    private var text = ""
    override val packetSamples = DoubaoProtocol.CHUNK_SAMPLES
    override val packetGapMs = 200L
    override val sendsEmptyLast = true
    override val totalTimeoutMs = 75_000L
    override val invalidResponse = "Invalid Doubao ASR response"

    override fun serviceError(e: Exception): ModelError? =
        (e as? DoubaoProtocol.ServiceError)?.let { ModelError.BadResponse("Doubao ASR code ${it.code}") }

    override suspend fun connect() { socket = connector() }
    override fun open() = socket!!.send(DoubaoProtocol.initial())

    /** The initial acknowledgement may already carry text, or even be the last packet. */
    override suspend fun awaitStart(partial: (String, String) -> Unit): String? {
        val first = DoubaoProtocol.response(socket!!.incoming.receive())
        text = first.text.orEmpty()
        if (first.last) return text
        if (first.text != null) partial("", text)
        return null
    }

    override suspend fun awaitFinal(partial: (String, String) -> Unit): String {
        for (bytes in socket!!.incoming) {
            val response = DoubaoProtocol.response(bytes)
            response.text?.let {
                // result_type=full is a replacement snapshot, never an append delta.
                text = it
                if (!response.last) partial("", it)
            }
            if (response.last) return text
        }
        throw IOException("ASR closed before final result")
    }

    override fun sendAudio(pcm: ShortArray, count: Int, last: Boolean) =
        socket!!.send(DoubaoProtocol.audio(if (count == pcm.size) pcm else pcm.copyOf(count), last))

    override fun close() { socket?.close() }
}
