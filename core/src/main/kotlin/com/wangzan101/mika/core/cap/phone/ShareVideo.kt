package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** 把工作区视频分享给一个 App,只打开分享页,不发出。 */
internal object ShareVideo : ShareFileTool("phone_share_video", "video", "视频", "/workspace/out/video/clip.mp4") {
    override val verb = Verb.ShareVideo
    override val label = "分享视频到 App"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareVideo
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareVideo(packageName, path, text)
}
