package com.wangzan101.mika.calls

import com.wangzan101.mika.i18n.ResFileTexts
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallFarewellPolicyTest {
    @Test fun recognizesCompleteDirectClosingsInNaturalSpeech() {
        listOf(
            "再见。", "拜拜！", "拜拜再见", "好谢谢拜拜", "谢谢您，再见！", "再见，谢谢！",
            "好的，那就先这样吧，再见。", "我这边没别的事了，回头再聊，拜拜。",
            "那我就先挂了啊", "我这边先挂电话了。", "你挂吧！", "可以挂了。",
            "麻烦您帮我挂断电话吧。", "麻烦挂一下电话。", "请挂断。", "可以结束这次通话了。",
        ).forEach { assertTrue(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }

    @Test fun ordinaryAcknowledgementsAndOpenEndedClosuresAreNotHangupConsent() {
        listOf("", "。", "好", "好的，谢谢。", "没问题", "我没别的事了。", "那就先这样吧。",
            "回头再聊", "拜", "挂了", "还行，辛苦了", "通话结束").forEach {
            assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it))
        }
    }

    @Test fun questionsNegationsAndQuotesDoNotTriggerHangup() {
        listOf(
            "我说再见了吗？", "现在可以挂断电话了吗", "可以挂了？", "你挂吧?", "可以挂了吧？",
            "不要挂断电话。", "先别挂。", "我还没说再见", "不是叫你挂断电话",
            "‘再见’", "他说再见", "我让他挂断电话", "他说：‘你挂吧’", "请把再见这个词记下来",
            "请问可以先挂了再打回来吗？再见", "没事，不用挂，再见",
        ).forEach { assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }

    @Test fun aFarewellWithMoreBusinessStillLeavesTheCallOpen() {
        listOf(
            "再见，等一下我还有个问题", "我先挂了吧，不过再确认一遍", "好，改成三点，再见",
            "拜拜，顺便帮我问下时间", "刚才说再见是开玩笑", "那就这样吧，再见。别挂！",
            "再见".repeat(60),
        ).forEach { assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }
    }

    @Test fun recognizesMultilingualClosingsAndRejectsNegationsOrQuestions() {
        listOf(
            "Goodbye!", "Bye, thank you!", "Okay, thanks, bye bye.", "I'm hanging up now, bye!",
            "You can hang up now, thank you.", "That's all, see you later!",
            "Gracias, adiós.", "Au revoir, merci!", "Vielen Dank, auf Wiedersehen!",
            "Obrigado, tchau!", "Grazie, arrivederci!", "До свидания, спасибо!",
            "ありがとうございました。失礼いたします。", "감사합니다. 안녕히 계세요.",
            "धन्यवाद, अलविदा।", "شكرا، مع السلامة", "Terima kasih, sampai jumpa!",
            "Teşekkürler, hoşça kalın!", "Cảm ơn, tạm biệt!", "ขอบคุณครับ ลาก่อน",
        ).forEach { assertTrue(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }

        listOf(
            "Goodbye?", "Can I hang up?", "Don't hang up!", "Wait, hold on, bye!",
            "Bye, one more question", "Okay, thank you.", "Sounds good.",
            "Ne raccroche pas!", "Nicht auflegen, bitte!", "रुको, फ़ोन मत काटो",
            "He said goodbye.", "\"Goodbye\"", "'Goodbye'",
        ).forEach { assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it)) }

        assertTrue(CallFarewellPolicy.isAssistantClosing("Our time is almost up, let's wrap up here. Goodbye."))
        assertTrue(CallFarewellPolicy.isAssistantClosing("Have a great day!"))
        assertTrue(CallFarewellPolicy.isAssistantClosing("時間差不多了，那我們先這樣吧"))
        assertTrue(CallFarewellPolicy.isAssistantClosing("زمان ما تقریباً به پایان رسیده، همین‌جا تمام می‌کنیم. خداحافظ."))
        assertTrue(CallFarewellPolicy.isAssistantClosing("Bye~"))
        assertFalse(CallFarewellPolicy.isAssistantClosing("Could you say goodbye?"))
        assertFalse(CallFarewellPolicy.isExplicitCallerFarewell("x".repeat(5000)))
    }

    @Test fun everyLocalesTimeLimitClosingLineIsRecognized() {
        val folders = File("src/main/res").listFiles { f -> f.isDirectory && File(f, "strings_calls.xml").isFile }!!
            .map { it.name }.sorted()
        assertEquals(13, folders.size)
        folders.forEach { folder ->
            val line = ResFileTexts(folder, Locale.ROOT).strings.getValue("calls_closing_line")
            assertTrue("$folder: $line", CallFarewellPolicy.isAssistantClosing(line))
        }
        assertTrue(CallFarewellPolicy.isAssistantClosing("时间差不多了，那我们先这样吧"))
    }

    @Test fun ambiguousForeignWordsAloneAreNotCallerFarewells() {
        // 打招呼也说的(buen día、ciao、пока、失礼します),光一个挂断动词(可能在问或转述),都不当成要挂
        listOf("Buen día", "Ciao!", "はい、失礼します", "Colgar", "Desligar", "Hang up", "Пока", "End the call").forEach {
            assertFalse(it, CallFarewellPolicy.isExplicitCallerFarewell(it))
        }
        listOf("I'll hang up now, bye.", "You can hang up now.", "Vale, voy a colgar.", "Obrigado, vou desligar.",
            "Puedes colgar.", "ありがとうございました。お先に失礼します。").forEach {
            assertTrue(it, CallFarewellPolicy.isExplicitCallerFarewell(it))
        }
    }
}
