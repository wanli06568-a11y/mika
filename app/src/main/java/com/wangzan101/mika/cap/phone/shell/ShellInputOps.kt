package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.INJECT_MODE_WAIT_FOR_FINISH
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.STROKE_MAX_MS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.STROKE_MAX_POINTS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAP_MS
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.VIRTUAL_KEYBOARD
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.injectMode
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sendPair
import com.wangzan101.mika.core.screen.uistate.PressKeys

// 助手服务:向指定屏幕注入按键与触摸
internal inline fun ShellService.injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean =
    sendPair({ inject(down, displayId) }, { inject(up, displayId) }, fallback)

/** 同 [injectPair],但定向投给 [targetUid] 拥有的焦点窗口,不经过全局输入焦点。 */
internal inline fun ShellService.injectPairTo(down: InputEvent, up: InputEvent, displayId: Int, targetUid: Int, fallback: () -> Boolean): Boolean =
    sendPair({ injectTo(down, displayId, targetUid) }, { injectTo(up, displayId, targetUid) }, fallback)

@SuppressLint("PrivateApi")
internal fun ShellService.inject(event: InputEvent, displayId: Int, waitForFinish: Boolean = false): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
        .invoke(manager, event, injectMode(event is KeyEvent, waitForFinish)) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "injectInputEvent failed", t)
    false
}

/**
 * 只投给 [targetUid] 拥有的焦点窗口,等处理完再返回;焦点不在它那里时系统拒绝,事件不会落到别的窗口。
 * 用安卓 13 起的隐藏重载 `injectInputEvent(event, mode, targetUid)`,没有这个重载时不注入。
 * 失败只记异常类型:被拒的原因里不含按键,但这条路径输入的是锁屏凭据,不留多余信息。
 */
@SuppressLint("PrivateApi")
internal fun ShellService.injectTo(event: InputEvent, displayId: Int, targetUid: Int): Boolean = try {
    event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
    val manager = inputManager()
    manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java, Int::class.java)
        .invoke(manager, event, INJECT_MODE_WAIT_FOR_FINISH, targetUid) as Boolean
} catch (t: Throwable) {
    Log.w(TAG, "targeted injectInputEvent failed: ${(t.cause ?: t).javaClass.simpleName}")
    false
}

/** API 34 起注入入口在 InputManagerGlobal,更早在 InputManager。 */
@SuppressLint("PrivateApi")
internal fun ShellService.inputManager(): Any = runCatching {
    Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
}.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)!!

internal fun ShellService.sendKey(displayId: Int, keyCode: Int): Boolean {
    // 与 press_key 同一张白名单:即便调用方出错,也不从这里发出音量、电源、Home 之类的整机按键
    if (!PressKeys.isAllowed(keyCode)) {
        Log.w(TAG, "refused key $keyCode")
        return false
    }
    val now = SystemClock.uptimeMillis()
    val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
    val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
    // 按键由系统按输入焦点路由,不认 event 上的 displayId:不定向的话会投到主屏焦点,虚拟屏上的 App 收不到返回键。
    // 解析到虚拟屏顶部任务的 uid 时定向投给它;定向没投出去再退回非定向注入(和旧路径一致),最后才走 shell 命令。
    // 逐级实际走的通道记进日志,方便在真机上定位 back 注入失效(#622)。
    val uid = topTaskUid(displayId)
    var channel = if (uid != null) "targeted" else "nontargeted"
    val ok = if (uid != null) {
        injectPairTo(down, up, displayId, uid) {
            channel = "nontargeted"
            injectPair(down, up, displayId) {
                channel = "shell"
                exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
            }
        }
    } else {
        injectPair(down, up, displayId) {
            channel = "shell"
            exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0
        }
    }
    if (keyCode == KeyEvent.KEYCODE_BACK) {
        Log.i(TAG, "injectKey BACK display=$displayId uid=$uid channel=$channel ok=$ok")
    }
    return ok
}

internal fun ShellService.sendCtrlKey(displayId: Int, keyCode: Int): Int {
    if (!ownDisplay(displayId)) return AsciiTextKeys.NOT_SENT
    if (keyCode != KeyEvent.KEYCODE_A && keyCode != KeyEvent.KEYCODE_V) {
        Log.w(TAG, "refused ctrl key $keyCode")
        return AsciiTextKeys.NOT_SENT
    }
    val now = SystemClock.uptimeMillis()
    val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
    fun event(code: Int, action: Int, modifiers: Int) = KeyEvent(
        now, SystemClock.uptimeMillis(), action, code, 0, modifiers,
        VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
    )
    var modifierDown = false
    try {
        if (!inject(event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, 0), displayId)) return AsciiTextKeys.NOT_SENT
        modifierDown = true
        if (!inject(event(keyCode, KeyEvent.ACTION_DOWN, meta), displayId)) return AsciiTextKeys.NOT_SENT
        val up = event(keyCode, KeyEvent.ACTION_UP, meta)
        return if (inject(up, displayId) || inject(up, displayId)) AsciiTextKeys.DISPATCHED else AsciiTextKeys.UNCONFIRMED
    } finally {
        if (modifierDown) {
            val up = event(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, 0)
            if (ownDisplay(displayId) && !inject(up, displayId) && !inject(up, displayId)) {
                Log.w(TAG, "could not release Ctrl on display $displayId")
            }
        }
    }
}

@SuppressLint("DiscouragedPrivateApi")
internal fun ShellService.sendAsciiText(displayId: Int, taskId: Int, packageName: String, text: String): Int {
    if (displayId <= Display.DEFAULT_DISPLAY || !ownDisplay(displayId) || taskId < 0 || !AsciiTextKeys.supports(text)) {
        return AsciiTextKeys.NOT_SENT
    }
    val (events, targetUid) = runCatching {
        val task = tasksOn(displayId).firstOrNull() ?: return AsciiTextKeys.NOT_SENT
        val top = TASK_INFO.getField("topActivity").get(task) as? ComponentName
        if (TASK_INFO.getField("taskId").getInt(task) != taskId || top?.packageName != packageName) {
            return AsciiTextKeys.NOT_SENT
        }
        val uid = if (Build.VERSION.SDK_INT >= 33) {
            val user = TASK_INFO.getField("userId").getInt(task)
            val pm = context.packageManager
            (pm.javaClass.getMethod("getPackageUidAsUser", String::class.java, Int::class.java, Int::class.java)
                .invoke(pm, packageName, 0, user) as Int).takeIf { it >= 0 } ?: return AsciiTextKeys.NOT_SENT
        } else null
        val keys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray())
            ?.takeIf { it.isNotEmpty() } ?: return AsciiTextKeys.NOT_SENT
        keys to uid
    }.getOrElse { return AsciiTextKeys.NOT_SENT }

    for (event in events) {
        if (!ownDisplay(displayId) || (targetUid == null && topTaskId(displayId) != taskId)) {
            return AsciiTextKeys.UNCONFIRMED
        }
        event.source = InputDevice.SOURCE_KEYBOARD
        val sent = if (targetUid != null) injectTo(event, displayId, targetUid)
            else inject(event, displayId, waitForFinish = true)
        // A failed dispatch can leave a prefix. Never replay it through another input route.
        if (!sent) return AsciiTextKeys.UNCONFIRMED
    }
    return AsciiTextKeys.DISPATCHED
}

internal fun ShellService.sendTap(displayId: Int, x: Int, y: Int): Boolean {
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
    val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
    return try {
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN
        injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "tap", "$x", "$y") == 0 }
    } finally {
        down.recycle()
        up.recycle()
    }
}

internal fun ShellService.sendStroke(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
    if (!ownDisplay(displayId)) return false
    val n = times.size
    if (n < 2 || n > STROKE_MAX_POINTS || xs.size != n || ys.size != n || times[0] != 0L ||
        times.last() > STROKE_MAX_MS || (1 until n).any { times[it] < times[it - 1] }
    ) return false
    val down = SystemClock.uptimeMillis()
    fun event(i: Int, action: Int): MotionEvent {
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER }
        val coords = MotionEvent.PointerCoords().apply { x = xs[i].toFloat(); y = ys[i].toFloat(); pressure = 1f; size = 1f }
        return MotionEvent.obtain(down, down + times[i], action, 1, arrayOf(props), arrayOf(coords), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }
    fun send(i: Int, action: Int): Boolean {
        val wait = down + times[i] - SystemClock.uptimeMillis()
        if (wait > 0) SystemClock.sleep(wait)
        val e = event(i, action)
        return try { inject(e, displayId) } finally { e.recycle() }
    }
    if (!send(0, MotionEvent.ACTION_DOWN)) return false
    for (i in 1 until n) send(i, MotionEvent.ACTION_MOVE)
    return send(n - 1, MotionEvent.ACTION_UP) || send(n - 1, MotionEvent.ACTION_UP)
}
