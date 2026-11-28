package com.wangzan101.mika.media

import android.graphics.Bitmap
import java.nio.ByteBuffer

/**
 * 把 ImageReader 的一帧(RGBA_8888)拷进按行跨度(rowStride / pixelStride)分配的位图 [target]。
 * 最后一行可能不带对齐填充,缓冲比整块位图短:这时先垫进一块等长的缓冲再拷,不让 copyPixelsFromBuffer 抛异常。
 * [scratch] 是上次垫用的缓冲;返回这次用的(可能是新的),调用方存下来下一帧传回。
 */
fun copyStridedFrame(target: Bitmap, src: ByteBuffer, scratch: ByteBuffer?): ByteBuffer? {
    src.rewind()
    val needed = target.byteCount
    if (src.remaining() >= needed) {
        target.copyPixelsFromBuffer(src)
        return scratch
    }
    val s = scratch?.takeIf { it.capacity() == needed } ?: ByteBuffer.allocateDirect(needed)
    s.clear()
    s.put(src)
    s.rewind()
    target.copyPixelsFromBuffer(s)
    return s
}
