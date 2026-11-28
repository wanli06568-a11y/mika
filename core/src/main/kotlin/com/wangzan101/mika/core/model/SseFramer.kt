package com.wangzan101.mika.core.model

/**
 * SSE 分帧:一行行喂进来,攒下 `data:` 行,遇到空行拼成一件事件交出去;其他字段(event:、id:、注释)不看。
 * 流式读和「整份回复其实是 SSE」两处共用。
 */
internal class SseFramer {
    private val data = mutableListOf<String>()

    /** 喂一行;这一行结束了一件事件时返回它,否则返回 null。 */
    fun feed(line: String): String? = when {
        line.isEmpty() -> flush()
        line.startsWith("data:") -> { data += line.removePrefix("data:").removePrefix(" "); null }
        else -> null
    }

    /** 交出还没遇到空行的那件事件(流结束时调用);没有就返回 null。 */
    fun flush(): String? {
        if (data.isEmpty()) return null
        val event = data.joinToString("\n")
        data.clear()
        return event
    }
}
