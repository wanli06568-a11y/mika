package com.wangzan101.mika.mcp

import com.wangzan101.mika.core.cap.mcp.McpPackageSpec
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * stdio 包下载确认:同意绑定「包名 + 精确版本 + 来源」(数据类相等,含生态),任何一项变了都要重新确认。
 * 确认是持久的用户决定:传入 [file] 时写盘,重装 App 后仍有效;不传则进程内暂存(测试用)。
 */
class McpPackageConsent(private val file: File? = null) {
    private val accepted = mutableSetOf<McpPackageSpec>()
    private val json = Json { ignoreUnknownKeys = true }

    init {
        file?.let { load(it) }
    }

    fun accepted(spec: McpPackageSpec): Boolean = spec in accepted

    fun accept(spec: McpPackageSpec) {
        accepted += spec
        file?.let { save(it) }
    }

    fun clear() {
        accepted.clear()
        file?.let { save(it) }
    }

    private fun load(f: File) {
        if (!f.isFile) return
        runCatching { json.decodeFromString<List<McpPackageSpec>>(f.readText()) }.getOrNull()
            ?.let { accepted += it }
    }

    private fun save(f: File) {
        f.parentFile?.mkdirs()
        AtomicFiles.write(f, json.encodeToString(accepted.toList()), durable = false)
    }
}
