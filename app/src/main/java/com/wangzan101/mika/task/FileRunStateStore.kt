package com.wangzan101.mika.task

import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.RunPhase
import com.wangzan101.mika.core.run.RunStore
import com.wangzan101.mika.core.run.StoredRun
import com.wangzan101.mika.core.workspace.AtomicFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import android.util.Log

/** App 私有目录里的运行账本；每次转阶段后原子替换，异常重启不会把半份 JSON 当成记录。 */
class FileRunStateStore(private val file: File) : RunStore {
    private val records = linkedMapOf<RunId, StoredRun>()

    init {
        if (file.isFile) runCatching {
            Json.parseToJsonElement(file.readText()).jsonArray.forEach { item ->
                val o = item.jsonObject
                val id = RunId(o.getValue("id").jsonPrimitive.content)
                records[id] = StoredRun(
                    id, o.getValue("threadId").jsonPrimitive.content, o.getValue("goal").jsonPrimitive.content,
                    phase(o.getValue("phase").jsonPrimitive.content),
                    o["reply"]?.jsonPrimitive?.contentOrNull, o["deliveryId"]?.jsonPrimitive?.contentOrNull,
                    o["roundInProgress"]?.jsonPrimitive?.content == "true",
                    o["triggerWindowKey"]?.jsonPrimitive?.contentOrNull,
                    o["runSucceeded"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                    channelPeer(o),
                    o["channelMessageId"]?.jsonPrimitive?.contentOrNull,
                    o["userText"]?.jsonPrimitive?.contentOrNull,
                    o["followUpId"]?.jsonPrimitive?.contentOrNull,
                    o["recordId"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }.onFailure {
            Log.e("MikaRunStore", "run store could not be read; preserving damaged file", it)
            AtomicFiles.quarantine(file, AtomicFiles.Quarantine.CopyCorrupt)
            records.clear()
        }
    }

    @Synchronized override fun save(record: StoredRun) {
        // 先写盘再改内存(和送达账本一样):写失败时内存仍和盘上一致
        val next = LinkedHashMap(records).apply { put(record.id, record) }
        file.parentFile?.mkdirs()
        val json = JsonArray(next.values.map { r -> buildJsonObject {
            put("id", r.id.value); put("threadId", r.threadId); put("goal", r.goal)
            put("phase", code(r.phase)); put("reply", r.reply)
            put("deliveryId", r.deliveryId)
            put("roundInProgress", r.roundInProgress)
            put("triggerWindowKey", r.triggerWindowKey)
            put("runSucceeded", r.runSucceeded)
            r.channelPeer?.let { peer -> put("channelPeer", buildJsonObject {
                put("channelId", peer.channelId); put("peerId", peer.peerId); put("threadId", peer.threadId)
            }) }
            put("channelMessageId", r.channelMessageId)
            put("userText", r.userText)
            put("followUpId", r.followUpId)
            put("recordId", r.recordId)
        } }).toString()
        check(AtomicFiles.write(file, json, durable = true, move = AtomicFiles.Move.Rename)) { "run store rename failed" }
        records[record.id] = record
    }

    @Synchronized override fun get(id: RunId): StoredRun? = records[id]
    @Synchronized override fun all(): List<StoredRun> = records.values.toList()

    private fun channelPeer(o: JsonObject): ChannelPeer? {
        val peer = o["channelPeer"] as? JsonObject ?: return null
        return runCatching {
            ChannelPeer(peer.getValue("channelId").jsonPrimitive.content,
                peer.getValue("peerId").jsonPrimitive.content, peer.getValue("threadId").jsonPrimitive.content)
        }.getOrNull()
    }

    private fun code(phase: RunPhase): String = when (phase) {
        RunPhase.Queued -> "queued"
        RunPhase.Preparing -> "preparing"
        RunPhase.Running -> "running"
        is RunPhase.WaitingUser -> "waiting_user"
        is RunPhase.Parked -> "parked"
        is RunPhase.Paused -> "paused"
        RunPhase.Finishing -> "finishing"
        RunPhase.Aftercare -> "aftercare"
        RunPhase.Ended -> "ended"
        RunPhase.Interrupted -> "interrupted"
    }

    private fun phase(code: String): RunPhase = when (code) {
        "queued" -> RunPhase.Queued
        "preparing" -> RunPhase.Preparing
        "running" -> RunPhase.Running
        "waiting_user" -> RunPhase.WaitingUser()
        "parked" -> RunPhase.Parked("")
        "paused" -> RunPhase.Paused("")
        "finishing" -> RunPhase.Finishing
        "aftercare" -> RunPhase.Aftercare
        "ended" -> RunPhase.Ended
        else -> RunPhase.Interrupted
    }
}
