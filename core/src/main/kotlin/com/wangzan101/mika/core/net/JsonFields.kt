package com.wangzan101.mika.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 字段是 JSON 字符串时取出来;数字、布尔、null、对象、数组都算没有。 */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 字段是任意 JSON 原始值时取它的文本(数字、布尔也算,不查是不是字符串);null、对象、数组算没有。 */
internal fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** 用这份 [Json] 配置解析成 JSON 对象;不是合法 JSON、或者顶层不是对象,都返回 null。 */
internal fun Json.jsonObjectOrNull(text: String): JsonObject? =
    try { parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
