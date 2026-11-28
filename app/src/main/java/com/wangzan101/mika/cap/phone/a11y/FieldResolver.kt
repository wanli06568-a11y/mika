package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.EDITOR_NOT_VISIBLE
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.FOCUS_WAIT_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.NO_FOCUS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.QUIET_MS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SAME_FIELD_SIZE
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Companion.SEARCH_WORDS
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.Field
import com.wangzan101.mika.cap.phone.a11y.AndroidScreen.FieldShape
import com.wangzan101.mika.core.screen.uistate.RELOCATE_MAX_SHIFT
import com.wangzan101.mika.core.screen.uistate.Relocation
import com.wangzan101.mika.core.screen.uistate.relocate
import kotlin.math.min

// 编号解析与找输入框:把快照里的编号解析回节点,找要输入的框、拿着输入焦点的框。都是 [AndroidScreen] 上的扩展,和它同包。

/**
 * 把编号解析成可操作的节点;经过重定位时第二个值是找回的元素现在的名字,否则为 null。
 * 最新快照里的节点还在且没变就直接用;快照过期或节点变了,按原元素特征在当前界面里找唯一对应者。
 */
internal fun AndroidScreen.resolve(ref: String, snapshotId: String): Pair<AccessibilityNodeInfo, String?>? {
    val (entry, snapshot) = when (val l = ids.lookup(ref, snapshotId)) {
        is NodeIds.Lookup.Unknown -> return null
        is NodeIds.Lookup.Current -> {
            if (unchanged(l.entry)) return l.entry.node to null
            l.entry to l.snapshot
        }
        is NodeIds.Lookup.Old -> l.entry to l.snapshot
    }
    val now = read()
    return when (val r = relocate(entry.locator, snapshot.locators, now.entries.map { it.locator }, min(screenWidth, screenHeight))) {
        is Relocation.Found -> now.entries[r.index].let { it.node to it.element.name }
        else -> null
    }
}

/** 节点还在,且类型、名字、资源 ID、位置都和快照时一样(列表复用后同一个节点可能换了内容)。 */
internal fun AndroidScreen.unchanged(entry: NodeEntry): Boolean {
    val n = entry.node
    if (!n.refresh()) return false
    val now = ScreenReader.locatorOf(n, screenWidth, screenHeight) ?: return false
    return now == entry.locator.copy(context = null)
}


/**
 * 找要输入的框。给了编号:元素本身或它里面的输入框;都不是、而它看上去是输入入口(首页那条「假搜索框」:
 * 可点、不是按钮、带提示语或名字像搜索)时先点它,点成功且出现了一个点之前没有的、拿着焦点的输入框,才用那个框。
 * 没给编号:当前拿着输入焦点的框。
 */
internal suspend fun AndroidScreen.findField(ref: String?, snapshotId: String?, tapToFocus: Boolean = true): Field {
    if (ref != null && snapshotId != null) {
        val (node, relocatedTo) = resolve(ref, snapshotId) ?: return Field.Missing("ref_expired")
        val editable = if (ScreenReader.canSetText(node)) node
            else descendant(node) { ScreenReader.canSetText(it) && visibleInputTarget(it) }
        if (editable != null) {
            if (!editable.refresh() || !visibleInputTarget(editable)) return Field.Missing(EDITOR_NOT_VISIBLE)
            return Field.Found(editable, relocatedTo = relocatedTo)
        }
        if (!tapToFocus || !looksLikeInputEntry(node)) return Field.Missing("not_editable")
        val focusedBefore = focusedEditable()
        val clicked = withEventClock { clock ->
            clock.mark()
            click(node).also { if (it) clock.awaitQuiet(QUIET_MS, FOCUS_WAIT_MS) }
        }
        val now = focusedField()
        return when {
            clicked && now is Field.Found && now.node != focusedBefore ->
                Field.Found(now.node, tapped = true, relocatedTo = relocatedTo)
            clicked && now is Field.Missing && now.reason == EDITOR_NOT_VISIBLE -> now
            else -> Field.Missing("not_editable")
        }
    }
    return focusedField()
}


internal fun AndroidScreen.sameField(a: FieldShape, n: AccessibilityNodeInfo): Boolean {
    val b = FieldShape.of(n)
    if (a.pkg != b.pkg || a.windowId != b.windowId || a.cls != b.cls || a.rid != b.rid) return false
    // 大小要几乎一样;位置允许整页挪动一点(输入时页面常会上移),和编号找回用同一个上限
    val short = min(screenWidth, screenHeight)
    val sizeTol = short * SAME_FIELD_SIZE
    val moveTol = short * RELOCATE_MAX_SHIFT
    return kotlin.math.abs(a.rect.width() - b.rect.width()) <= sizeTol && kotlin.math.abs(a.rect.height() - b.rect.height()) <= sizeTol &&
        kotlin.math.hypot((a.rect.centerX() - b.rect.centerX()).toDouble(), (a.rect.centerY() - b.rect.centerY()).toDouble()) <= moveTol
}

/** 像输入入口:可点,不是按钮类,且带提示语或名字像搜索。 */
internal fun AndroidScreen.looksLikeInputEntry(n: AccessibilityNodeInfo): Boolean {
    val target = ancestorOrSelf(n) { it.isClickable } ?: return false
    val cls = target.className?.toString().orEmpty()
    if (cls.endsWith("Button")) return false
    if (ScreenReader.placeholderOf(n) != null || !n.hintText.isNullOrBlank()) return true
    val name = ScreenReader.ownLabel(n) ?: ScreenReader.ownLabel(target) ?: return false
    return SEARCH_WORDS.any { name.contains(it, ignoreCase = true) }
}

/**
 * 这块屏上有东西拿着输入焦点(哪怕不是树里的输入框,比如 App 自己画的界面整块拿着焦点),或弹着输入法。
 * 都没有时按键没有框可去:树里看不到的输入框也只有拿着焦点才收得到粘贴。
 */
internal fun AndroidScreen.somethingHoldsInputFocus(): Boolean =
    textInput.imeBounds() != null || focusedInput() != null

internal fun AndroidScreen.focusedInput(): AccessibilityNodeInfo? {
    val content = ScreenReader.contentWindows(windows())
    val focusedWindow = content.firstOrNull { it.isFocused }
    if (focusedWindow != null) return focusedWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    return content.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
}

internal fun AndroidScreen.visibleInputTarget(node: AccessibilityNodeInfo): Boolean =
    node.isEnabled && ScreenReader.locatorOf(node, screenWidth, screenHeight) != null

/** Keep a known hidden native editor distinct from a custom editor absent from the tree. */
internal fun AndroidScreen.focusedField(): Field {
    val focus = focusedInput() ?: return Field.Missing(NO_FOCUS)
    val editor = if (ScreenReader.canSetText(focus)) focus
        else descendant(focus) { it.isFocused && ScreenReader.canSetText(it) } ?: return Field.Missing(NO_FOCUS)
    return if (editor.refresh() && visibleInputTarget(editor)) Field.Found(editor) else Field.Missing(EDITOR_NOT_VISIBLE)
}

internal fun AndroidScreen.focusedEditable(): AccessibilityNodeInfo? = (focusedField() as? Field.Found)?.node

internal fun AndroidScreen.blockedFocusedEditor(): Boolean = (focusedField() as? Field.Missing)?.reason == EDITOR_NOT_VISIBLE
