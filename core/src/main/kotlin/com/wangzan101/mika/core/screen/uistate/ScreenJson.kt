package com.wangzan101.mika.core.screen.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ScreenJson {
    /** observe 的元素数组;center 换成与 tap 坐标相同的 0–999(像素只在端口内部用);bounds 换成 size=[宽,高](同一网格,省两个冗余角坐标);返回数组与被截掉的元素数。 */
    fun elements(s: ScreenSnapshot, maxElements: Int): Pair<JsonArray, Int> {
        val all = s.elements.filter { it.meaningful }
        val arr = buildJsonArray {
            for (e in all.take(maxElements)) add(buildJsonObject {
                put("ref", e.ref)
                put("role", e.role)
                put("name", e.name)
                put("center", buildJsonObject { put("x", normalize(e.centerX, s.width)); put("y", normalize(e.centerY, s.height)) })
                if (e.bounds.size == 4) put("size", JsonArray(listOf(JsonPrimitive(e.bounds[2] - e.bounds[0]), JsonPrimitive(e.bounds[3] - e.bounds[1]))))
                e.resourceId?.let { put("resourceId", it) }
                if (e.hint) put("hint", true)
            })
        }
        return arr to (all.size - arr.size).coerceAtLeast(0)
    }
}
