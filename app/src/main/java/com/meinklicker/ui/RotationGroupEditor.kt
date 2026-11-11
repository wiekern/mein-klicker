package com.meinklicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meinklicker.data.RotationGroup
import com.meinklicker.data.Scheme
import com.meinklicker.data.rotationError
import com.meinklicker.data.rotationMembers

@Composable
internal fun RotationGroupEditor(
    scheme: Scheme,
    group: RotationGroup?,
    editable: Boolean,
    onChange: (RotationGroup?, calibrate: Boolean) -> Unit,
) {
    var dialog by remember(scheme.id) { mutableStateOf<String?>(null) }
    val draft = scheme.copy(rotationGroup = group)
    val members = draft.rotationMembers()
    fun label(id: String): String {
        val index = scheme.steps.indexOfFirst { it.id == id }
        return if (index < 0) "未选择" else "第 ${index + 1} 步 (${scheme.steps[index].x}, ${scheme.steps[index].y})"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启用循环组", Modifier.weight(1f))
            Switch(
                checked = group != null,
                enabled = editable,
                modifier = Modifier.semantics { contentDescription = "启用循环组" },
                onCheckedChange = { onChange(if (it) RotationGroup(emptyList(), "", "") else null, false) },
            )
        }
        if (group != null) {
            Text("每次启动仅执行一个组内步骤，组外步骤照常执行。手动触发与 ▶ 共用进度，每次只运行一遍。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = editable, onClick = { dialog = "members" }) {
                Text("选择组内步骤（${members.size} 项）")
            }
            TextButton(enabled = editable, onClick = { dialog = "commit" }) {
                Text("进度确认步骤：${label(group.commitStepId)}")
            }
            val next = members.indexOfFirst { it.id == group.nextStepId }
            TextButton(enabled = editable && members.isNotEmpty(), onClick = { dialog = "next" }) {
                Text(if (next < 0) "下次执行：未选择" else "下次执行：组内第 ${next + 1} 项 · ${label(group.nextStepId)}")
            }
            Text("把确认步骤设为“出征”。该次点击完成后才切换到下一项；游戏未实际派出时，可点“下次执行”手动校准。修改后请保存。",
                style = MaterialTheme.typography.bodySmall)
            draft.rotationError()?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall) }
        }
    }
    if (dialog != null && group != null && editable) {
        val mode = dialog
        val lastMember = scheme.steps.indexOfLast { it.id in group.stepIds }
        val candidates = when (mode) {
            "members" -> scheme.steps
            "commit" -> scheme.steps.filterIndexed { i, step -> i > lastMember && step.id !in group.stepIds }
            else -> members
        }
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(when (mode) {
                "members" -> "选择组内步骤"
                "commit" -> "选择进度确认步骤"
                else -> "校准下次执行项"
            }) },
            text = {
                Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    if (candidates.isEmpty()) Text("没有可选步骤。确认步骤需要在所有组内步骤之后，请先添加或调整步骤。")
                    candidates.forEach { step ->
                        val selected = when (mode) {
                            "members" -> step.id in group.stepIds
                            "commit" -> step.id == group.commitStepId
                            else -> step.id == group.nextStepId
                        }
                        val title = if (mode == "next") "组内第 ${members.indexOf(step) + 1} 项 · ${label(step.id)}"
                            else label(step.id)
                        fun choose() {
                            when (mode) {
                                "members" -> {
                                    val ids = if (selected) group.stepIds - step.id else group.stepIds + step.id
                                    val ordered = scheme.steps.filter { it.id in ids }.map { it.id }
                                    onChange(group.copy(stepIds = ordered,
                                        nextStepId = group.nextStepId.takeIf { it in ordered } ?: ordered.firstOrNull().orEmpty()), false)
                                }
                                "commit" -> { onChange(group.copy(commitStepId = step.id), false); dialog = null }
                                else -> { onChange(group.copy(nextStepId = step.id), true); dialog = null }
                            }
                        }
                        val selection = if (mode == "members") Modifier.toggleable(
                            value = selected, role = Role.Checkbox, onValueChange = { choose() },
                        ) else Modifier.selectable(selected, role = Role.RadioButton, onClick = { choose() })
                        Row(Modifier.fillMaxWidth().then(selection).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (mode == "members") Checkbox(checked = selected, onCheckedChange = null)
                            else RadioButton(selected = selected, onClick = null)
                            Text(title, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("完成") } },
        )
    }
}
