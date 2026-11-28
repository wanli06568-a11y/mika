package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.prompt.Prompts
import com.wangzan101.mika.core.screen.observation.ObsKind
import com.wangzan101.mika.core.screen.observation.Observation
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.action
import com.wangzan101.mika.core.tool.ArgError
import com.wangzan101.mika.core.tool.Args
import com.wangzan101.mika.core.tool.EvidenceLog
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.tool.Risk
import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.ToolDef
import com.wangzan101.mika.core.tool.ToolExecution
import com.wangzan101.mika.core.tool.str
import com.wangzan101.mika.core.workspace.SandboxPaths
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把工作区里的一个文件分享给一个 App(ACTION_SEND + EXTRA_STREAM),在 Mika 的屏上打开它的分享页:
 * 只打开,不发出;收件人和「发送」由模型在那一页上用屏幕工具完成,发送仍走现有的确认关卡。
 * 音频、图片、视频三个工具只差文件种类、文案和屏上的那一个调用。
 *
 * @param kind 英文种类词(audio / image / video),拼进参数说明和报错
 * @param kindZh 中文种类词,拼进观察文本
 * @param pathExample 参数 path 说明里的示例路径
 */
internal abstract class ShareFileTool(
    toolName: String,
    private val kind: String,
    private val kindZh: String,
    pathExample: String,
) : PhoneTool {
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef(toolName, Prompts.tool(toolName),
        mapOf(
            "package" to str("Exact package name of the app to share the $kind to, e.g. com.example.app."),
            "path" to str("Workspace $kind path, e.g. $pathExample."),
            "text" to str("Optional caption to share with the $kind, at most 2000 characters."),
        ),
        listOf("package", "path"))

    /** 屏上真正打开分享页的那一步。 */
    protected abstract suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?): ActionResult

    override fun offered(ctx: ToolContext) = available(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val path = a.str("path", max = 500)!!.trim()
        val prefix = SandboxPaths.WORKSPACE + "/"
        if (!path.startsWith(prefix) || path.removePrefix(prefix).split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw ArgError("path", "must be a workspace $kind path under $prefix")
        }
        val text = a.str("text", max = 2000)
        // 换了 App,之前被拒的付款申报就不是这次的了
        ctx.refusedPay = null
        val r = ctx.action(share(ctx, p, path, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return r
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext): String? =
        (o["package"] as? JsonPrimitive)?.content

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((o["package"] as? JsonPrimitive)?.content?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的${kindZh}分享页(还没发出)", source = PHONE)
        })
    }
}
