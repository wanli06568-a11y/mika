package com.wangzan101.mika.cap.phone.shell

import android.app.AppOpsManager
import android.content.ClipData
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.util.Log
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_FAILED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_LABEL
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_SET
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.CLIP_UNREADABLE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.OPSTR_READ_CLIPBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.READ_CLIPBOARD_IN_BACKGROUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_IS_SENSITIVE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.EXTRA_SUPPRESS_CLIPBOARD_OVERLAY

// 助手服务:剪贴板读写保护与设备文件策略
/** [ShellService.setClipText] 的实现。 */
internal fun ShellService.putClipText(text: String): Int = synchronized(clipLock) {
    try {
        // 上次还原因读不到而没完成时,先再还原一次;仍读不到就不能覆盖此刻可能属于用户的新内容。
        if (clipSaved && !restoreSavedClip()) return CLIP_UNREADABLE
        if (!clipSaved) {
            if (!canReadClipboard()) return CLIP_UNREADABLE
            val prior = clipboard.primaryClip
            // 读到空不一定是空:系统可能悄悄拦下了读取,这时有无、描述还会说有内容
            when (ShellClip.read(prior != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
                ShellClip.Read.Unreadable -> {
                    Log.i(TAG, "setClipText: clip present but unreadable, not replacing")
                    return CLIP_UNREADABLE
                }
                ShellClip.Read.Empty -> Log.i(TAG, "setClipText: user clipboard was empty")
                ShellClip.Read.Has -> Log.i(TAG, "setClipText: user clipboard saved")
            }
            savedClip = prior
            clipSaved = true
        }
        val clip = ClipData.newPlainText(CLIP_LABEL, text)
        suppressOverlay(clip)
        markSensitive(clip)
        clipboard.setPrimaryClip(clip)
        ourText = text
        // Android can silently decline the write. Never paste a clipboard we didn't set.
        if (nowState(text) == ShellClip.Now.Ours) CLIP_SET else CLIP_FAILED
    } catch (t: Throwable) {
        Log.w(TAG, "setClipText failed", t)
        CLIP_FAILED
    }
}

/** [ShellService.restoreClip] 的实现:还原完([applyRestore] 为真)才清掉留底,否则下次接着试。 */
internal fun ShellService.restoreSavedClip(): Boolean = synchronized(clipLock) {
    if (!clipSaved) return true
    val prior = savedClip
    val finished = applyRestore("restoreClip", ourText, prior?.let { { it } })
    if (finished) {
        savedClip = null
        clipSaved = false
        ourText = null
    }
    finished
}

/** [ShellService.savedClipText] 的实现:留底的文字;没有待还原的为 null,原来是空的或读不出文字为空串。 */
internal fun ShellService.savedClipTextNow(): String? = synchronized(clipLock) {
    if (!clipSaved) return null
    runCatching { savedClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
}

/** [ShellService.restoreClipText] 的实现:还原成调用方记下的 [original](空串表示原来是空的)。 */
internal fun ShellService.restoreClipTextTo(original: String, ours: String): Boolean = synchronized(clipLock) {
    applyRestore("restoreClipText", ours, original.takeIf { it.isNotEmpty() }?.let { { ClipData.newPlainText("", it) } })
}

/**
 * 还原一次剪贴板:按此刻是不是 Mika 的 [ours] 和原来有没有内容([putBack] 为 null 表示原来是空的)定下放回 / 清空 / 不动([ShellClip.restore])。
 * 写入也可能被系统悄悄拒绝:做完再读一次,仍是 Mika 的或此刻读不到都算没完成,调用方下次继续尝试;
 * 用户已经复制了别的内容时保留用户的新内容,算完成。[what] 只用于日志。调用方持有 clipLock。
 */
internal fun ShellService.applyRestore(what: String, ours: String?, putBack: (() -> ClipData)?): Boolean = runCatching {
    val now = nowState(ours)
    val action = ShellClip.restore(putBack == null, now)
    Log.i(TAG, "$what: now=$now action=$action")
    when (action) {
        ShellClip.Restore.PutBack -> clipboard.setPrimaryClip(suppressOverlay(putBack!!()))
        ShellClip.Restore.Clear -> clipboard.clearPrimaryClip()
        ShellClip.Restore.Leave -> Unit
    }
    val after = nowState(ours)
    after != ShellClip.Now.Ours && after != ShellClip.Now.Unreadable
}.onFailure { Log.w(TAG, "$what failed", it) }.getOrDefault(false)

/** 剪贴板此刻是不是 Mika 放进去的 [ours];读不到(有内容但取不到)时不当成空的。 */
internal fun ShellService.nowState(ours: String?): ShellClip.Now {
    val now = clipboard.primaryClip
    return when (ShellClip.read(now != null, clipboard.hasPrimaryClip(), clipboard.primaryClipDescription != null)) {
        ShellClip.Read.Empty -> ShellClip.Now.Empty
        ShellClip.Read.Unreadable -> ShellClip.Now.Unreadable
        ShellClip.Read.Has -> {
            val ourClip = now!!.description.label == CLIP_LABEL && now.itemCount == 1 && ours != null &&
                now.getItemAt(0).text?.toString() == ours
            if (ourClip) ShellClip.Now.Ours else ShellClip.Now.Other
        }
    }
}

/** shell 要有后台读剪贴板的权限、且读剪贴板的权限操作没被关掉,才算读得到。 */
internal fun ShellService.canReadClipboard(): Boolean = runCatching {
    val granted = context.checkPermission(READ_CLIPBOARD_IN_BACKGROUND, Process.myPid(), Process.myUid()) ==
        PackageManager.PERMISSION_GRANTED
    val ops = context.getSystemService(AppOpsManager::class.java)
    granted && ops.unsafeCheckOpNoThrow(OPSTR_READ_CLIPBOARD, Process.myUid(), ShellContext.SHELL_PACKAGE) ==
        AppOpsManager.MODE_ALLOWED
}.getOrDefault(false)

internal fun ShellService.suppressOverlay(clip: ClipData): ClipData {
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_SUPPRESS_CLIPBOARD_OVERLAY, true)
    clip.description.extras = extras
    return clip
}

/**
 * Mika 粘贴的内容(可能是地址、手机号等)标成敏感:系统和输入法的剪贴板预览、历史不显示原文。
 * 只标 Mika 自己放进去的,还原用户的剪贴板时保持原样。
 */
internal fun ShellService.markSensitive(clip: ClipData): ClipData {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return clip
    val extras = clip.description.extras?.let { PersistableBundle(it) } ?: PersistableBundle()
    extras.putBoolean(EXTRA_IS_SENSITIVE, true)
    clip.description.extras = extras
    return clip
}

internal inline fun ShellService.deviceFileCall(block: (DeviceFilePolicy) -> Bundle): Bundle = asCallAudioShell {
    val snapshot = filePolicy ?: return@asCallAudioShell Bundle().apply { putString("status", "policy_unsynced") }
    val response = block(snapshot.policy)
    synchronized(filePolicyLock) {
        if (filePolicy !== snapshot) {
            response.getParcelable<ParcelFileDescriptor>("fd")?.close()
            Bundle().apply { putString("status", "policy_changed") }
        } else {
            response.getParcelable<ParcelFileDescriptor>("fd")?.let { returnedCallPipes.set(listOf(it)) }
            response
        }
    }
}
