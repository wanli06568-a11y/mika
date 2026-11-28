package com.wangzan101.mika.calls

/**
 * Accepts only an unambiguous closing from a caller's or assistant's final utterance. No transcript is retained.
 *
 * 中文和其他语言分两条路径各判各的,任一条认下就算:中文路径的规则和切分与原来逐字相同(只多认繁体字形),
 * 其他语言的词表、否定和转述规则都不影响中文。
 */
internal object CallFarewellPolicy {
    // --- 中文(简体、繁体) ---
    private const val ZH_THANKS = "[谢謝]{2}(?:你|您)?(?:啊|了)?|多[谢謝]|辛苦了"
    private const val ZH_COURTESY = "好(?:的|吧|啊)?|行(?:吧|啊)?|嗯{1,3}|那(?:好吧?)?|$ZH_THANKS"
    private const val ZH_CLOSING_CONTEXT = "(?:那)?就(?:先)?[这這][样樣]吧|(?:我[这這][边邊]|我)?[没沒](?:有)?[别別]的事了|不打[扰擾]了|(?:改天|回[头頭])再聊"
    private const val ZH_GOODBYE = "(?:再[见見]|拜拜)(?:啊|啦|了)?"
    private const val ZH_SELF_HANGUP = "(?:我(?:[们們]|[这這][边邊])?)(?:就)?(?:先)?[挂掛](?:[断斷])?(?:[电電][话話])?了(?:啊|啦|吧)?"
    private const val ZH_REQUEST_HANGUP = "(?:(?:麻[烦煩]|[请請])(?:你|您)?(?:[帮幫]我)?(?:先)?(?:[挂掛][断斷](?:[电電][话話])?(?:吧|了|一下)?|[挂掛](?:一下)?[电電][话話](?:吧|了)?)|" +
        "(?:你|您)(?:可以|先)?[挂掛](?:[断斷])?(?:[电電][话話])?(?:了|吧|了吧)|" +
        "(?:可以|[请請])(?:直接)?[结結]束(?:[这這]次)?通[话話](?:了|吧)?|" +
        "(?:可以|[请請])(?:直接)?[挂掛][断斷](?:[电電][话話])?(?:了|吧)?|[挂掛](?:[断斷])?[电電][话話]吧|[挂掛][断斷]吧|可以[挂掛]了)"

    // --- Multilingual courtesy / context / farewell / hangup patterns (lowercased) ---
    private const val MULTI_THANKS =
        "thank(?:s|\\s+you)(?:\\s+(?:so|very)\\s+much|\\s+a\\s+lot)?|many\\s+thanks|" +
            "muchas\\s+gracias|gracias|merci(?:\\s+beaucoup)?|danke(?:\\s+schön|\\s+sehr)?|vielen\\s+dank|" +
            "obrigad[oa]|muito\\s+obrigad[oa]|grazie(?:\\s+mille)?|спасибо|большое\\s+спасибо|" +
            "ありがとう(?:ございま(?:す|した))?|감사합니다|고맙습니다|고마워요|" +
            "धन्यवाद|शुक्रिया|shukriya|dhanyavaad|terima\\s+kasih|teşekkür(?:ler|\\s+ederim)|" +
            "cảm\\s+ơn|ขอบคุณ(?:ครับ|ค่ะ)?|dank\\s+je|bedankt|dziękuję|شكرا(?:\\s+جزيلا)?"

    private const val MULTI_COURTESY =
        "ok(?:ay)?|alright|all\\s+right|sure|got\\s+it|sounds\\s+good|perfect|great|wonderful|" +
            "no\\s+(?:problem|worries)|you(?:'re|\\s+are)\\s+welcome|" +
            "vale|de\\s+acuerdo|está\\s+bien|d'accord|très\\s+bien|alles\\s+klar|gut|in\\s+ordnung|" +
            "tudo\\s+bem|certo|va\\s+bene|perfetto|хорошо|ладно|понятно|" +
            "はい|わかりました|了解(?:です)?|네|알겠습니다|좋아요|ठीक\\s+है|अच्छा|जी|" +
            "baik|tamam|vâng|được\\s+rồi|ครับ|ค่ะ|โอเค|goed|dobrze|حسنا|تمام|" +
            "$MULTI_THANKS"

    private const val MULTI_CLOSING_CONTEXT =
        "that(?:'s|\\s+is)\\s+(?:all|it|everything)(?:\\s+for\\s+(?:now|today))?|" +
            "(?:i\\s+)?(?:have\\s+)?nothing\\s+else|all\\s+(?:set|good|done)|" +
            "(?:i(?:'ve|\\s+have)\\s+)?got(?:ta|\\s+to)\\s+go|(?:i\\s+)?need\\s+to\\s+go|" +
            "we(?:'re|\\s+are)\\s+(?:all\\s+)?done|that\\s+will\\s+be\\s+all|" +
            "eso\\s+es\\s+todo|nada\\s+más|c'est\\s+tout|das\\s+wär(?:'s|\\s+es)|alles\\s+erledigt|" +
            "é\\s+só\\s+isso|è\\s+tutto|на\\s+этом\\s+всё|больше\\s+ничего|" +
            "以上です|それだけです|더\\s+이상\\s+없어요|बस\\s+इतना\\s+ही|और\\s+कुछ\\s+नहीं"

    private const val MULTI_GOODBYE =
        "(?:good\\s*)?bye(?:\\s+bye)?|see\\s+(?:you|ya)(?:\\s+(?:later|soon|around))?|" +
            "talk\\s+(?:to\\s+you\\s+)?(?:later|soon)|ttyl|take\\s+care|" +
            "have\\s+a\\s+(?:good|great|nice|wonderful|lovely)\\s+(?:day|evening|night|weekend|one)|" +
            "catch\\s+you\\s+later|until\\s+next\\s+time|farewell|" +
            "adiós|chao|chau|hasta\\s+(?:luego|pronto|mañana)|nos\\s+vemos|" +
            "au\\s+revoir|à\\s+(?:bientôt|plus(?:\\s+tard)?)|bonne\\s+(?:journée|soirée)|" +
            "tschüss|tschau|auf\\s+wieder(?:sehen|hören)|bis\\s+(?:bald|dann|später)|schönen\\s+(?:tag|abend)(?:\\s+noch)?|" +
            "tchau|adeus|até\\s+(?:logo|mais|breve)|tenha\\s+um\\s+bom\\s+dia|" +
            "arrivederci|a\\s+presto|buona\\s+(?:giornata|serata)|" +
            "до\\s+свидания|всего\\s+(?:доброго|хорошего)|увидимся|хорошего\\s+дня|" +
            "さようなら|さよなら|バイバイ|じゃあね|またね|お先に失礼(?:します|いたします)|失礼いたします|" +
            "안녕히\\s*(?:계세요|가세요)|바이바이|잘\\s*가|수고하세요|좋은\\s*하루\\s*되세요|" +
            "अलविदा|बाय(?:\\s+बाय)?|फिर\\s+मिलेंगे|ख़ुदा\\s+हाफ़िज़|अल्लाह\\s+हाफ़िज़|alvida|khuda\\s+hafiz|allah\\s+hafiz|phir\\s+milenge|" +
            "مع\\s+السلامة|وداعا|إلى\\s+اللقاء|باي|خداحافظ|بدرود|" +
            "sampai\\s+jumpa|dadah|selamat\\s+tinggal|" +
            "hoşça\\s+kal(?:ın)?|görüşürüz|güle\\s+güle|" +
            "tạm\\s+biệt|chào\\s+tạm\\s+biệt|hẹn\\s+gặp\\s+lại|" +
            "ลาก่อน|บ๊ายบาย|tot\\s+ziens|doei|do\\s+widzenia"

    // 挂断只认自述(我要挂了)或请对方挂(你可以挂了);光一个动词「colgar」「hang up」可能是在问、在转述,不算
    private const val MULTI_HANGUP =
        "(?:i(?:'m|\\s+am)\\s+)?hanging\\s+up(?:\\s+now)?|" +
            "(?:i(?:'ll|\\s+will)|i(?:'m|\\s+am)\\s+going\\s+to|i(?:'m|\\s+am)\\s+gonna|let\\s+me|you\\s+can|you\\s+may|please|go\\s+ahead\\s+and)\\s+" +
            "(?:hang\\s+up|end\\s+(?:the|this)\\s+call)(?:\\s+(?:now|please))?|" +
            "(?:puedes|puede|ya\\s+puedes|ya\\s+puede)\\s+colgar|voy\\s+a\\s+colgar|te\\s+cuelgo|le\\s+cuelgo|ya\\s+cuelgo|" +
            "(?:tu\\s+peux|vous\\s+pouvez)\\s+raccrocher|je\\s+raccroche|" +
            "(?:du\\s+kannst|sie\\s+können)\\s+auflegen|ich\\s+lege\\s+auf|" +
            "(?:pode|podes|já\\s+pode)\\s+desligar|vou\\s+desligar|" +
            "(?:puoi|può)\\s+riattaccare|riattacco|" +
            "(?:можешь\\s+|можете\\s+)?положить\\s+трубку|клади\\s+трубку|завершай\\s+звонок|" +
            "(?:電話を)?切(?:ります|っていい(?:です)?)|" +
            "(?:전화\\s*)?끊(?:을게요|어도\\s*돼요|어)|" +
            "(?:फ़ोन|कॉल)\\s*(?:रखो|रख\\s+दो|काटो|काट\\s+दो)|(?:phone|call)\\s+(?:rakho|kaat\\s+do)|" +
            "أغلق\\s+المكالمة|يمكنك\\s+إنهاء\\s+المكالمة|" +
            "tutup\\s+telepon|telefonu\\s+kapat|cúp\\s+máy|วางสายได้เลย|(?:je\\s+kunt|u\\s+kunt)\\s+ophangen|ik\\s+hang\\s+op|rozłącz\\s+się"

    private val spaces = Regex("[ \\t\\r\\n　]+")

    private val zhSplitClause = Regex("[,，、。.!！；;]+")
    private val zhClosing = Regex("(?:$ZH_COURTESY){0,4}(?:(?:$ZH_GOODBYE){1,2}|$ZH_SELF_HANGUP|$ZH_REQUEST_HANGUP)(?:$ZH_THANKS)?")
    private val zhBefore = Regex("(?:$ZH_COURTESY)|(?:$ZH_CLOSING_CONTEXT)")
    private val zhAfter = Regex(ZH_THANKS)
    private val zhQuoteOrQuestion = "?？\"'“”‘’「」『』《》〈〉＂＇"
    private val zhReportedSpeech = Regex(
        "例如|比如|举例|示例|假如|假设|假设说|引用|原话|(?:的|这)句(?:话)?|" +
            "(?:可以|可|建议|不妨|就|应该|请|要|试着|用来)(?:先|直接|跟\\S{1,8}|对\\S{1,8}|这么|这样)?(?:说|回答|回复|念)|" +
            "(?:他说|她说|对方说|他当时说|她当时说|转述|复述)[：:]?",
    )
    private val zhSettledEnding = Regex(
        "(?:那|好|行)?(?:我[们們])?(?:就(?:先)?[这這][样樣]|先[这這][样樣]|(?:就|先)(?:到[这這][里裡]|聊到[这這][里裡]|[说說]到[这這][里裡]))(?:吧|了)?|" +
            "(?:那)?(?:我)?(?:就)?(?:先)?不打[扰擾](?:您|你)?了|祝您(?:[顺順]利|生活愉快)",
    )

    // --- 其他语言:按小写、保留单词间空格匹配 ---
    private val multiSplitClause = Regex("[,，、。.!！；;।،·~～…]+")
    private val boundaryQuote = Regex("(?:^|[\\s:：,，(（])['’]|['’](?:$|[\\s,，.。!！)）])")
    private val alwaysQuoteOrQuestion = "?？¿؟\"“”「」『』《》〈〉＂"

    private val multiClosing = Regex(
        "(?:(?:$MULTI_COURTESY)\\s+){0,3}(?:(?:$MULTI_GOODBYE)(?:\\s+(?:$MULTI_GOODBYE))?|(?:$MULTI_HANGUP))(?:\\s+(?:$MULTI_THANKS))?",
    )
    private val multiBefore = Regex("(?:$MULTI_COURTESY)|(?:$MULTI_CLOSING_CONTEXT)")
    private val multiAfter = Regex("(?:$MULTI_THANKS)|(?:$MULTI_GOODBYE)")

    private val negationOrHold = Regex(
        "\\b(?:don't\\s+hang|do\\s+not\\s+hang|don't\\s+cut|do\\s+not\\s+cut|not\\s+hanging\\s+up|" +
            "not\\s+saying\\s+goodbye|didn't\\s+say\\s+goodbye|wait|hold\\s+on|one\\s+more\\s+(?:thing|question)|" +
            "another\\s+question|before\\s+(?:you|we)\\s+go|just\\s+kidding|joking|" +
            "for\\s+example|such\\s+as|write\\s+down|repeat\\s+after)\\b|" +
            "\\bno,?\\s*(?:wait|hold|don't|stop)\\b|" +
            "no\\s+cuelgues|espera|un\\s+momento|ne\\s+raccroche\\s+pas|attends|attendez|" +
            "nicht\\s+auflegen|warte|warten|не\\s+клади|подожди|подождите|" +
            "待って|切らないで|잠깐|끊지\\s*마|मत\\s+काटो|रुको|रुकिए|لا\\s+تغلق|انتظر",
        RegexOption.IGNORE_CASE,
    )

    private val reportedSpeech = Regex(
        "\\b(?:he|she|they)\\s+said\\b|\\bfor\\s+(?:example|instance)\\b|\\bremember\\s+the\\s+word\\b",
        RegexOption.IGNORE_CASE,
    )

    private val settledEnding = Regex(
        "(?:let(?:'s|\\s+us)|we(?:'ll|\\s+will))\\s+wrap\\s+up(?:\\s+here)?|" +
            "our\\s+time\\s+is\\s+(?:almost\\s+)?up|" +
            "(?:have\\s+a\\s+)?(?:good|great|nice|wonderful)\\s+(?:day|evening|night|weekend)|" +
            "wish\\s+you\\s+(?:all\\s+)?the\\s+best|take\\s+care|" +
            "goodbye|(?:good\\s*)?bye(?:\\s+bye)?|" +
            "adiós|au\\s+revoir|auf\\s+wieder(?:sehen|hören)|tschüss|tchau|arrivederci|до\\s+свидания|" +
            "さようなら|(?:このあたりで|ここで|これで|それでは|では)?(?:お先に)?失礼いたします|(?:このあたりで|ここで|これで)失礼します|안녕히\\s*(?:계세요|가세요)|अलविदा|مع\\s+السلامة|خداحافظ|sampai\\s+jumpa|hoşça\\s+kalın|tạm\\s+biệt|ลาก่อน|tot\\s+ziens|do\\s+widzenia",
        RegexOption.IGNORE_CASE,
    )

    /** A natural closing sentence, never a lone acknowledgement, a question or quoted words. */
    fun isAssistantClosing(text: String): Boolean = zhAssistantClosing(text) || multiAssistantClosing(text)

    fun isExplicitCallerFarewell(text: String): Boolean = zhCallerFarewell(text) || multiCallerFarewell(text)

    private fun zhAssistantClosing(text: String): Boolean {
        if (zhCallerFarewell(text)) return true
        if (text.length !in 1..180 || text.any { it in zhQuoteOrQuestion }) return false
        val normalized = text.replace(spaces, "")
        if (zhReportedSpeech.containsMatchIn(normalized)) return false
        val ending = normalized.split(zhSplitClause).lastOrNull(String::isNotEmpty) ?: return false
        return zhSettledEnding.matches(ending)
    }

    private fun zhCallerFarewell(text: String): Boolean {
        if (text.length !in 1..100 || text.any { it in zhQuoteOrQuestion }) return false
        val clauses = text.split(zhSplitClause).map { it.replace(spaces, "") }.filter(String::isNotEmpty)
        if (clauses.size !in 1..8) return false

        var foundClosing = false
        for (clause in clauses) {
            when {
                zhClosing.matches(clause) -> foundClosing = true
                !foundClosing && zhBefore.matches(clause) -> Unit
                foundClosing && zhAfter.matches(clause) -> Unit
                else -> return false
            }
        }
        return foundClosing
    }

    private fun hasDisqualifyingQuoteOrQuestion(text: String): Boolean =
        text.any { it in alwaysQuoteOrQuestion } || boundaryQuote.containsMatchIn(text)

    private fun multiClauses(text: String): List<String> =
        text.split(multiSplitClause).map { it.replace(spaces, " ").trim().lowercase() }.filter(String::isNotEmpty)

    private fun multiAssistantClosing(text: String): Boolean {
        if (multiCallerFarewell(text)) return true
        if (text.length !in 1..220 || hasDisqualifyingQuoteOrQuestion(text)) return false
        if (negationOrHold.containsMatchIn(text) || reportedSpeech.containsMatchIn(text)) return false
        val ending = multiClauses(text).lastOrNull() ?: return false
        return settledEnding.matches(ending) || multiClosing.matches(ending)
    }

    private fun multiCallerFarewell(text: String): Boolean {
        if (text.length !in 1..140 || hasDisqualifyingQuoteOrQuestion(text)) return false
        if (negationOrHold.containsMatchIn(text) || reportedSpeech.containsMatchIn(text)) return false
        val clauses = multiClauses(text)
        if (clauses.size !in 1..8) return false

        var foundClosing = false
        for (clause in clauses) {
            when {
                multiClosing.matches(clause) -> foundClosing = true
                !foundClosing && multiBefore.matches(clause) -> Unit
                foundClosing && multiAfter.matches(clause) -> Unit
                else -> return false
            }
        }
        return foundClosing
    }
}
