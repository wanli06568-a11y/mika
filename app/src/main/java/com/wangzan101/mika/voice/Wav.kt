package com.wangzan101.mika.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 位单声道 PCM 和标准 WAV(44 字节头 + 小端数据)互转:上传给服务商的转文字接口,或读回自带的录音。 */
object Wav {
    const val RATE = 16_000
    private const val HEADER = 44 // 标准头长度,写法见 [Pcm16.wav]

    fun encode(pcm16: ShortArray, rate: Int = RATE): ByteArray = Pcm16.wav(Pcm16.toBytes(pcm16), rate)

    /**
     * 取出 16 位单声道 WAV 里的 PCM 采样(小端)。按块找 `data`,前面有别的块(LIST 等)也能读;
     * 找不到 `data` 块时按标准 44 字节头之后的数据处理。不做重采样、不混音,格式要和 [encode] 一致。
     */
    fun decode(wav: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        val end = wav.size.toLong()
        var start = HEADER.toLong()
        var stop = end
        // 偏移按 Long 算:块长度是无符号 32 位,坏文件里的大长度不能溢出成负数或越界
        var off = 12L
        while (off + 8 <= end) {
            val id = String(wav, off.toInt(), 4, Charsets.US_ASCII)
            val len = buf.getInt(off.toInt() + 4).toLong() and 0xFFFF_FFFFL
            if (id == "data") {
                start = off + 8
                // 长度超出文件(录音时没写回长度、或文件被截断)按读到文件末尾处理
                stop = if (start + len > end) end else start + len
                break
            }
            off += 8 + len + (len and 1)
        }
        if (start >= stop) return ShortArray(0)
        return Pcm16.toSamples(wav.copyOfRange(start.toInt(), stop.toInt()))
    }

    /** 一段全零的静音 WAV,用来试探某个模型能不能转文字(不需要真录音)。 */
    fun silence(ms: Int, rate: Int = RATE): ByteArray = encode(ShortArray(rate * ms / 1000), rate)
}
