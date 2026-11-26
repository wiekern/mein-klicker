package com.meinklicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meinklicker.data.PanelAction
import com.meinklicker.data.PanelBranch
import com.meinklicker.data.PanelDetection
import com.meinklicker.data.Scheme
import com.meinklicker.data.panelDetectionError

@Composable
internal fun PanelDetectionSummary(
    scheme: Scheme,
    legacyGuardEnabled: Boolean,
    editable: Boolean,
    onRemoveLegacyGuard: () -> Unit,
    onUseExistingRegion: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scheme.panelDetections.isEmpty() && scheme.panelCheck != null) {
            if (legacyGuardEnabled) {
                Text("此方案启用了旧版点击前检测。", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = editable, onClick = onRemoveLegacyGuard) { Text("移除旧版检测") }
            }
            TextButton(enabled = editable, onClick = onUseExistingRegion) { Text("用已有选区添加检测步骤") }
        }
        Text("用悬浮栏 ✎ → 面板检测添加步骤或重新框选。需要 Android 11 或以上。",
            style = MaterialTheme.typography.bodySmall)
        if (scheme.panelDetections.isNotEmpty()) {
            Text("选为 A 或 B 的点击步骤仅在对应分支命中时执行。常规步骤顺序会跳过这些点位。",
                style = MaterialTheme.typography.bodySmall)
            Text("「结束本轮」会跳过余下步骤。有下一轮时，按轮间隔等待。",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private data class DetectionOutcome(val action: PanelAction, val stepId: String = "")

private data class DetectionPosition(val beforeStepId: String? = null, val afterStepId: String? = null)

@Composable
internal fun PanelDetectionSettings(
    scheme: Scheme,
    number: Int,
    detection: PanelDetection,
    detectionDelay: String,
    editable: Boolean,
    onBranchChange: (PanelBranch) -> Unit,
    onDelayChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val branch = detection.branch
    val group = scheme.rotationGroup
    val positions = buildList {
        scheme.steps.forEachIndexed { index, step ->
            add(DetectionPosition(beforeStepId = step.id) to "第 ${index + 1} 步前")
            add(DetectionPosition(afterStepId = step.id) to "第 ${index + 1} 步后")
        }
        add(DetectionPosition() to "步骤末尾")
    }
    val outcomes = buildList {
        add(DetectionOutcome(PanelAction.STOP) to "停止执行")
        add(DetectionOutcome(PanelAction.CONTINUE) to "继续执行")
        add(DetectionOutcome(PanelAction.END_ROUND) to "结束本轮")
        scheme.steps.forEachIndexed { index, step ->
            if (group == null || (step.id !in group.stepIds && step.id != group.commitStepId)) {
                add(DetectionOutcome(PanelAction.CLICK, step.id) to "第 ${index + 1} 步 (${step.x}, ${step.y})")
            }
        }
    }
    val present = DetectionOutcome(branch.presentAction,
        if (branch.presentAction == PanelAction.CLICK) branch.presentStepId else "")
    val absent = DetectionOutcome(branch.absentAction,
        if (branch.absentAction == PanelAction.CLICK) branch.absentStepId else "")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("检测 $number", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(enabled = editable, onClick = onRemove) { Text("移除检测 $number") }
        }
        PanelOption("插入位置", DetectionPosition(branch.beforeStepId, branch.afterStepId), positions, editable) {
            onBranchChange(branch.copy(beforeStepId = it.beforeStepId, afterStepId = it.afterStepId))
        }
        PanelOption("检测到 A", present, outcomes, editable) {
            onBranchChange(branch.copy(presentAction = it.action, presentStepId = it.stepId))
        }
        PanelOption("未检测到 B", absent, outcomes, editable) {
            onBranchChange(branch.copy(absentAction = it.action, absentStepId = it.stepId))
        }
        NumberField("检测前等待 (ms)", detectionDelay, branch.delayBeforeMs < 0,
            "≥ 0", enabled = editable, onChange = onDelayChange)
        scheme.panelDetectionError(detection)?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun <T> PanelOption(label: String, value: T, options: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == value }?.second ?: "请选择"
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty()) {
            Text("$label · $selected")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (option, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    expanded = false
                    onSelect(option)
                })
            }
        }
    }
}
