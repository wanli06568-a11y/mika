package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** 把一个工作区音频分享给一个 App,只打开分享页,不发出。 */
internal object ShareAudio : ShareFileTool("phone_share_audio", "audio", "音频", "/workspace/out/audio/line.wav") {
    override val verb = Verb.ShareAudio
    override val label = "分享音频到 App"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareAudio
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareAudio(packageName, path, text)
}
