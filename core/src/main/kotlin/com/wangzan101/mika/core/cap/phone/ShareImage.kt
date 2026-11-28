package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.tool.ToolContext
import com.wangzan101.mika.core.tool.Verb

/** 把工作区里的一张图片分享给一个 App,只打开分享页,不发出。 */
internal object ShareImage : ShareFileTool("phone_share_image", "image", "图片",
    "/workspace/out/images/cat.png or /workspace/inbox/photo.jpg") {
    override val verb = Verb.ShareImage
    override val label = "分享图片到 App"
    override fun available(ctx: ToolContext) = ctx.phone.screen.canShareImage
    override suspend fun share(ctx: ToolContext, packageName: String, path: String, text: String?) =
        ctx.phone.screen.shareImage(packageName, path, text)
}
