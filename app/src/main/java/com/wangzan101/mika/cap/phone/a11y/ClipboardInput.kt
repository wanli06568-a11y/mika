package com.wangzan101.mika.cap.phone.a11y

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.PASTE_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.WEB_VIEW
import com.wangzan101.mika.cap.phone.shell.AsciiTextKeys
import com.wangzan101.mika.cap.phone.shell.ShellService

// 写字进输入框:直接 SET_TEXT,或选中原内容后经剪贴板粘贴。都是 [AndroidScreen] 上的扩展,和它同包。

/**
 * 网页里的编辑区(contenteditable)只认真实输入:SET_TEXT 只改了网页上的字,编辑器收不到 input 事件,
 * App 会当作没输入(「完成」不亮、退出不保存)。这类输入框改用选中原内容再粘贴或按删除键。
 */
internal fun AndroidScreen.inWebView(node: AccessibilityNodeInfo) = ancestorOrSelf(node) { it.className == WEB_VIEW } != null

/**
 * 选中原内容后粘贴(或按删除键清空)。返回 null 表示剪贴板不能安全使用,没有改动任何文字;
 * 其余结果用 [AsciiTextKeys] 三态表示。选择失败时不发删除或粘贴,避免只删一个字符或在光标处插入。
 */
internal fun AndroidScreen.inputText(node: AccessibilityNodeInfo, text: String): Int? {
    if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
    val before = node.text?.toString().orEmpty()
    fun selectAll(): Boolean {
        if (!node.refresh() || !visibleInputTarget(node) || node.text?.toString().orEmpty() != before) return false
        if (before.isEmpty()) return true
        val range = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before.length)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range)
    }
    if (text.isEmpty()) {
        if (before.isEmpty()) return AsciiTextKeys.DISPATCHED
        if (!selectAll()) return AsciiTextKeys.NOT_SENT
        if (!node.refresh() || !visibleInputTarget(node)) return AsciiTextKeys.NOT_SENT
        return if (shell.injectKey(displayId, KeyEvent.KEYCODE_DEL)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
    }
    return withClipboardText(text) {
        if (!selectAll()) return@withClipboardText AsciiTextKeys.NOT_SENT
        if (!node.refresh() || !visibleInputTarget(node)) return@withClipboardText AsciiTextKeys.NOT_SENT
        // 标准动作返回 false 时也可能已经产生效果;按不确定处理,不补发快捷键。
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return@withClipboardText AsciiTextKeys.UNCONFIRMED
        // App 是异步去读剪贴板的:等字变了再还原,最多等一会儿
        val deadline = SystemClock.uptimeMillis() + PASTE_WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (node.refresh() && node.text?.toString().orEmpty() != before) break
            Thread.sleep(POLL_MS)
        }
        AsciiTextKeys.DISPATCHED
    }
}

/** 持有全局剪贴板锁同步执行;设置后不论结果都尽量留底,只有确认恢复完成才清除持有标记和加密留底。 */
internal fun AndroidScreen.withClipboardText(text: String, action: () -> Int): Int? {
    clipLock.lock()
    try {
        clipHeld(true)
        val set = runCatching { shell.setClipText(text) }.getOrDefault(ShellService.CLIP_FAILED)
        runCatching { shell.savedClipText() }.getOrNull()?.let { clipSaved(it, text) }
        if (set != ShellService.CLIP_SET) return null
        return action()
    } finally {
        val restored = runCatching { shell.restoreClip() }.getOrDefault(false)
        try {
            if (restored) clipHeld(false)
        } finally {
            clipLock.unlock()
        }
    }
}

internal fun AndroidScreen.setText(node: AccessibilityNodeInfo, text: String): Boolean {
    if (!node.refresh() || !visibleInputTarget(node)) return false
    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
}
