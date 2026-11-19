package com.meinklicker.overlay

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.meinklicker.R
import com.meinklicker.data.Step

/** 全屏接住触摸，选点时不会把点击传给下层应用。 */
internal class RotationCalibrationOverlay(
    context: Context,
    private val overlays: OverlayManager,
    private val members: List<Step>,
    currentStepId: String,
    private val onConfirm: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private var selected = members.indexOfFirst { it.id == currentStepId }
    private var saving = false
    private val markerSize = overlays.dp(44)
    // 方案保存的是从屏幕左边缘量起的绝对坐标。
    private val markerLayer = FrameLayout(context).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
    private val markers = members.mapIndexed { index, step ->
        MarkerView(context, STEP_COLOR).apply {
            label = labelFor(index)
            contentDescription = "点位 $label"
            setOnClickListener { select(index) }
            markerLayer.addView(this, FrameLayout.LayoutParams(markerSize, markerSize, Gravity.TOP or Gravity.START).apply {
                marginStart = step.x - markerSize / 2
                topMargin = step.y - markerSize / 2
            })
        }
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        gravity = Gravity.CENTER
    }
    private val shortcuts = members.indices.map { index ->
        BarButton(context, labelFor(index), overlays.dp(44)) { select(index) }.apply { textSize = 14f }
    }
    private val shortcutScroll = HorizontalScrollView(context).apply {
        isFillViewport = true
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            shortcuts.forEach { addView(it) }
        })
    }
    private val confirm = BarButton(context, "确认", overlays.dp(60)) { save() }.apply { textSize = 14f }
    private val cancel = BarButton(context, "取消", overlays.dp(60)) { onCancel() }.apply { textSize = 14f }
    private val root = FrameLayout(context).apply {
        isClickable = true
        addView(markerLayer, FrameLayout.LayoutParams(-1, -1))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg(BAR_BG, overlays.dp(12).toFloat())
            setPadding(overlays.dp(8), overlays.dp(8), overlays.dp(8), overlays.dp(4))
            addView(title)
            addView(shortcutScroll, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(context).apply {
                addView(cancel)
                addView(confirm)
            })
        }, FrameLayout.LayoutParams(
            minOf(overlays.dp(280), overlays.screen.width - overlays.dp(24)), -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = overlays.dp(28) })
    }

    fun show() {
        select(selected)
        val screen = overlays.screen
        overlays.add(root, overlays.newParams(width = screen.width, height = screen.height))
    }

    fun dismiss() = overlays.remove(root)

    private fun select(index: Int) {
        if (saving) return
        selected = index
        title.text = title.context.getString(R.string.rotation_calibration_title, labelFor(index))
        markers.forEachIndexed { i, marker ->
            marker.isSelected = i == index
            marker.markerColor = if (i == index) SELECTED_COLOR else STEP_COLOR
        }
        shortcuts.forEachIndexed { i, button ->
            button.isSelected = i == index
            button.background = if (i == index) roundedBg(SELECTED_COLOR, overlays.dp(6).toFloat()) else null
        }
        markers[index].bringToFront()
        val button = shortcuts[index]
        shortcutScroll.post {
            shortcutScroll.smoothScrollTo(button.left + (button.width - shortcutScroll.width) / 2, 0)
        }
    }

    private fun save() {
        if (saving) return
        saving = true
        title.text = title.context.getString(R.string.rotation_calibration_saving, labelFor(selected))
        confirm.isEnabled = false
        cancel.isEnabled = false
        shortcuts.forEach { it.isEnabled = false }
        onConfirm(members[selected].id)
    }

    private fun labelFor(index: Int) = "G${index + 1}"

    private companion object {
        const val SELECTED_COLOR = 0xEE00796B.toInt()
    }
}
