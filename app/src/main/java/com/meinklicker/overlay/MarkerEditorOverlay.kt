package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.meinklicker.data.DEFAULT_DELAY_MS
import com.meinklicker.data.DEFAULT_PRESS_MS
import com.meinklicker.data.DEFAULT_TRIGGER_RADIUS_DP
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.MAX_TRIGGERS
import com.meinklicker.data.MIN_DELAY_MS
import com.meinklicker.data.Scheme
import com.meinklicker.data.PanelCheck
import com.meinklicker.data.Step
import com.meinklicker.data.TriggerConfig
import com.meinklicker.data.triggerLabel
import com.meinklicker.data.reconcileRotation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 在目标应用界面上拖动标记设置位置。每个标记一个独立的小窗口，
 * 这样标记以外的区域仍然可以操作下层应用。相邻步骤之间画连线和箭头表示顺序。
 */
class MarkerEditorOverlay(
    private val context: Context,
    private val overlays: OverlayManager,
    private val original: Scheme?,
    private val defaultName: String,
    private val notify: (String) -> Unit,
    private val onPreviewTap: suspend (x: Int, y: Int, pressMs: Long) -> Boolean,
    private val onCapturePanel: (suspend (Rect) -> PanelCheck)? = null,
    /** 保存时传入新方案，取消时传 null。 */
    private val onFinish: (Scheme?) -> Unit,
) {
    private inner class Marker(
        var x: Int,
        var y: Int,
        var delayMs: Long,
        val isTrigger: Boolean,
        /** 只对触发点有意义；编辑器里不改，在主界面的方案设置页修改。 */
        val radiusDp: Int = DEFAULT_TRIGGER_RADIUS_DP,
        val stepId: String = UUID.randomUUID().toString(),
    ) {
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
            refreshLines()
        }

        private fun place() {
            params.x = x - markerSize / 2
            params.y = y - markerSize / 2
        }
    }

    private val markerSize = overlays.dp(32)
    private val buttonSize = overlays.dp(40)
    private val captionWidth = minOf(overlays.dp(52), (overlays.screen.width - overlays.dp(64)) / 6)
    private val screen = overlays.screen
    private val handler = Handler(Looper.getMainLooper())
    private val previewScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var previewing = false
    private var dismissed = false
    private var panelCheck = original?.panelCheck
    private var regionSelector: PanelRegionOverlay? = null

    private val triggers = mutableListOf<Marker>()
    private val steps = mutableListOf<Marker>()
    private val lines = OrderLinesOverlay(context, overlays)

    // ---- 编辑栏：⠿ │ + 加步骤  🗑 清空 │ 👆 触发点 │ ✓ 保存  ✕ 取消 ----

    private val clearButton = CaptionButton(context, "🗑", CLEAR_CAPTION, captionWidth) { onClearTapped() }
    private val triggerButton = CaptionButton(context, "👆", "触发点", captionWidth) { addTrigger() }
    private val checkButton = CaptionButton(context, "▣", "面板检测", captionWidth) { selectPanelRegion() }
    private var clearArmed = false
    private val disarmClear = Runnable { setClearArmed(false) }

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
        val dividerHeight = overlays.dp(32)
        addView(verticalDivider(context, dividerHeight))
        addView(CaptionButton(context, "+", "加步骤", captionWidth) { addStep() })
        addView(clearButton)
        addView(verticalDivider(context, dividerHeight))
        addView(triggerButton)
        addView(checkButton)
        addView(verticalDivider(context, dividerHeight))
        addView(CaptionButton(context, "✓", "保存", captionWidth) { save() })
        addView(CaptionButton(context, "✕", "取消", captionWidth) { finish(null) })
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
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(BarButton(context, "点击一次", buttonSize) { panelMarker?.let(::previewTap) }.apply {
                textSize = 15f
                minWidth = overlays.dp(80)
            })
            addView(BarButton(context, "删除", buttonSize) { panelMarker?.let(::deleteMarker) }.apply {
                setTextColor(0xFFEF9A9A.toInt())
                textSize = 15f
                minWidth = overlays.dp(72)
            })
            addView(BarButton(context, "关闭", buttonSize) { closePanel() }.apply {
                textSize = 15f
                minWidth = overlays.dp(72)
            })
        })
    }

    fun show() {
        original?.triggers?.forEach { triggers += Marker(it.x, it.y, it.delayAfterMs, isTrigger = true, it.radiusDp) }
        original?.steps?.forEach { steps += Marker(it.x, it.y, it.delayBeforeMs, isTrigger = false, stepId = it.id) }
        // 连线先添加，标记盖在线上面
        lines.show()
        triggers.forEach { overlays.add(it.view, it.params) }
        steps.forEach { overlays.add(it.view, it.params) }
        relabel()
        overlays.add(bar, barParams)
    }

    fun dismiss() {
        dismissed = true
        previewScope.cancel()
        regionSelector?.dismiss()
        regionSelector = null
        handler.removeCallbacks(disarmClear)
        closePanel()
        triggers.forEach { overlays.remove(it.view) }
        steps.forEach { overlays.remove(it.view) }
        overlays.remove(bar)
        lines.dismiss()
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

    private fun deleteMarker(marker: Marker) {
        closePanel()
        overlays.remove(marker.view)
        if (marker.isTrigger) {
            val index = triggers.indexOf(marker)
            val label = triggerLabel(index, triggers.size)
            triggers.remove(marker)
            val renumbered = if (index < triggers.size) "，后面的触发点已重新编号" else ""
            notify(if (label == "T") "已删除触发点" else "已删除触发点 $label$renumbered")
        } else {
            val index = steps.indexOf(marker)
            steps.remove(marker)
            notify("已删除第 ${index + 1} 步，后面的步骤已重新编号")
        }
        relabel()
    }

    /** 清空需要 3 秒内连点两次，防止误触；触发点保留。 */
    private fun onClearTapped() {
        if (steps.isEmpty()) return
        if (!clearArmed) {
            setClearArmed(true)
            notify("再点一次 🗑 清空全部 ${steps.size} 步（触发点保留）")
            handler.postDelayed(disarmClear, 3000)
            return
        }
        handler.removeCallbacks(disarmClear)
        setClearArmed(false)
        val count = steps.size
        if (panelMarker?.isTrigger == false) closePanel()
        steps.forEach { overlays.remove(it.view) }
        steps.clear()
        relabel()
        notify("已清空 $count 步；点 ✕ 取消可恢复")
    }

    private fun setClearArmed(armed: Boolean) {
        clearArmed = armed
        clearButton.caption = if (armed) "再点确认" else CLEAR_CAPTION
        clearButton.background = if (armed) roundedBg(TRIGGER_COLOR, overlays.dp(8).toFloat()) else null
    }

    /** 每点一次加一个触发点；点中任意一个都会开始运行。删除在标记的参数面板里。 */
    private fun addTrigger() {
        if (triggers.size >= MAX_TRIGGERS) {
            notify("最多 $MAX_TRIGGERS 个触发点")
            return
        }
        // 从屏幕上方三分之一处开始逐个错开，跳过已被占用的位置（删掉中间的触发点后再加时）
        val slot = (0 until MAX_TRIGGERS).first { k ->
            val offset = overlays.dp(24) * k
            triggers.none { it.x == screen.width / 2 + offset && it.y == screen.height / 3 + offset }
        }
        val offset = overlays.dp(24) * slot
        val marker = Marker(screen.width / 2 + offset, screen.height / 3 + offset, DEFAULT_DELAY_MS, true)
        triggers += marker
        overlays.add(marker.view, marker.params)
        raiseBar()
        relabel()
        notify(
            if (triggers.size == 1) "已添加触发点（红色 T）：拖到你要亲手点的位置"
            else "已添加触发点 T${triggers.size}：点中任意一个触发点都会开始运行"
        )
    }

    /** 新加的标记窗口会盖在编辑栏上，重新添加编辑栏让它保持在最上层。 */
    private fun raiseBar() {
        overlays.remove(bar)
        overlays.add(bar, barParams)
    }

    private fun relabel() {
        triggers.forEachIndexed { i, m -> m.view.label = triggerLabel(i, triggers.size) }
        val members = steps.filter { it.stepId in original?.rotationGroup?.stepIds.orEmpty() }
        steps.forEachIndexed { i, m ->
            m.view.label = when {
                m in members -> "G${members.indexOf(m) + 1}"
                m.stepId == original?.rotationGroup?.commitStepId -> "${i + 1}✓"
                else -> "${i + 1}"
            }
        }
        // 已有触发点时按钮为红色圆底（与待命按钮、触发区同色）
        triggerButton.background = if (triggers.isNotEmpty()) roundedBg(TRIGGER_COLOR, overlays.dp(8).toFloat()) else null
        checkButton.background = if (panelCheck?.enabled == true) roundedBg(STEP_COLOR, overlays.dp(8).toFloat()) else null
        clearButton.isEnabled = steps.isNotEmpty()
        if (steps.isEmpty()) setClearArmed(false)
        if (panelMarker != null) refreshPanel()
        refreshLines()
    }

    private fun refreshLines() {
        lines.update(triggers.map { Point(it.x, it.y) }, steps.map { Point(it.x, it.y) })
    }

    private fun save() {
        if (steps.isEmpty()) {
            notify("至少需要 1 个步骤")
            return
        }
        val triggers = triggers.map { TriggerConfig(it.x, it.y, it.radiusDp, it.delayMs) }
        val steps = steps.map { Step(it.x, it.y, it.delayMs, id = it.stepId) }
        // 编辑已有方案时保留其他设置（循环、按压时长等）
        val scheme = original?.copy(
            screenWidth = screen.width,
            screenHeight = screen.height,
            triggers = triggers,
            steps = steps,
            panelCheck = panelCheck,
            updatedAt = System.currentTimeMillis(),
        ) ?: Scheme(
            id = UUID.randomUUID().toString(),
            name = defaultName,
            screenWidth = screen.width,
            screenHeight = screen.height,
            triggers = triggers,
            steps = steps,
            loop = LoopConfig(),
            panelCheck = panelCheck,
            updatedAt = System.currentTimeMillis(),
        )
        finish(scheme.reconcileRotation())
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
            (marker.y - markerSize - overlays.dp(280)).coerceAtLeast(0)
        }
        if (overlays.isShown(panel)) overlays.update(panel, panelParams) else overlays.add(panel, panelParams)
    }

    /** 编辑时只点击当前标记一次，方便进入下一步所需界面；不执行循环或后续步骤。 */
    private fun previewTap(marker: Marker) {
        if (previewing || dismissed) return
        previewing = true
        val x = marker.x
        val y = marker.y
        closePanel()
        // 其他标记和编辑栏也可能盖住目标，一起让出触摸区域。
        val windows = (triggers + steps).map { it.view to it.params } + (bar to barParams)
        windows.forEach { (view, params) ->
            view.visibility = View.INVISIBLE
            overlays.setTouchable(view, params, false)
        }
        previewScope.launch {
            try {
                overlays.awaitTouchReleased()
                if (!onPreviewTap(x, y, original?.pressMs ?: DEFAULT_PRESS_MS)) {
                    notify("点击未完成，请重试")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notify("点击失败，请重试")
            } finally {
                if (!dismissed) windows.forEach { (view, params) ->
                    view.visibility = View.VISIBLE
                    overlays.setTouchable(view, params, true)
                }
                previewing = false
            }
        }
    }

    private fun refreshPanel() {
        val m = panelMarker ?: return
        panelTitle.text = if (m.isTrigger) {
            val label = triggerLabel(triggers.indexOf(m), triggers.size)
            if (label == "T") "触发点" else "触发点 $label"
        } else {
            val memberIndex = steps.filter { it.stepId in original?.rotationGroup?.stepIds.orEmpty() }.indexOf(m)
            val suffix = when {
                memberIndex >= 0 -> " · 循环组第 ${memberIndex + 1} 项"
                m.stepId == original?.rotationGroup?.commitStepId -> " · 进度确认"
                else -> ""
            }
            "第 ${steps.indexOf(m) + 1} 步$suffix"
        }
        panelX.text = "X ${m.x}"
        panelY.text = "Y ${m.y}"
        panelDelay.text = if (m.isTrigger) "触发后延迟 ${m.delayMs} ms" else "等待 ${m.delayMs} ms"
    }

    private fun closePanel() {
        panelMarker = null
        overlays.remove(panel)
    }

    private fun selectPanelRegion() {
        if (previewing || dismissed) return
        val capture = onCapturePanel
        if (Build.VERSION.SDK_INT < 30 || capture == null) {
            notify("面板检测需要 Android 11 或以上")
            return
        }
        previewing = true
        closePanel()
        val windows = (triggers + steps).map { it.view to it.params } + (bar to barParams)
        windows.forEach { (view, params) ->
            view.visibility = View.INVISIBLE
            overlays.setTouchable(view, params, false)
        }
        lines.dismiss()
        fun restore() {
            if (!dismissed) {
                lines.show()
                refreshLines()
                windows.forEach { (view, params) ->
                    view.visibility = View.VISIBLE
                    overlays.setTouchable(view, params, true)
                }
                relabel()
            }
            previewing = false
        }
        regionSelector = PanelRegionOverlay(context, overlays) { region ->
            regionSelector = null
            if (region == null) {
                restore()
            } else {
                previewScope.launch {
                    try {
                        overlays.awaitTouchReleased()
                        panelCheck = capture(region)
                        notify("已设置面板检测，保存后生效；区域消失会停止并退出待命")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        notify("未能设置检测：${e.message ?: "无法读取画面"}")
                    } finally {
                        restore()
                    }
                }
            }
        }.also { it.show() }
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

    private companion object {
        const val CLEAR_CAPTION = "清空"
    }
}
