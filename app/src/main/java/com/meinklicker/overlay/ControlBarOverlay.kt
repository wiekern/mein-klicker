package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.view.WindowManager.LayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.meinklicker.data.Scheme
import com.meinklicker.engine.RunState

/** 悬浮控制栏：可拖动（拖左侧把手），点把手收起为小圆钮，点圆钮展开。 */
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

    private val params = overlays.newParams(x = overlays.dp(8), y = overlays.screen.height / 4)
    private val buttonSize = overlays.dp(40)

    private val nameText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        maxWidth = overlays.dp(120)
        isSingleLine = true
    }
    private val statusText = TextView(context).apply {
        setTextColor(0xFFB0BEC5.toInt())
        textSize = 11f
        isSingleLine = true
    }
    private val runButton = BarButton(context, "▶", buttonSize) { actions.onRun() }
    private val armButton = BarButton(context, "⚡", buttonSize) {
        if (state == RunState.Idle) actions.onArm() else actions.onDisarm()
    }
    private val stopButton = BarButton(context, "■", buttonSize) { actions.onStop() }
    private val editButton = BarButton(context, "✎", buttonSize) { actions.onEdit() }
    private val mainButton = BarButton(context, "≡", buttonSize) { actions.onOpenMain() }

    private val handle = TextView(context).apply {
        text = "⠿"
        textSize = 20f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        minWidth = overlays.dp(28)
        minHeight = buttonSize
    }

    private val expanded = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
        setPadding(overlays.dp(4), overlays.dp(2), overlays.dp(6), overlays.dp(2))
        addView(handle)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(overlays.dp(2), 0, overlays.dp(6), 0)
            addView(nameText)
            addView(statusText)
            // 点方案名弹出方案列表
            setOnClickListener { togglePicker() }
        })
        addView(runButton)
        addView(armButton)
        addView(stopButton)
        addView(editButton)
        addView(mainButton)
    }

    private val collapsed = TextView(context).apply {
        text = "MK"
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = circleBg(BAR_BG)
        visibility = View.GONE
    }

    private val root = FrameLayout(context).apply {
        addView(expanded)
        addView(collapsed, FrameLayout.LayoutParams(overlays.dp(44), overlays.dp(44)))
    }

    init {
        val drag = dragListener(onClick = { setCollapsed(true) })
        handle.setOnTouchListener(drag)
        collapsed.setOnTouchListener(dragListener(onClick = { setCollapsed(false) }))
    }

    private var state: RunState = RunState.Idle
    private var schemes: List<Scheme> = emptyList()
    private val picker = SchemePicker()

    val isShown: Boolean get() = overlays.isShown(root)

    fun show() = overlays.add(root, params)

    fun hide() {
        picker.dismiss()
        overlays.remove(root)
    }

    fun render(state: RunState, scheme: Scheme?, schemes: List<Scheme>) {
        this.state = state
        this.schemes = schemes
        if (state != RunState.Idle) picker.dismiss() else picker.refresh()
        nameText.text = (scheme?.name ?: "未选择方案") + " ▾"
        statusText.text = when (state) {
            RunState.Idle -> "空闲"
            is RunState.Armed -> "待命中"
            is RunState.Running -> when {
                state.round == 0 -> "运行中"
                scheme == null || scheme.loop.infinite -> "运行中 第${state.round}轮 第${state.step}步"
                else -> "运行中 ${state.round}/${scheme.loop.count}轮 第${state.step}步"
            }
        }
        runButton.isEnabled = state == RunState.Idle && !scheme?.steps.isNullOrEmpty()
        val armed = state is RunState.Armed || (state is RunState.Running && state.fromTrigger)
        armButton.text = if (armed) "✕" else "⚡"
        armButton.isEnabled = armed || (state == RunState.Idle && scheme?.trigger != null)
        stopButton.isEnabled = state is RunState.Running
        editButton.isEnabled = state == RunState.Idle
    }

    /** 悬浮栏在屏幕上的矩形（绝对像素），用于运行前的重叠检查。 */
    fun bounds(): Rect? {
        if (!isShown) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    private fun togglePicker() {
        if (picker.isShown) picker.dismiss() else if (state == RunState.Idle) picker.show()
    }

    /** 方案列表：显示在悬浮栏下方，点一项切换，最后一项新建。只在空闲时可用。 */
    private inner class SchemePicker {
        private val pickerParams = overlays.newParams()
        private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val view = ScrollView(context).apply {
            background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
            setPadding(overlays.dp(4), overlays.dp(4), overlays.dp(4), overlays.dp(4))
            addView(list)
        }

        val isShown: Boolean get() = overlays.isShown(view)

        fun show() {
            refresh()
            val bar = bounds() ?: return
            val screen = overlays.screen
            pickerParams.x = bar.left
            pickerParams.height = LayoutParams.WRAP_CONTENT
            val maxHeight = overlays.dp(320)
            if (bar.bottom + maxHeight < screen.height) {
                pickerParams.y = bar.bottom
            } else {
                pickerParams.y = (bar.top - maxHeight).coerceAtLeast(0)
                pickerParams.height = maxHeight
            }
            overlays.add(view, pickerParams)
        }

        fun dismiss() = overlays.remove(view)

        fun refresh() {
            list.removeAllViews()
            schemes.forEach { s ->
                list.addView(item(s.name) { dismiss(); actions.onSelectScheme(s.id) })
            }
            list.addView(item("＋ 新建方案") { dismiss(); actions.onNewScheme() })
        }

        private fun item(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
            minWidth = overlays.dp(180)
            maxWidth = overlays.dp(260)
            isSingleLine = true
            setPadding(overlays.dp(12), overlays.dp(10), overlays.dp(12), overlays.dp(10))
            setOnClickListener { onClick() }
        }
    }

    private fun setCollapsed(value: Boolean) {
        if (value) picker.dismiss()
        expanded.visibility = if (value) View.GONE else View.VISIBLE
        collapsed.visibility = if (value) View.VISIBLE else View.GONE
    }

    private fun dragListener(onClick: () -> Unit): DragTouchListener {
        var startX = 0
        var startY = 0
        return DragTouchListener(
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
            onClick = onClick,
        )
    }
}
