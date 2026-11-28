package com.wangzan101.mika.cap.phone.shell

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.view.Display
import com.wangzan101.mika.core.screen.uistate.AppCopies
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TAG
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PER_USER_RANGE
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_OK
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_IN_USE_ON_MAIN
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_NOT_FOUND
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_ERROR
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_OK_COLD_IGNORED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_INVALID_LINK
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_IN_USE_BY_OTHER
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.START_UNRESOLVED
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.PROFILE_API_VISIBILITY_HIDDEN
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.TASK_INFO
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.USER_CURRENT
import com.wangzan101.mika.cap.phone.shell.ShellService.Companion.sameCopy

// 助手服务:启动应用、链接与分享、系统动作、分身与多用户、自家页面
/**
 * 以 shell 包名调 IActivityTaskManager.startActivityAsUser([ShellService.startActivityAsUser]),带 [options](落在哪块屏)。
 * 系统接下了(结果 >= 0)为 true;拒绝时记下结果码([what] 标明是哪一次启动)、返回 false。接口不可用时抛出,由调用方退回别的路。
 */
internal fun ShellService.startAsShell(intent: Intent, resolvedType: String?, options: Bundle, userId: Int, what: String): Boolean {
    // (caller, callingPackage, callingFeatureId, intent, resolvedType, resultTo, resultWho,
    //  requestCode, flags, profilerInfo, options, userId)
    val result = startActivityAsUser.invoke(
        taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, resolvedType, null, null,
        0, 0, null, options, userId,
    ) as Int
    if (result < 0) Log.w(TAG, "startActivityAsUser($what) -> $result")
    return result >= 0
}

/**
 * 启动前的占用检查:[onMain] 为真(用户在主屏用着)→ [START_IN_USE_ON_MAIN];[packages] 在 Mika 另一块屏上
 * ([userId] 不为空时只看那份用户里的)→ [START_IN_USE_BY_OTHER];都没有为 null。
 * 看不清任务状态时为 [START_ERROR]:宁可不启动,也不冒险打扰用户。
 */
internal inline fun ShellService.inUseCode(packages: Collection<String>, displayId: Int, userId: Int? = null, onMain: () -> Boolean): Int? = try {
    when {
        onMain() -> START_IN_USE_ON_MAIN
        onOtherOwnDisplay(packages, displayId, userId) -> START_IN_USE_BY_OTHER
        else -> null
    }
} catch (t: Throwable) {
    Log.e(TAG, "cannot inspect main-screen tasks", t)
    START_ERROR
}

/** 能接链接或系统动作的这些包里,有一个在主屏有任务(规则见 [ShellLinks.inUse])。 */
internal fun ShellService.linkInUseOnMain(packages: List<String>): Boolean =
    ShellLinks.inUse(packages, tasksOn(Display.DEFAULT_DISPLAY).flatMap(::packagesOf).toSet())

/**
 * 在 [userId] 那份用户里启动 [packageName](见 [startApp]);占用只看这一份(包名 + 用户)。
 * [userId] 是 Mika 自己的用户时按原来的方式启动(USER_CURRENT、包管理器给的入口);别的用户先在那份用户里查启动入口,
 * 再以那份用户启动,接口不可用时退回 `am start --user`。
 */
internal fun ShellService.start(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    inUseCode(listOf(packageName), displayId, userId) { inUseOnMainScreen(packageName, userId) }?.let { return it }
    // 上次任务留下的那个任务还在主屏后面:整个挪回来,接着那一页做,不另开一个从头来
    if (resumeKept(displayId, packageName, userId)) return START_OK
    val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    // 另一份用户里的先走桌面打开图标的那条路:有的系统对程序启动的另一份先弹「用哪一个打开」,桌面这条路不弹
    val component = intent.component
    if (userId != myUser && component != null && startAsLauncher(component, userId, options)) return START_OK
    val ok = try {
        startAsShell(intent, null, options, if (userId == myUser) USER_CURRENT else userId, "$packageName, user $userId")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
        val user = if (userId == myUser) emptyArray() else arrayOf("--user", "$userId")
        exec("am", "start", *user, "--display", "$displayId", "-n", component) == 0
    }
    if (ok) START_OK else START_ERROR
}

/**
 * 像桌面那样打开另一份用户 [userId] 里的启动页 [component]:ILauncherApps.startActivityAsUser(LauncherApps.startMainActivity
 * 背后的接口),带同样的 [options](启动到 Mika 的屏)。系统那边按调用方查能不能碰那份用户:shell 有跨用户的权限,
 * 包名也对得上自己的 uid。接口不在、或被拒(抛错)返回 false,调用方改走 startActivityAsUser。
 */
internal fun ShellService.startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
    val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
    val user = UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
    // 安卓 11 起:(caller, callingPackage, callingFeatureId, component, sourceBounds, opts, user);更早没有 callingFeatureId
    val m = la.javaClass.methods.first { it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 && ComponentName::class.java in it.parameterTypes }
    val args: Array<Any?> = if (m.parameterTypes.size == 7) arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
    else arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
    m.invoke(la, *args)
    true
} catch (t: Throwable) {
    Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} in user $userId failed", t)
    false
}

/**
 * 打开 [packageName] 用的 intent:Mika 自己的用户里就是包管理器给的入口;别的用户里查那份用户的启动页
 * (隐藏接口 queryIntentActivitiesAsUser,不行改问 `cmd package query-activities --user`)。查不到为 null。
 */
@SuppressLint("PrivateApi")
internal fun ShellService.launchIntent(packageName: String, userId: Int): Intent? {
    if (userId == myUser) return context.packageManager.getLaunchIntentForPackage(packageName)
    val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName)
    val found = try {
        val pm = context.packageManager
        val m = pm.javaClass.getMethod("queryIntentActivitiesAsUser", Intent::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        (m.invoke(pm, query, 0, userId) as List<*>).filterIsInstance<ResolveInfo>().map { it.activityInfo.packageName to it.activityInfo.name }
    } catch (t: Throwable) {
        Log.w(TAG, "queryIntentActivitiesAsUser failed, asking the package service", t)
        val args = ActivityQuery.args(Intent.ACTION_MAIN, category = Intent.CATEGORY_LAUNCHER, packageName = packageName, user = "$userId")
        sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
    }
    val (pkg, cls) = found?.firstOrNull { it.first == packageName } ?: return null
    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(ComponentName(pkg, cls))
}

/** 这些包里有在 Mika 另一块屏(不是 [displayId])上的任务:那是别的任务正在用的 App。[userId] 不为空时只看那份用户里的。 */
internal fun ShellService.onOtherOwnDisplay(packages: Collection<String>, displayId: Int, userId: Int? = null): Boolean {
    val others = synchronized(displays) { displays.keys.toSet() } - displayId
    if (others.isEmpty()) return false
    val displayField = TASK_INFO.getField("displayId")
    return allTasks().any { t -> displayField.getInt(t) in others && packages.any { isCopy(t, it, userId) } }
}

/** 任务是 [packageName] 在 [userId] 那份用户里的那份(规则见 [sameCopy])。 */
internal fun ShellService.isCopy(task: Any, packageName: String, userId: Int?): Boolean =
    sameCopy(packagesOf(task), runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrNull(), packageName, userId)

/** [restartApp] 作用在 [userId] 那份用户里的那份:只看这一份的任务、只结束这份用户里的进程,装在别的用户里的另一份不动。 */
internal fun ShellService.restart(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
    // 不是自己建的屏(含主屏)一律不动,更不能先结束进程
    if (!ownDisplay(displayId)) return START_ERROR
    val decision = try {
        // 别的任务正在 Mika 另一块屏上用它:绝不结束它的进程
        if (onOtherOwnDisplay(listOf(packageName), displayId, userId)) return START_IN_USE_BY_OTHER
        // 要从头开:上次任务留在主屏后面的那份是 Mika 自己留下的,先关掉,不算用户的状态
        dropKept(packageName, userId)
        val displayField = TASK_INFO.getField("displayId")
        val taskDisplays = allTasks().filter { isCopy(it, packageName, userId) }.map { displayField.getInt(it) }
        ColdStart.decide(taskDisplays, displayId, inUseOnMainScreen(packageName, userId))
    } catch (t: Throwable) {
        Log.e(TAG, "cannot inspect tasks", t)
        return START_ERROR
    }
    if (launchIntent(packageName, userId) == null) return START_NOT_FOUND
    when (decision) {
        ColdStart.IN_USE -> START_IN_USE_ON_MAIN
        ColdStart.WARM -> start(displayId, packageName, userId).let { if (it == START_OK) START_OK_COLD_IGNORED else it }
        ColdStart.RESTART -> {
            val user = if (userId == myUser) "current" else "$userId"
            if (exec("am", "force-stop", "--user", user, packageName) != 0) return START_ERROR
            start(displayId, packageName, userId)
        }
    }
}

/**
 * 这台手机的用户,按安卓原版的 UserInfo / UserProperties 读(隐藏接口反射):标记、类型、资料组、是否在运行、安静模式、
 * 是否和当前用户共用媒体、是否对别的 App 隐藏。读不到用户列表为 null;单项读不到的留空(按不满足算,见 [AppCopies])。
 */
@SuppressLint("PrivateApi")
internal fun ShellService.users(): List<AppCopies.User>? {
    val um = runCatching { context.getSystemService(UserManager::class.java) }.getOrNull()
        ?: UserManager::class.java.getMethod("get", Context::class.java).invoke(null, context) as UserManager
    val list = (runCatching { um.javaClass.getMethod("getAliveUsers").invoke(um) }.getOrNull()
        ?: runCatching { um.javaClass.getMethod("getUsers").invoke(um) }.getOrNull()) as? List<*> ?: return null
    val info = Class.forName("android.content.pm.UserInfo")
    // 系统自己给标记起的名字(dumpsys user 用的同一个 UserInfo.flagsToString,含系统自定义的标记);反射不行时读 dumpsys user
    val flagsToString = runCatching { info.getMethod("flagsToString", Int::class.javaPrimitiveType) }.getOrNull()
    val dumped by lazy { sh("dumpsys", "user").takeIf { it.first == 0 }?.second?.let(AppCopies::parseDumpsys).orEmpty() }
    return list.filterNotNull().map { u ->
        val id = info.getField("id").getInt(u)
        val flags = info.getField("flags").getInt(u)
        val handle = UserHandle.getUserHandleForUid(id * PER_USER_RANGE)
        val props = runCatching { um.javaClass.getMethod("getUserProperties", UserHandle::class.java).invoke(um, handle) }.getOrNull()
        fun prop(name: String): Any? = props?.let { p -> runCatching { p.javaClass.getMethod(name).invoke(p) }.getOrNull() }
        val names = flagsToString?.let { m -> runCatching { (m.invoke(null, flags) as? String)?.let(::flagNames) }.getOrNull() }
            ?: dumped[id]?.flagNames
        val group = runCatching { info.getField("profileGroupId").getInt(u) }.getOrDefault(AppCopies.NO_PROFILE_GROUP)
        val running = runCatching { um.isUserRunning(handle) }.getOrDefault(false)
        AppCopies.User(
            id = id,
            flags = flags,
            type = runCatching { info.getField("userType").get(u) as? String }.getOrNull(),
            profileGroupId = group,
            running = running,
            unlocked = running && runCatching { um.isUserUnlocked(handle) }.getOrDefault(false),
            quiet = runCatching { um.isQuietModeEnabled(handle) }.getOrDefault(false),
            mediaShared = prop("isMediaSharedWithParent") as? Boolean,
            hidden = (prop("getProfileApiVisibility") as? Int)?.let { it == PROFILE_API_VISIBILITY_HIDDEN },
            flagNames = names,
            // dumpsys 里的 parentId 就是资料组(不是自己、也不是「不在组里」时才写);读到了一并带上
            parentId = group.takeIf { it != id && it != AppCopies.NO_PROFILE_GROUP } ?: if (flagsToString == null) dumped[id]?.parentId else null,
        )
    }
}

/** flagsToString 的结果(如 "CLONED_PROFILE|FULL|INITIALIZED",有的版本带花括号)拆成名字。 */
internal fun ShellService.flagNames(s: String): List<String> = s.trim('{', '}', ' ').split('|', ' ', ',').map(String::trim).filter(String::isNotEmpty)

/**
 * 以 shell 身份把 Mika 的兼容性自检页([SystemIntents.OWN_PAGE_CLASS])开到这块屏,和 [startApp] 同一条路
 * (startActivityAsUser 带 setLaunchDisplayId,不行退回 `am start --display`)。那一页只对持有 INTERACT_ACROSS_USERS_FULL 的
 * 调用方开放(shell 有,普通 App 没有);shell 启动不受后台启动页面的限制。包名必须是 Mika 自己的,别的组件一律不开。
 */
internal fun ShellService.startOwnPage(displayId: Int, intent: Intent): Int {
    val component = intent.component ?: return START_INVALID_LINK
    val mine = runCatching { context.packageManager.getPackagesForUid(allowedUid)?.toList() }.getOrNull().orEmpty()
    // 只有 Mika 调得到这个服务;包名再核一次(查不到 Mika 的包名时只认类名)
    if (component.className != SystemIntents.OWN_PAGE_CLASS || (mine.isNotEmpty() && component.packageName !in mine)) return START_INVALID_LINK
    // 每次都从头开一页:旧的那一页连同它的任务清掉,新的一定在 Mika 屏的最上面
    val page = Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(page, null, options, USER_CURRENT, "own page")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec("am", "start", "--display", "$displayId", "--activity-clear-task", "-n", component.flattenToShortString()) == 0
    }
    return if (ok) START_OK else START_ERROR
}

/**
 * 能接 [intent] 的页面,(包名, 完整类名)。系统接口在这一侧查不了时改问系统服务(见 [ActivityQuery]);都不行时为 null。
 * [type] 是 intent 的数据类型(命令里要单独给)。
 */
internal fun ShellService.activitiesFor(intent: Intent, type: String?): List<Pair<String, String>>? = try {
    context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName to it.activityInfo.name }
} catch (t: Throwable) {
    Log.w(TAG, "queryIntentActivities failed, asking the package service", t)
    val args = ActivityQuery.args(intent.action!!, type, intent.dataString, intent.categories?.firstOrNull(), intent.`package`)
    sh(*args.toTypedArray()).takeIf { it.first == 0 }?.second?.let(ActivityQuery::parse)
        .also { if (it == null) Log.e(TAG, "cannot resolve ${intent.action}") }
}

internal fun ShellService.packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
    (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
}

/** [ShellService.startView] 的实现。 */
internal fun ShellService.launchView(displayId: Int, action: String?, uri: String?, packageName: String?): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    ShellLinks.refuse(action, uri, packageName)?.let { return it }
    val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    uri?.let { intent.data = Uri.parse(it) }
    packageName?.let { intent.setPackage(it) }
    // 网页式链接只交给声明了可从链接打开的页面,不碰 App 的内部页面
    if (action == null) intent.addCategory(Intent.CATEGORY_BROWSABLE)
    val candidates = activitiesFor(intent, null) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    val handlers = candidates.map { it.first }.distinct()
    inUseCode(handlers, displayId) { linkInUseOnMain(handlers) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, null, options, USER_CURRENT, "link")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        val target = intent.component?.let { listOf("-n", it.flattenToShortString()) }
            ?: (listOf("-a", intent.action!!) + listOfNotNull(uri?.let { "-d" }, uri) + listOfNotNull(packageName?.let { "-p" }, packageName) +
                (if (action == null) listOf("-c", Intent.CATEGORY_BROWSABLE) else emptyList()))
        exec("am", "start", "--display", "$displayId", *target.toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** [ShellService.startShare] 的实现。 */
internal fun ShellService.launchShare(displayId: Int, packageName: String, text: String): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    val intent = ShareIntent.of(packageName, text)
    val candidates = activitiesFor(intent, ShareIntent.TYPE) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    inUseCode(listOf(packageName), displayId) { inUseOnMainScreen(packageName) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, ShareIntent.TYPE, options, USER_CURRENT, "share")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec(*ShareIntent.amArgs(displayId, packageName, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** [ShellService.startShareFile] 的实现。 */
internal fun ShellService.launchShareFile(displayId: Int, packageName: String, uri: String, mime: String, text: String): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    val target = Uri.parse(uri)
    val intent = ShareIntent.ofFile(packageName, target, mime, text)
    val candidates = activitiesFor(intent, mime) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    inUseCode(listOf(packageName), displayId) { inUseOnMainScreen(packageName) }?.let { return it }
    candidates.singleOrNull()?.let { (pkg, cls) -> intent.component = ComponentName(pkg, cls) }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
    val ok = try {
        startAsShell(intent, mime, options, USER_CURRENT, "share file")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
        exec(*ShareIntent.amArgsFile(displayId, packageName, target, mime, text, intent.component?.flattenToShortString()).toTypedArray()) == 0
    }
    if (ok) START_OK else START_ERROR
}

/** [ShellService.startSystemAction] 的实现。 */
internal fun ShellService.launchSystemAction(displayId: Int, intent: Intent, asMika: PendingIntent?): Int = synchronized(launchLock) {
    if (!ownDisplay(displayId)) return START_ERROR
    // Mika 自己的兼容性自检页:以 shell 身份直接开到这块屏
    if (intent.component?.className == SystemIntents.OWN_PAGE_CLASS) return startOwnPage(displayId, intent)
    if (!SystemIntents.allowed(intent.action, intent.dataString)) return START_INVALID_LINK
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val candidates = activitiesFor(intent, intent.type) ?: return START_ERROR
    if (candidates.isEmpty()) return START_UNRESOLVED
    val handlers = candidates.map { it.first }.distinct()
    inUseCode(handlers, displayId) { linkInUseOnMain(handlers) }?.let { return it }
    val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
    val asShell = try {
        startAsShell(intent, intent.type, options.toBundle(), USER_CURRENT, "${intent.action}")
    } catch (t: Throwable) {
        Log.w(TAG, "startActivityAsUser(${intent.action}) refused as shell", t)
        false
    }
    if (asShell) return START_OK
    if (asMika == null) return START_ERROR
    // 安卓 14 起 PendingIntent 的发出方要明说允许它从后台启动页面
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
    return try {
        asMika.send(context, 0, null, null, null, null, options.toBundle())
        START_OK
    } catch (t: Throwable) {
        Log.w(TAG, "sending ${intent.action} as Mika failed", t)
        START_ERROR
    }
}
