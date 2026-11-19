package com.meinklicker.overlay

import android.content.Context
import android.graphics.Rect
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.rotationMembers

/** 每个 G 标记只占自己的触摸区域，其余屏幕仍可正常操作。 */
internal class RotationPointOverlay(
    private val context: Context,
    private val overlays: OverlayManager,
    private val onTapped: (scheme: Scheme, stepId: String) -> Unit,
) {
    private val size = overlays.dp(32)
    private val points = mutableMapOf<String, Point>()
    private var shownScheme: Scheme? = null

    private inner class Point(stepId: String) {
        val marker = MarkerView(context, STEP_COLOR).apply {
            setOnClickListener { shownScheme?.let { onTapped(it, stepId) } }
        }
        val params = overlays.newParams(width = size, height = size)
    }

    fun show(scheme: Scheme, bar: Rect?) {
        if (shownScheme?.id != scheme.id) hide()
        shownScheme = scheme
        val reserved = scheme.triggers.mapTo(mutableListOf()) { trigger ->
            val radius = overlays.dp(trigger.radiusDp)
            Rect(trigger.x - radius, trigger.y - radius, trigger.x + radius, trigger.y + radius)
        }
        if (bar != null && !bar.isEmpty) reserved += bar
        val screen = overlays.screen
        val maxX = screen.width - size
        val maxY = screen.height - size
        val nextId = scheme.rotationGroup?.nextStepId
        val visible = mutableSetOf<String>()
        for ((index, step) in scheme.rotationMembers().withIndex()) {
            val bounds = place(step, maxX, maxY, reserved) ?: continue
            reserved += bounds
            visible += step.id
            val point = points.getOrPut(step.id) { Point(step.id) }
            val selected = step.id == nextId
            point.marker.label = "G${index + 1}"
            point.marker.contentDescription = "点位 ${point.marker.label}"
            point.marker.isSelected = selected
            point.marker.markerColor = if (selected) SELECTED_COLOR else STEP_COLOR
            val moved = point.params.x != bounds.left || point.params.y != bounds.top
            point.params.x = bounds.left
            point.params.y = bounds.top
            if (overlays.isShown(point.marker)) {
                if (moved) overlays.update(point.marker, point.params)
            } else overlays.add(point.marker, point.params)
        }
        (points.keys - visible).forEach { id -> points.remove(id)?.let { overlays.remove(it.marker) } }
    }

    private fun place(step: Step, maxX: Int, maxY: Int, reserved: List<Rect>): Rect? {
        if (maxX < 0 || maxY < 0) return null
        val preferredX = (step.x - size / 2).coerceIn(0, maxX)
        val preferredY = (step.y - size / 2).coerceIn(0, maxY)
        val preferred = Rect(preferredX, preferredY, preferredX + size, preferredY + size)
        if (reserved.none { Rect.intersects(it, preferred) }) return preferred

        // 最近的空位会贴着某个障碍边缘，或保留原来的一条轴坐标。
        val xs = (listOf(preferredX, 0, maxX) + reserved.flatMap { listOf(it.left - size, it.right) })
            .filter { it in 0..maxX }.distinct().sortedBy { kotlin.math.abs(it - preferredX) }
        val ys = (listOf(preferredY, 0, maxY) + reserved.flatMap { listOf(it.top - size, it.bottom) })
            .filter { it in 0..maxY }.distinct().sortedBy { kotlin.math.abs(it - preferredY) }
        var closest: Rect? = null
        var bestDistance = Long.MAX_VALUE
        for (y in ys) {
            val dy = (y - preferredY).toLong()
            if (dy * dy >= bestDistance) break
            for (x in xs) {
                val dx = (x - preferredX).toLong()
                val distance = dx * dx + dy * dy
                if (distance >= bestDistance) break
                val candidate = Rect(x, y, x + size, y + size)
                if (reserved.none { Rect.intersects(it, candidate) }) {
                    closest = candidate
                    bestDistance = distance
                    break
                }
            }
        }
        return closest
    }

    fun hide() {
        shownScheme = null
        points.values.forEach { overlays.remove(it.marker) }
        points.clear()
    }

    private companion object {
        const val SELECTED_COLOR = 0xEE00796B.toInt()
    }
}
