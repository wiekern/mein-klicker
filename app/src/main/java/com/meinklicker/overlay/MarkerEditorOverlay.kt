package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.meinklicker.data.DEFAULT_DELAY_MS
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.MIN_DELAY_MS
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import java.util.UUID

/**
 * 在目标应用界面上拖动标记设置位置。每个标记一个独立的小窗口，
 * 这样标记以外的区域仍然可以操作下层应用。
 */
class MarkerEditorOverlay(
    private val context: Context,
    private val overlays: OverlayManager,
    private val original: Scheme?,
    private val defaultName: String,
    /** 保存时传入新方案，取消时传 null。 */
    private val onFinish: (Scheme?) -> Unit,
) {
    private inner class Marker(var x: Int, var y: Int, var delayMs: Long, val isTrigger: Boolean) {
        val view = MarkerView(context, if (isTrigger) TRIGGER_COLOR else STEP_COLOR)
        val params = overlays.newParams(width = markerSize, height = markerSize)

        init {
            place()
            var startX = 0
            var startY = 0
            view.setOnTouchListener(DragTouchListener(
                onDragStart = { startX = x; startY = y },
                onDrag = { dx, dy -> moveTo(startX + dx, startY + dy) },
                onClick = { openPanel(this) },
            ))
        }

        fun moveTo(newX: Int, newY: Int) {
            val screen = overlays.screen
            x = newX.coerceIn(0, screen.width - 1)
            y = newY.coerceIn(0, screen.height - 1)
            place()
            overlays.update(view, params)
            if (panelMarker === this) refreshPanel()
        }

        private fun place() {
            params.x = x - markerSize / 2
            params.y = y - markerSize / 2
        }
    }

    private val markerSize = overlays.dp(48)
    private val buttonSize = overlays.dp(40)
    private val screen = overlays.screen

    private var trigger: Marker? = null
    private var triggerRadiusDp = original?.trigger?.radiusDp ?: 40
    private val steps = mutableListOf<Marker>()

    // ---- 编辑栏 ----

    private val toggleTriggerButton = BarButton(context, "", buttonSize) { toggleTrigger() }
    private val removeButton = BarButton(context, "−", buttonSize) { removeLastStep() }
    private val barParams = overlays.newParams(x = overlays.dp(8), y = screen.height * 3 / 4)
    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
        setPadding(overlays.dp(4), overlays.dp(2), overlays.dp(4), overlays.dp(2))
        val barView = this
        addView(TextView(context).apply {
            text = "⠿"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = overlays.dp(28)
            minHeight = buttonSize
            var startX = 0
            var startY = 0
            setOnTouchListener(DragTouchListener(
                onDragStart = { startX = barParams.x; startY = barParams.y },
                onDrag = { dx, dy ->
                    barParams.x = startX + dx
                    barParams.y = startY + dy
                    overlays.update(barView, barParams)
                },
            ))
        })
        addView(BarButton(context, "+", buttonSize) { addStep() })
        addView(removeButton)
        addView(toggleTriggerButton)
        addView(BarButton(context, "保存", buttonSize) { save() })
        addView(BarButton(context, "取消", buttonSize) { finish(null) })
    }

    // ---- 单个标记的参数面板 ----

    private var panelMarker: Marker? = null
    private val panelTitle = panelText(14f)
    private val panelX = panelText(14f)
    private val panelY = panelText(14f)
    private val panelDelay = panelText(14f)
    private val panelParams = overlays.newParams()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
        setPadding(overlays.dp(12), overlays.dp(8), overlays.dp(12), overlays.dp(8))
        addView(panelTitle)
        addView(stepperRow(panelX, listOf(-10, -1, 1, 10)) { d -> panelMarker?.let { it.moveTo(it.x + d, it.y) } })
        addView(stepperRow(panelY, listOf(-10, -1, 1, 10)) { d -> panelMarker?.let { it.moveTo(it.x, it.y + d) } })
        addView(stepperRow(panelDelay, listOf(-100, -10, 10, 100)) { d ->
            panelMarker?.let {
                it.delayMs = (it.delayMs + d).coerceAtLeast(MIN_DELAY_MS)
                refreshPanel()
            }
        })
        addView(BarButton(context, "关闭", buttonSize) { closePanel() })
    }

    fun show() {
        original?.trigger?.let { trigger = Marker(it.x, it.y, it.delayAfterMs, isTrigger = true) }
        original?.steps?.forEach { steps += Marker(it.x, it.y, it.delayBeforeMs, isTrigger = false) }
        trigger?.let { overlays.add(it.view, it.params) }
        steps.forEach { overlays.add(it.view, it.params) }
        relabel()
        overlays.add(bar, barParams)
    }

    fun dismiss() {
        closePanel()
        trigger?.let { overlays.remove(it.view) }
        steps.forEach { overlays.remove(it.view) }
        overlays.remove(bar)
    }

    private fun addStep() {
        // 新标记从屏幕中心开始，逐个错开，避免叠在一起
        val offset = overlays.dp(24) * (steps.size % 6)
        val marker = Marker(screen.width / 2 + offset, screen.height / 2 + offset, DEFAULT_DELAY_MS, false)
        steps += marker
        overlays.add(marker.view, marker.params)
        raiseBar()
        relabel()
    }

    private fun removeLastStep() {
        val marker = steps.removeLastOrNull() ?: return
        if (panelMarker === marker) closePanel()
        overlays.remove(marker.view)
        relabel()
    }

    private fun toggleTrigger() {
        val current = trigger
        if (current != null) {
            if (panelMarker === current) closePanel()
            overlays.remove(current.view)
            trigger = null
        } else {
            val marker = Marker(screen.width / 2, screen.height / 3, DEFAULT_DELAY_MS, true)
            trigger = marker
            overlays.add(marker.view, marker.params)
            raiseBar()
        }
        relabel()
    }

    /** 新加的标记窗口会盖在编辑栏上，重新添加编辑栏让它保持在最上层。 */
    private fun raiseBar() {
        overlays.remove(bar)
        overlays.add(bar, barParams)
    }

    private fun relabel() {
        trigger?.view?.label = "T"
        steps.forEachIndexed { i, m -> m.view.label = "${i + 1}" }
        toggleTriggerButton.text = if (trigger == null) "+T" else "−T"
        removeButton.isEnabled = steps.isNotEmpty()
    }

    private fun save() {
        if (steps.isEmpty()) {
            Toast.makeText(context, "至少需要 1 个步骤", Toast.LENGTH_SHORT).show()
            return
        }
        val scheme = Scheme(
            id = original?.id ?: UUID.randomUUID().toString(),
            name = original?.name ?: defaultName,
            screenWidth = screen.width,
            screenHeight = screen.height,
            trigger = trigger?.let { TriggerConfig(it.x, it.y, triggerRadiusDp, it.delayMs) },
            steps = steps.map { Step(it.x, it.y, it.delayMs) },
            loop = original?.loop ?: LoopConfig(),
            updatedAt = System.currentTimeMillis(),
        )
        finish(scheme)
    }

    private fun finish(result: Scheme?) {
        dismiss()
        onFinish(result)
    }

    private fun openPanel(marker: Marker) {
        panelMarker = marker
        refreshPanel()
        // 面板放在标记下方，靠下时放到上方
        panelParams.x = overlays.dp(16)
        panelParams.y = if (marker.y < screen.height / 2) marker.y + markerSize else {
            (marker.y - markerSize - overlays.dp(240)).coerceAtLeast(0)
        }
        if (overlays.isShown(panel)) overlays.update(panel, panelParams) else overlays.add(panel, panelParams)
    }

    private fun refreshPanel() {
        val m = panelMarker ?: return
        panelTitle.text = if (m.isTrigger) "触发点" else "第 ${steps.indexOf(m) + 1} 步"
        panelX.text = "X ${m.x}"
        panelY.text = "Y ${m.y}"
        panelDelay.text = if (m.isTrigger) "触发后延迟 ${m.delayMs} ms" else "等待 ${m.delayMs} ms"
    }

    private fun closePanel() {
        panelMarker = null
        overlays.remove(panel)
    }

    private fun panelText(size: Float) = TextView(context).apply {
        textSize = size
        setTextColor(Color.WHITE)
        minWidth = overlays.dp(150)
    }

    private fun stepperRow(label: TextView, deltas: List<Int>, onStep: (Int) -> Unit) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, overlays.dp(4), 0, 0)
            addView(label)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                deltas.forEach { d ->
                    addView(BarButton(context, if (d > 0) "+$d" else "$d", buttonSize) { onStep(d) }.apply {
                        textSize = 13f
                        minWidth = overlays.dp(52)
                    })
                }
            })
        }
}
