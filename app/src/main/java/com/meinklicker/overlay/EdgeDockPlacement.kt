package com.meinklicker.overlay

import android.graphics.Rect
import kotlin.math.abs

/** 优先同侧，沿该侧找离原位置最近、能容纳完整按钮的空隙。 */
internal fun findEdgeDock(safeArea: Rect, origin: Rect, size: Int, obstacles: List<Rect>): Rect? {
    if (safeArea.width() < size || safeArea.height() < size) return null
    val firstY = safeArea.top
    val lastY = safeArea.bottom - size
    val preferredY = origin.top.coerceIn(firstY, lastY)
    val edges = listOf(safeArea.left, safeArea.right - size)
        .sortedBy { abs(it + size / 2 - origin.centerX()) }
    for (x in edges) {
        val blocking = obstacles.filter { it.left < x + size && x < it.right }
        // 最近的可用点只可能在首选位置，或紧贴某个障碍的上下边缘。
        val candidates = buildList {
            add(preferredY)
            blocking.forEach {
                add(it.top - size)
                add(it.bottom)
            }
        }
        val y = candidates.filter { it in firstY..lastY }
            .filter { top -> blocking.none { top < it.bottom && it.top < top + size } }
            .minByOrNull { abs(it - preferredY) }
        if (y != null) return Rect(x, y, x + size, y + size)
    }
    return null
}
