package com.wangzan101.mika.calls.briefing

import com.wangzan101.mika.calls.CallPolicy
import com.wangzan101.mika.calls.CallTaskBrief
import com.wangzan101.mika.calls.VolcRealtimeEvent

/** Control is recognized only from a complete current owner turn, never model text. */
internal object VoiceBriefingAuthorization {
    const val PHRASE = "我说完了，你去打"
    private val zhPattern = Regex("我说完了[，,、\\s]*你去打[。.!！]?")
    // 语音识别常在两句之间打句号(I'm done. Make the call.),和中文一样只放过两句之间的空白和标点。
    private val enPattern = Regex("(?i)(?:i['’]?m|i am)?\\s*done[,.!;\\s]+make the call[.!]?")
    fun matches(event: VolcRealtimeEvent.UserTranscript): Boolean {
        if (!event.isFinal || !event.isCompleteFinal || !event.isCurrentTurn) return false
        // Ignore only ordinary whitespace and punctuation separating these two exact clauses.
        // Quotes, extra words, negation, conditional statements and quoted examples all fail closed.
        val text = event.text.trim()
        return text.matches(zhPattern) || text.matches(enPattern)
    }
}

internal class VoiceBriefingConversation(initialBrief: CallTaskBrief?) {
    private val initial = initialBrief?.validatedSnapshot()
    private val turns = mutableListOf<String>()
    private var terminal = false

    val ownerTranscript: String get() = turns.joinToString("\n")

    /** A null return means keep listening. A snapshot can be returned at most once. */
    fun accept(event: VolcRealtimeEvent.UserTranscript): CallTaskBrief? {
        if (terminal || !event.isFinal) return null
        if (!event.isCompleteFinal) throw VoiceBriefingFailure("这句话未完整识别，请重新交代")
        // The shared provider event deliberately has no turn identity/sequence. Never reorder
        // a late correction by arrival time, or silently lose it by discarding it.
        if (!event.isCurrentTurn) throw VoiceBriefingFailure("口述定稿顺序发生变化，未拨号；请重新完整交代")
        if (VoiceBriefingAuthorization.matches(event)) {
            if (event.hasUnresolvedPriorTurn) {
                throw VoiceBriefingFailure("之前的交代尚未完整识别，未拨号；请重新完整交代")
            }
            val brief = snapshot()
            terminal = true
            return brief
        }
        // The provider normally emits one final per item. Consecutive repeated finals add no
        // instruction and cannot consume the task capacity twice.
        if (event.text.isNotBlank() && event.text.trim() != turns.lastOrNull()) turns.add(event.text.trim())
        if (ownerTranscript.length > CallTaskBrief.MAX_CONTEXT_CHARS) {
            throw VoiceBriefingFailure("交代超过可完整保存的长度，请精简后重新开始；本次不会拨号")
        }
        return null
    }

    fun cancel() { terminal = true }

    private fun snapshot(): CallTaskBrief {
        if (turns.isEmpty()) return initial ?: throw VoiceBriefingFailure("还没有听到本次任务，请先交代要办的事")
        val original = initial?.let {
            buildString {
                append("原有交代：\n目标：").append(it.goal)
                if (it.questions.isNotEmpty()) append("\n必问：\n").append(it.questions.joinToString("\n"))
                if (it.context.isNotEmpty()) append("\n背景：").append(it.context)
                if (it.constraints.isNotEmpty()) append("\n限制：").append(it.constraints)
                append("\n追加口述（有冲突以主人后述为准）：\n")
            }
        }.orEmpty()
        val full = original + ownerTranscript
        if (full.length > CallTaskBrief.MAX_CONTEXT_CHARS) {
            throw VoiceBriefingFailure("交代超过可完整保存的长度，请精简后重新开始；本次不会拨号")
        }
        // Keep every owner instruction verbatim, including corrections and boundaries. Model
        // clarifications never enter the task as if they were owner-confirmed facts.
        return CallTaskBrief(goal = "依照主人完整口述交代办理本次电话任务", context = full).validatedSnapshot()
    }

    companion object {
        const val PROMPT = "你是电话助理 Mika。现在只和手机主人进行拨号前的任务交代对话，还没有拨号。" +
            "简洁询问任务目标、必要问题、背景和不能承诺的事项；一次只问一个真正缺失的问题。" +
            "主人的修改覆盖之前说法，任何你提出的建议都必须说明是建议，不把猜测说成事实。" +
            "主人可能分多次说话、停顿后继续或修改交代；停顿、没有识别到内容、你已理解任务或你自己的总结都不代表交代结束。" +
            "在主人明确授权前持续听取，不说‘交代完毕’‘已经结束’或催促主人结束，不替主人决定已经说完。" +
            "不得自称已拨号或已办妥，不执行任何电话控制。主人只有单独说出‘我说完了，你去打’才会授权，" +
            "不要替主人说这句，也不要让主人用别的确认词代替。先说：请交代这通电话要办的事，我在听。"
        const val GREETING = "请交代这通电话要办的事，我在听。"

        /** 交代对话的提示:中文界面用原中文提示;其他语言给模型英文提示,并让它用该语言说话。[greeting] 是应用播的开场白。 */
        fun promptFor(languageTag: String?, greeting: String = GREETING): String {
            if (CallPolicy.isChineseLanguage(languageTag)) return PROMPT
            val lang = java.util.Locale.forLanguageTag(languageTag!!).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "English" }
            return "You are phone assistant Mika. Right now you are only taking a pre-call briefing from the phone owner; no call has been dialed yet. " +
                "Speak concisely in $lang. Ask only one truly missing question at a time about goal, must-ask questions, context, or constraints. " +
                "Owner corrections override earlier statements. Never claim you already dialed or completed anything. " +
                "Wait until the owner says \"I'm done, make the call\" on its own to authorize dialing; never say it for them or suggest shortcuts. " +
                "Start by saying: $greeting"
        }

        /** 主人已经填好的交代,附在提示后只作本通上下文;语言同 [promptFor]。 */
        fun briefContext(brief: CallTaskBrief, languageTag: String?): String =
            if (CallPolicy.isChineseLanguage(languageTag)) {
                "\n以下是主人已经填写的交代，只作本通上下文：\n目标：${brief.goal}\n" +
                    "必问：${brief.questions.joinToString("；")}\n背景：${brief.context}\n限制：${brief.constraints}"
            } else {
                "\nThe owner has already filled in this briefing; use it only as context for this conversation:\nGoal: ${brief.goal}\n" +
                    "Must ask: ${brief.questions.joinToString("; ")}\nBackground: ${brief.context}\nConstraints: ${brief.constraints}"
            }
    }
}

internal class VoiceBriefingFailure(val safeMessage: String) : Exception(safeMessage)
