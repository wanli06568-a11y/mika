package com.wangzan101.mika.settings

import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * 询问 / 自主(新任务开工时取它),存成一个小 JSON 文件;读不懂按询问。
 * 先写临时文件再改名,写一半断电不会留下坏文件。
 */
class ApprovalStore(private val file: File) {
    private val _autonomous: MutableStateFlow<Boolean>

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _autonomous = MutableStateFlow((o?.get("autonomous") as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** 自主模式:打开时连付款也不先问你(询问时只有付款先问)。换它请走 [ApprovalControl.setAutonomous],正在跑的任务才会跟着换。 */
    val autonomous: StateFlow<Boolean> = _autonomous.asStateFlow()

    @Synchronized fun setAutonomous(on: Boolean) {
        _autonomous.value = on
        save()
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        AtomicFiles.write(file, buildJsonObject { put("autonomous", _autonomous.value) }.toString(), durable = false)
    }
}
