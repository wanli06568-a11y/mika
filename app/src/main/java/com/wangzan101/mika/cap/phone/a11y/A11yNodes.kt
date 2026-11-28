package com.wangzan101.mika.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo

// 无障碍节点树的遍历:每处的上限各自传入。序列是惰性的,找到就停,不会多取父节点或子节点。

/** 往上的祖先(不含自己),由近到远,最多 [max] 个;到根就停。 */
internal fun AccessibilityNodeInfo.ancestors(max: Int): Sequence<AccessibilityNodeInfo> =
    generateSequence(parent) { it.parent }.take(max)

/** 自己,再往上最多 [maxUp] 层祖先。 */
internal fun AccessibilityNodeInfo.selfAndAncestors(maxUp: Int): Sequence<AccessibilityNodeInfo> =
    generateSequence(this) { it.parent }.take(maxUp + 1)

/** 自己和下面的节点,按层次(广度优先),最多走 [max] 个。 */
internal fun AccessibilityNodeInfo.breadthFirst(max: Int): Sequence<AccessibilityNodeInfo> = sequence {
    val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@breadthFirst) }
    var visited = 0
    while (queue.isNotEmpty() && visited++ < max) {
        val n = queue.removeFirst()
        yield(n)
        for (i in 0 until n.childCount) n.getChild(i)?.let(queue::add)
    }
}
