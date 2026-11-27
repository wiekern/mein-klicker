package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import com.meinklicker.R
import com.meinklicker.data.Scheme
import com.meinklicker.data.rotationError
import com.meinklicker.data.rotationMembers
import com.meinklicker.engine.RunState

/**
 * 竖向悬浮控制栏。拖顶部把手移动；收起后只保留 ▶ 和 ■ 两个急用按钮。
 * 方案名不常驻显示，在「切换」弹出的列表里查看和切换。
 */
class ControlBarOverlay(
    private val context: Context,
    private val overlays: OverlayManager,
    private val actions: Actions,
) {
    interface Actions {
        fun onRun()
        fun onArm()
        fun onDisarm()
        fun onBarBoundsChanged()
        fun onStop()
        fun onOpenMain()
        fun onEdit()
        fun onNewScheme()
        fun onSelectScheme(id: String)
    }

    private val params = overlays.newParams(x = overlays.dp(4), y = overlays.screen.height / 4)
    private val buttonSize = overlays.dp(40)
    private val dockSize = overlays.dp(48)
    private val dockParams = overlays.newParams(width = dockSize, height = dockSize).apply {
        gravity = Gravity.TOP or Gravity.LEFT
    }
    private val dockButton = BarButton(context, "👆", dockSize) {
        when (state) {
            is RunState.Armed -> actions.onDisarm()
            is RunState.Running -> actions.onStop()
            RunState.Idle -> Unit
        }
    }
    private var dockBounds: Rect? = null

    private val handle = TextView(context).apply {
        text = "⠿"
        textSize = 18f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = buttonSize
        minHeight = overlays.dp(28)
    }
    private val statusText = TextView(context).apply {
        setTextColor(0xFFB0BEC5.toInt())
        textSize = 10f
        gravity = Gravity.CENTER
        isSingleLine = true
        maxWidth = buttonSize + overlays.dp(8)
    }
    /** 当前方案的循环设置、组进度或轮间隔倒计时。 */
    private val loopText = TextView(context).apply {
        setTextColor(0xFF80CBC4.toInt())
        textSize = 10f
        gravity = Gravity.CENTER
        isSingleLine = true
        maxWidth = buttonSize + overlays.dp(8)
    }
    private val runButton = BarButton(context, "▶", buttonSize) { actions.onRun() }
    /** 待命开关：👆 表示"等你先点一下"；待命中红色圆底（与触发区同色），再点退出。 */
    private val armButton = BarButton(context, "👆", buttonSize) {
        if (state == RunState.Idle) actions.onArm() else actions.onDisarm()
    }
    private val stopButton = BarButton(context, "■", buttonSize) { actions.onStop() }
    private val editButton = BarButton(context, "✎", buttonSize) { actions.onEdit() }
    private val newButton = IconBarButton(context, R.drawable.ic_scheme_add, "新建方案", buttonSize) {
        picker.dismiss()
        actions.onNewScheme()
    }
    private val pickButton = IconBarButton(context, R.drawable.ic_scheme_switch, "切换方案", buttonSize) { togglePicker() }
    private val mainButton = IconBarButton(context, R.drawable.ic_open_app, "打开 App 主界面", buttonSize) { actions.onOpenMain() }
    private val collapseButton = BarButton(context, "︽", buttonSize) { setCollapsed(!collapsed) }

    /** 收起时隐藏的部分。 */
    private val secondary = listOf(statusText, loopText, armButton, editButton, newButton, pickButton, mainButton)

    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
        setPadding(overlays.dp(2), overlays.dp(2), overlays.dp(2), overlays.dp(4))
        listOf(handle, statusText, loopText, runButton, armButton, stopButton, editButton, newButton, pickButton, mainButton, collapseButton)
            .forEach(::addView)
    }

    private var state: RunState = RunState.Idle
    private var selectedId: String? = null
    private var schemes: List<Scheme> = emptyList()
    private var collapsed = false
    private val picker = SchemePicker()
    private val handler = Handler(Looper.getMainLooper())
    private val roundWaitTick = Runnable { updateRoundWaitText() }

    init {
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = refreshRoundWait()
            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(roundWaitTick)
            }
        })
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) actions.onBarBoundsChanged()
        }
        var startX = 0
        var startY = 0
        handle.setOnTouchListener(DragTouchListener(
            onDragStart = {
                picker.dismiss()
                startX = params.x
                startY = params.y
            },
            onDrag = { dx, dy ->
                val screen = overlays.screen
                params.x = (startX + dx).coerceIn(0, (screen.width - root.width).coerceAtLeast(0))
                params.y = (startY + dy).coerceIn(0, (screen.height - root.height).coerceAtLeast(0))
                overlays.update(root, params)
                actions.onBarBoundsChanged()
            },
        ))
    }

    val isShown: Boolean get() = overlays.isShown(root) || overlays.isShown(dockButton)

    fun show() {
        if (dockBounds == null) overlays.add(root, params) else overlays.add(dockButton, dockParams)
    }

    fun hide() {
        picker.dismiss()
        overlays.remove(root)
        overlays.remove(dockButton)
    }

    fun render(state: RunState, scheme: Scheme?, schemes: List<Scheme>) {
        this.state = state
        this.selectedId = scheme?.id
        this.schemes = schemes
        if (state != RunState.Idle) picker.dismiss() else picker.refresh()
        statusText.text = when (state) {
            RunState.Idle -> "空闲"
            is RunState.Armed -> "待命"
            is RunState.Running -> when {
                state.roundWait != null -> "等下轮"
                state.round == 0 -> "运行"
                state.checkingPanel -> "检测面板"
                scheme?.rotationGroup != null -> "第${state.step}步"
                scheme == null || scheme.loop.infinite -> "第${state.round}轮"
                else -> "${state.round}/${scheme.loop.count}轮"
            }
        }
        loopText.text = scheme?.let { loopBadge(it) } ?: ""
        val waiting = (state as? RunState.Running)?.roundWait != null
        loopText.visibility = if (!collapsed && (scheme != null || waiting)) View.VISIBLE else View.GONE
        refreshRoundWait()
        runButton.isEnabled = state == RunState.Idle && !scheme?.steps.isNullOrEmpty()
        val armed = state is RunState.Armed || (state is RunState.Running && state.fromTrigger)
        armButton.background = if (armed) circleBg(TRIGGER_COLOR) else null
        armButton.isEnabled = armed || (state == RunState.Idle && !scheme?.triggers.isNullOrEmpty())
        stopButton.isEnabled = state is RunState.Running
        val running = state is RunState.Running
        dockButton.text = if (running) "■" else "👆"
        dockButton.contentDescription = if (running) "停止运行" else "退出待命"
        dockButton.background = circleBg(if (running) BAR_BG else TRIGGER_COLOR)
        editButton.isEnabled = state == RunState.Idle
        newButton.isEnabled = state == RunState.Idle
        pickButton.isEnabled = state == RunState.Idle
    }

    private fun refreshRoundWait() {
        handler.removeCallbacks(roundWaitTick)
        updateRoundWaitText()
    }

    private fun updateRoundWaitText() {
        if (collapsed || !root.isAttachedToWindow) return
        val wait = (state as? RunState.Running)?.roundWait ?: return

        val remainingMs = wait.remainingMs(SystemClock.uptimeMillis())
        val seconds = remainingMs / 1_000 + if (remainingMs % 1_000 == 0L) 0 else 1
        val hours = seconds / 3_600
        val minutes = seconds / 60 % 60
        val secondsText = (seconds % 60).toString().padStart(2, '0')
        val label = when {
            hours >= 100 -> "≥100h"
            hours > 0 -> "$hours:${minutes.toString().padStart(2, '0')}:$secondsText"
            else -> "$minutes:$secondsText"
        }

        if (loopText.text != label) loopText.text = label

        if (remainingMs > 0) {
            val nextTickMs = (remainingMs - 1) % 1_000 + 1
            handler.postDelayed(roundWaitTick, nextTickMs)
        }
    }

    /** 悬浮栏在屏幕上的矩形（绝对像素），用于运行前的重叠检查。 */
    fun bounds(): Rect? {
        if (!isShown) return null
        dockBounds?.let { return it }
        val size = if (root.isLayoutRequested) {
            // 手动收起/展开后可能还没完成下一次布局，按即将显示的尺寸避让。
            val screen = overlays.screen
            root.measure(View.MeasureSpec.makeMeasureSpec(screen.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(screen.height, View.MeasureSpec.AT_MOST))
            root.measuredWidth to root.measuredHeight
        } else root.width to root.height
        return Rect(params.x, params.y, params.x + size.first, params.y + size.second)
    }

    /** 有遮挡时用固定大小的贴边按钮替代原悬浮栏，保留原栏的位置和展开状态。 */
    internal fun avoidArmObstacles(obstacles: List<Rect>): Boolean {
        val current = bounds() ?: return true
        val overlaps = obstacles.any { Rect.intersects(current, it) }
        val wasDocked = dockBounds != null
        if (!overlaps && !wasDocked) return true
        val safeArea = dockSafeArea()
        if (!overlaps && safeArea.contains(current)) return true

        val next = findEdgeDock(safeArea, current, dockSize, obstacles) ?: return false
        if (!wasDocked) hide()
        dockBounds = next
        dockParams.x = next.left
        dockParams.y = next.top
        if (wasDocked) overlays.update(dockButton, dockParams) else show()
        return true
    }

    internal fun restoreAfterArm() {
        if (dockBounds == null) return
        val wasShown = isShown
        overlays.remove(dockButton)
        dockBounds = null
        if (wasShown) show()
    }

    private fun dockSafeArea(): Rect {
        val screen = overlays.screen
        val view = if (dockBounds == null) root else dockButton
        val insets = overlays.windowInsets(view)
        val safe = when {
            insets == null -> Insets.NONE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Insets.toCompatInsets(insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            ))
            else -> WindowInsetsCompat.toWindowInsetsCompat(insets, view).getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
        }
        val margin = overlays.dp(4)
        return Rect(safe.left + margin, safe.top + margin,
            screen.width - safe.right - margin, screen.height - safe.bottom - margin)
    }

    private fun loopBadge(scheme: Scheme): String {
        val group = scheme.rotationGroup
        return if (group != null) {
            if (scheme.rotationError() != null) "组待修复"
            else "组${scheme.rotationMembers().indexOfFirst { it.id == group.nextStepId } + 1}/${group.stepIds.size}"
        } else if (scheme.loop.infinite) "∞" else "×${scheme.loop.count}"
    }

    private fun setCollapsed(value: Boolean) {
        collapsed = value
        if (value) picker.dismiss()
        secondary.forEach { it.visibility = if (value) View.GONE else View.VISIBLE }
        if (!value && loopText.text.isEmpty()) loopText.visibility = View.GONE
        collapseButton.text = if (value) "︾" else "︽"
        refreshRoundWait()
    }

    private fun togglePicker() {
        if (picker.isShown) picker.dismiss() else if (state == RunState.Idle) picker.show()
    }

    /** 方案列表：弹在悬浮栏旁边（悬浮栏靠左时在右侧），点一项切换，最后一项新建。 */
    private inner class SchemePicker {
        private val pickerParams = overlays.newParams()
        private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val view = ScrollView(context).apply {
            background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
            setPadding(overlays.dp(4), overlays.dp(4), overlays.dp(4), overlays.dp(4))
            addView(list)
        }
        private val width = overlays.dp(220)
        private val maxHeight = overlays.dp(320)

        val isShown: Boolean get() = overlays.isShown(view)

        fun show() {
            refresh()
            val bar = bounds() ?: return
            val screen = overlays.screen
            val gap = overlays.dp(4)
            pickerParams.width = width
            pickerParams.x = if (bar.centerX() < screen.width / 2) bar.right + gap else bar.left - gap - width
            pickerParams.y = bar.top.coerceAtMost((screen.height - maxHeight).coerceAtLeast(0))
            pickerParams.height = LayoutParams.WRAP_CONTENT
            overlays.add(view, pickerParams)
            // 内容超过最大高度时限制高度，可滚动
            view.post {
                if (view.height > maxHeight && isShown) {
                    pickerParams.height = maxHeight
                    overlays.update(view, pickerParams)
                }
            }
        }

        fun dismiss() = overlays.remove(view)

        fun refresh() {
            list.removeAllViews()
            schemes.forEach { s ->
                val mark = if (s.id == selectedId) "✓ " else "　 "
                list.addView(item("$mark${s.name}  ${loopBadge(s)}") { dismiss(); actions.onSelectScheme(s.id) })
            }
            list.addView(item("＋ 新建方案") { dismiss(); actions.onNewScheme() })
        }

        private fun item(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            isSingleLine = true
            setPadding(overlays.dp(12), overlays.dp(10), overlays.dp(12), overlays.dp(10))
            setOnClickListener { onClick() }
        }
    }
}
