package com.wangzan101.mika.cap.phone.a11y

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.ALARM_POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.ALARM_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.IN_USE_BY_OTHER
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.LAUNCH_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.POLL_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SETTLE_CAP_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SYSTEM_ACTION_REQUEST
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.TargetApp
import com.wangzan101.mika.cap.phone.shell.ShellService
import com.wangzan101.mika.cap.phone.shell.SystemIntents
import com.wangzan101.mika.core.device.access.AppAccessState
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.SystemAction
import com.wangzan101.mika.core.screen.uistate.SystemActionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 打开链接 / 设置页、经安卓标准 intent 做系统动作:启动到虚拟屏,等它到前台,核实落在了谁那里。
 * 共享 [AndroidScreen] 的字段与方法经 [screen] 访问(方法体在 `with(screen)` 里)。
 */
internal class LinkAndSystemActions(private val screen: AndroidScreen) {

    /**
     * 打开链接或设置页。和 [openApp] 一样第一次用时才建屏;用户在主屏用着那个 App 时不打开。
     * 设置页预期落在系统设置所在的包,指定了 App 的落在那个 App;落到别处(系统选择器、跳转页)照样成功,带提示。
     */
    suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult = withContext(Dispatchers.IO) {
        with(screen) {
            // 指定了 App 的由应用访问边界看过;没指定的,名单非空时在这里挑一个没被禁用的接手
            val pinned = packageName ?: allowedLinkHandler(action, uri)
            val id = display.get().displayId
            val expected = pinned ?: settingsPackage.takeIf { action != null }
            if (checkOtherTasks && expected != null && !reserve(expected)) return@withContext IN_USE_BY_OTHER
            val before = ScreenReader.topPackage(windows())
            val beforeTree = treeHash()
            val startedAt = System.nanoTime()
            // 事先知道由谁打开的(指定了 App、设置页、只有一个 App 接的链接):启动前先静上
            val early = expected ?: linkHandler(action, uri)
            early?.let { beforeOpen(it, null) }
            val code = shell.startView(id, action, uri, pinned)
            if (code != ShellService.START_OK) early?.let { notOpened(it, null) }
            when (code) {
                ShellService.START_OK -> Unit
                ShellService.START_IN_USE_ON_MAIN -> return@withContext ActionResult.Failed("app_in_use_by_user")
                ShellService.START_IN_USE_BY_OTHER -> return@withContext IN_USE_BY_OTHER
                ShellService.START_UNRESOLVED -> return@withContext ActionResult.Failed("link_unresolved")
                ShellService.START_INVALID_LINK -> return@withContext ActionResult.Failed("invalid_link")
                else -> return@withContext ActionResult.Failed("launch_failed")
            }
            // 链接可能落到另一个 App、系统选择器或同 App 的新页面,同样不能把启动前的帧交出去。
            frameGuardSince = startedAt
            fun landed(): Boolean {
                val top = ScreenReader.topPackage(windows())
                return if (expected != null) top == expected else top != null && (top != before || treeHash() != beforeTree)
            }
            val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
            while (!landed() && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            waitStable(SETTLE_CAP_MS)
            val top = ScreenReader.topPackage(windows())
            val changed = top != before || treeHash() != beforeTree
            if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
            // 只认真正打开链接的 App:落在系统选择器或别处时不把它当成目标
            val opened = top?.takeIf { if (expected != null) it == expected else it !in systemPickers }
            if (opened != null) {
                // 事先不知道由谁打开的链接,落地后再占;占不到也不撤回(外壳那边已查过别的屏)
                if (checkOtherTasks) reserve(opened)
                targetPackage = opened
                requestedUser = null
                val task = shell.topTaskId(displayId)
                _target.value = TargetApp(opened, task, userOf(task))
            }
            ok(hint = if (expected != null && top != expected) hintLinkElsewhere(top, expected) else null)
        }
    }

    /**
     * 经安卓标准 intent 做系统动作(见 [SystemIntents])。先在 Mika 这边查谁接(能力检测,不用建屏):没有就 not_supported;
     * 有几个时按 [SystemIntents.choose] 直接指定一个。再交给内置助手启动到 Mika 的屏(第一次用时建屏),动作前后照常盯着主屏。
     * 闹钟、计时器本该悄悄设好:之后读系统的下一个闹钟核对;App 却开了界面时照实报。闹钟列表、新日程要等那个 App 到最上面。
     */
    suspend fun systemAction(action: SystemAction): SystemActionResult = withContext(Dispatchers.IO) {
        with(screen) {
            val intent = SystemIntents.toIntent(SystemIntents.spec(action))
            val pm = a11y.packageManager
            val handlers = runCatching {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map {
                    SystemIntents.Handler(it.activityInfo.packageName, it.activityInfo.name, (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
            }.getOrDefault(emptyList())
            val default = defaultOf(pm, intent)
            // 由谁接只有这里知道:跳过被禁用的;能接的全被禁用就拒绝
            val allowedHandlers = permitted(appAccess.current(), handlers) { it.packageName }
            val handler = SystemIntents.choose(allowedHandlers, default)
                ?: return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
            val pkg = handler.packageName
            intent.component = android.content.ComponentName(pkg, handler.className)
            if (checkOtherTasks && !action.silent && !reserve(pkg)) return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
            val alarms = a11y.getSystemService(AlarmManager::class.java)
            val alarmBefore = runCatching { alarms?.nextAlarmClock?.triggerTime }
            val id = display.get().displayId
            val topBefore = ScreenReader.topPackage(windows())
            val beforeTree = treeHash()
            val startedAt = System.nanoTime()
            val w = spill.armSpillWatch()
            // 接手的页面要求的权限(设闹钟)shell 可能没有:备一个以 Mika 身份启动的
            val asMika = PendingIntent.getActivity(a11y, SYSTEM_ACTION_REQUEST, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            // 悄悄设好的(闹钟、计时器)不开界面,不去静它:时钟到点要响
            if (!action.silent) beforeOpen(pkg, null)
            val code = runCatching { shell.startSystemAction(id, intent, asMika) }.getOrDefault(ShellService.START_ERROR)
            if (code != ShellService.START_OK && !action.silent) notOpened(pkg, null)
            when (code) {
                ShellService.START_OK -> Unit
                ShellService.START_IN_USE_ON_MAIN -> return@withContext SystemActionResult(ActionResult.Failed("app_in_use_by_user"), pkg)
                ShellService.START_IN_USE_BY_OTHER -> return@withContext SystemActionResult(IN_USE_BY_OTHER, pkg)
                ShellService.START_UNRESOLVED -> return@withContext SystemActionResult(ActionResult.Failed(SystemActionResult.NOT_SUPPORTED))
                else -> return@withContext SystemActionResult(ActionResult.Failed("launch_failed"), pkg)
            }
            if (!action.silent) frameGuardSince = startedAt
            fun top() = ScreenReader.topPackage(windows())
            var alarmAfter = alarmBefore.getOrNull()
            if (action is SystemAction.SetAlarm && alarmBefore.isSuccess) {
                // 时钟 App 多半在后台写好再登记:等系统的下一个闹钟变了,最多等一会儿
                val deadline = SystemClock.uptimeMillis() + ALARM_WAIT_MS
                while (SystemClock.uptimeMillis() < deadline) {
                    alarmAfter = runCatching { alarms?.nextAlarmClock?.triggerTime }.getOrNull()
                    if (alarmAfter != alarmBefore.getOrNull()) break
                    delay(ALARM_POLL_MS)
                }
            } else if (!action.silent) {
                val deadline = SystemClock.uptimeMillis() + LAUNCH_WAIT_MS
                while (top() != pkg && SystemClock.uptimeMillis() < deadline) delay(POLL_MS)
            }
            waitStable(SETTLE_CAP_MS)
            val landed = top() == pkg
            // 本该悄悄设好的,屏上却多了那个 App 的界面
            val showing = action.silent && landed && topBefore != pkg
            if (!action.silent || showing) {
                val changed = top() != topBefore || treeHash() != beforeTree
                if (changed) awaitFrameAfter(startedAt) else frameGuardSince = null
            }
            if (landed && (!action.silent || showing)) {
                targetPackage = pkg
                requestedUser = null
                val task = runCatching { shell.topTaskId(displayId) }.getOrDefault(-1)
                _target.value = TargetApp(pkg, task, userOf(task))
            }
            val hint = if (!action.silent && !landed) hintNotInFront(top(), pkg) else null
            SystemActionResult(
                ok(hint = hint, spill = spill.takeSpill(w)), handledBy = pkg,
                nextAlarmBefore = alarmBefore.getOrNull(), nextAlarmAfter = alarmAfter, alarmReadable = alarmBefore.isSuccess, showing = showing,
            )
        }
    }

    /**
     * 禁用名单非空、链接没指定 App 时由谁打开:系统默认的那个没被禁用就用它,不然用第一个没被禁用的;能接的全被禁用就拒绝。
     * 名单为空、或没有 App 能接时为 null(照常交给系统,不多查一次)。
     */
    private fun allowedLinkHandler(action: String?, uri: String?): String? {
        val access = screen.appAccess.current()
        if (access.blocked.isEmpty()) return null
        val intent = Intent(action ?: Intent.ACTION_VIEW).apply {
            uri?.let { data = android.net.Uri.parse(it) }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val pm = screen.a11y.packageManager
        val candidates = runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct() }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val allowed = permitted(access, candidates) { it }
        return defaultOf(pm, intent)?.takeIf { it in allowed } ?: allowed.first()
    }

    /** 能接的里面没被禁用的那些;名单为空时原样返回,能接的全被禁用就抛出第一个的拒绝。 */
    private fun <T> permitted(access: AppAccessState, handlers: List<T>, pkg: (T) -> String): List<T> {
        if (access.blocked.isEmpty()) return handlers
        val allowed = handlers.filter { access.denial(pkg(it)) == null }
        if (handlers.isNotEmpty() && allowed.isEmpty()) access.denial(pkg(handlers.first()))?.let { throw it }
        return allowed
    }

    /** 系统给这个 intent 定的默认接手包;读不到为 null。 */
    private fun defaultOf(pm: PackageManager, intent: Intent): String? =
        runCatching { pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()

    /** 没指定 App 的链接由谁打开:系统直接定下的那一个;要问用户选(落在系统选择器)或读不到为 null。 */
    private fun linkHandler(action: String?, uri: String?): String? = runCatching {
        if (action != null || uri == null) return null
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        screen.a11y.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.getOrNull()?.takeIf { it !in screen.systemPickers }

    private fun hintLinkElsewhere(top: String?, expected: String) =
        "The link opened, but the screen shows ${top ?: "no app yet"} instead of $expected: a system chooser or a redirect page may have taken it. Observe before acting."

    private fun hintNotInFront(top: String?, expected: String) =
        "$expected took the action, but the screen shows ${top ?: "no app yet"}. Observe before acting."
}
