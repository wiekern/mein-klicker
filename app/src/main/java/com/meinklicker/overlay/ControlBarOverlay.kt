package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
        fun onStop()
        fun onOpenMain()
        fun onEdit()
        fun onNewScheme()
        fun onSelectScheme(id: String)
    }

    private val params = overlays.newParams(x = overlays.dp(4), y = overlays.screen.height / 4)
    private val buttonSize = overlays.dp(40)

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
    /** 当前方案的循环设置：×N 或 ∞。 */
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

    init {
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
            },
        ))
    }

    val isShown: Boolean get() = overlays.isShown(root)

    fun show() = overlays.add(root, params)

    fun hide() {
        picker.dismiss()
        overlays.remove(root)
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
                state.round == 0 -> "运行"
                scheme?.rotationGroup != null -> "第${state.step}步"
                scheme == null || scheme.loop.infinite -> "第${state.round}轮"
                else -> "${state.round}/${scheme.loop.count}轮"
            }
        }
        loopText.text = scheme?.let { loopBadge(it) } ?: ""
        loopText.visibility = if (collapsed || scheme == null) View.GONE else View.VISIBLE
        runButton.isEnabled = state == RunState.Idle && !scheme?.steps.isNullOrEmpty()
        val armed = state is RunState.Armed || (state is RunState.Running && state.fromTrigger)
        armButton.background = if (armed) circleBg(TRIGGER_COLOR) else null
        armButton.isEnabled = armed || (state == RunState.Idle && !scheme?.triggers.isNullOrEmpty())
        stopButton.isEnabled = state is RunState.Running
        editButton.isEnabled = state == RunState.Idle
        newButton.isEnabled = state == RunState.Idle
        pickButton.isEnabled = state == RunState.Idle
    }

    /** 悬浮栏在屏幕上的矩形（绝对像素），用于运行前的重叠检查。 */
    fun bounds(): Rect? {
        if (!isShown) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
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
