package com.wangzan101.mika.cap.phone.a11y

import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.WEB_VIEW
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.EventClock
import com.wangzan101.mika.core.cap.phone.GesturePacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

// 底层触摸:无障碍点击、手势,不行时由内置助手注入触摸。都是 [AndroidScreen] 上的扩展,和它同包。

internal fun AndroidScreen.isClickableTarget(node: AccessibilityNodeInfo): Boolean =
    node.isEnabled && (node.isClickable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })

/** 坐标点击无反应时,反查该点处最小的可点控件改走无障碍点击;命中对外/不可逆按钮或大容器时不补点。 */
internal fun AndroidScreen.clickNodeAt(x: Int, y: Int, clock: EventClock): Boolean {
    if (!hasDisplay || handedOver() || textInput.imeBounds()?.contains(x, y) == true) return false
    val window = ScreenReader.contentWindows(windows()).firstOrNull {
        Rect().also(it::getBoundsInScreen).contains(x, y)
    }?.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
    val entries = ScreenReader.read(listOf(window), screenWidth, screenHeight).entries
    val rect = Rect()
    val hitEntries = entries.filter { entry ->
        entry.node.getBoundsInScreen(rect)
        rect.intersect(0, 0, screenWidth, screenHeight) && rect.contains(x, y)
    }
    if (hitEntries.isEmpty()) return false
    if (hitEntries.any { ClickFallback.mayBeOutward(it.element.name, it.locator.resourceId, clickable = it.element.clickable) }) return false
    val seen = HashSet<AccessibilityNodeInfo>()
    val candidates = mutableListOf<ClickFallback.Candidate<AccessibilityNodeInfo>>()
    for (entry in hitEntries) {
        val target = ancestorOrSelf(entry.node) { isClickableTarget(it) } ?: continue
        if (!seen.add(target) || !target.isVisibleToUser) continue
        target.getBoundsInScreen(rect)
        if (!rect.intersect(0, 0, screenWidth, screenHeight) || rect.isEmpty) continue
        candidates += ClickFallback.Candidate(
            item = target,
            left = rect.left, top = rect.top, right = rect.right, bottom = rect.bottom,
            label = ScreenReader.labelOf(target).ifBlank { entry.element.name.ifBlank { null } },
            resourceId = target.viewIdResourceName ?: entry.locator.resourceId,
            scrollable = target.isScrollable,
            webView = target.className == WEB_VIEW,
        )
    }
    val picked = ClickFallback.pickTarget(x, y, screenWidth, screenHeight, candidates) ?: return false
    clock.mark()
    return picked.performAction(AccessibilityNodeInfo.ACTION_CLICK)
}

/** 优先对节点(或最近的可点祖先)发点击;不行再在其中心做手势,最后注入触摸。 */
internal suspend fun AndroidScreen.click(node: AccessibilityNodeInfo): Boolean {
    val target = ancestorOrSelf(node) { isClickableTarget(it) }
    if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
    val rect = android.graphics.Rect().also { node.getBoundsInScreen(it) }
    return touch(pacing.within(rect.left, rect.top, rect.right, rect.bottom))
}

internal suspend fun AndroidScreen.tapAt(x: Int, y: Int): Boolean = touch(pacing.near(x, y))

/** 在这一点上点一下:先停一会儿再落指,按住随机的一小会儿。无障碍手势不行时由内置助手注入触摸。 */
internal suspend fun AndroidScreen.touch(at: Pair<Int, Int>): Boolean {
    val (x, y) = at
    delay(pacing.approach())
    val press = pacing.press()
    if (a11y.gesture(displayId, Path().apply { moveTo(x.toFloat(), y.toFloat()) }, press)) return true
    if (pacing.paced && injectStroke(listOf(GesturePacing.Point(x.toDouble(), y.toDouble(), 0), GesturePacing.Point(x.toDouble(), y.toDouble(), press)))) return true
    return shell.injectTap(displayId, x, y)
}

/**
 * 一根手指沿 [points] 滑动。有节奏时按 [GesturePacing.stroke] 排好每个点的时刻,由内置助手逐点注入(App 看到先快后慢的真实速度);
 * 助手注不了时退回无障碍手势沿同一条弧线走(系统按路径匀速走,只少了快慢)。不加节奏时和以前一样:无障碍手势按给定时长匀速走折线。
 */
internal suspend fun AndroidScreen.drag(points: List<Pair<Int, Int>>, durationMs: Long, bow: Boolean): Boolean {
    if (!pacing.paced) return a11y.gesture(displayId, pathOf(points), durationMs)
    delay(pacing.approach())
    val stroke = pacing.stroke(points.map { it.first.toDouble() to it.second.toDouble() }, durationMs, bow, screenWidth to screenHeight)
    if (injectStroke(stroke.points)) return true
    return a11y.gesture(displayId, pathOf(stroke.points.map { it.x.roundToInt() to it.y.roundToInt() }), stroke.durationMs)
}

internal fun AndroidScreen.injectStroke(points: List<GesturePacing.Point>): Boolean = runCatching {
    shell.injectStroke(
        displayId,
        IntArray(points.size) { points[it].x.roundToInt() },
        IntArray(points.size) { points[it].y.roundToInt() },
        LongArray(points.size) { points[it].t },
    )
}.getOrDefault(false)

internal fun AndroidScreen.pathOf(points: List<Pair<Int, Int>>) = Path().apply {
    moveTo(points.first().first.toFloat(), points.first().second.toFloat())
    points.drop(1).forEach { lineTo(it.first.toFloat(), it.second.toFloat()) }
}
