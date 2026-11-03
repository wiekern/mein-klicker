package com.meinklicker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meinklicker.container
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.MIN_DELAY_MS
import com.meinklicker.data.Scheme
import kotlinx.coroutines.launch

/** 方案参数设置。位置不在这里设置，在目标应用上用悬浮栏的 ✎ 拖动设置。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchemeDetailScreen(scheme: Scheme, onBack: () -> Unit) {
    val container = LocalContext.current.container
    // 文本框里的值都先按字符串编辑，保存时统一校验
    var name by remember(scheme.id) { mutableStateOf(scheme.name) }
    var infinite by remember(scheme.id) { mutableStateOf(scheme.loop.infinite) }
    var count by remember(scheme.id) { mutableStateOf(if (scheme.loop.infinite) "1" else "${scheme.loop.count}") }
    var interval by remember(scheme.id) { mutableStateOf("${scheme.loop.intervalMs}") }
    var triggerDelay by remember(scheme.id, scheme.trigger) { mutableStateOf("${scheme.trigger?.delayAfterMs ?: ""}") }
    var triggerRadius by remember(scheme.id, scheme.trigger) { mutableStateOf("${scheme.trigger?.radiusDp ?: ""}") }
    val stepDelays = remember(scheme.id, scheme.steps) { mutableStateListOf(*scheme.steps.map { "${it.delayBeforeMs}" }.toTypedArray()) }

    val countValue = count.toIntOrNull()?.takeIf { it >= 1 }
    val intervalValue = interval.toLongOrNull()?.takeIf { it >= 0 }
    val triggerDelayValue = triggerDelay.toLongOrNull()?.takeIf { it >= 0 }
    val triggerRadiusValue = triggerRadius.toIntOrNull()?.takeIf { it in 10..200 }
    val stepDelayValues = stepDelays.map { it.toLongOrNull()?.takeIf { v -> v >= MIN_DELAY_MS } }
    val valid = name.isNotBlank() &&
        (infinite || countValue != null) &&
        intervalValue != null &&
        (scheme.trigger == null || (triggerDelayValue != null && triggerRadiusValue != null)) &&
        stepDelayValues.all { it != null }

    fun save() {
        val updated = scheme.copy(
            name = name.trim(),
            loop = LoopConfig(count = if (infinite) 0 else countValue!!, intervalMs = intervalValue!!),
            trigger = scheme.trigger?.copy(delayAfterMs = triggerDelayValue!!, radiusDp = triggerRadiusValue!!),
            steps = scheme.steps.mapIndexed { i, step -> step.copy(delayBeforeMs = stepDelayValues[i]!!) },
            updatedAt = System.currentTimeMillis(),
        )
        container.scope.launch { container.repository.save(updated) }
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(enabled = valid, onClick = ::save) { Text("保存") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("名称") },
                    singleLine = true, isError = name.isBlank(), modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SectionTitle("循环") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("无限循环", Modifier.weight(1f))
                    Switch(checked = infinite, onCheckedChange = { infinite = it })
                }
            }
            if (!infinite) {
                item { NumberField("循环次数", count, countValue == null, "≥ 1") { count = it } }
            }
            item { NumberField("轮间隔 (ms)", interval, intervalValue == null, "≥ 0") { interval = it } }

            if (scheme.trigger != null) {
                item { SectionTitle("触发点") }
                item {
                    NumberField("触发后延迟 (ms)", triggerDelay, triggerDelayValue == null, "≥ 0") { triggerDelay = it }
                }
                item {
                    NumberField("触发区半径 (dp)", triggerRadius, triggerRadiusValue == null, "10–200") {
                        triggerRadius = it
                    }
                }
            }

            item { SectionTitle("步骤（执行前等待）") }
            if (scheme.steps.isEmpty()) {
                item { Text("还没有步骤。请在目标应用上点悬浮栏的 ✎ 添加。", style = MaterialTheme.typography.bodyMedium) }
            }
            itemsIndexed(scheme.steps) { i, step ->
                NumberField(
                    "第 ${i + 1} 步 (${step.x}, ${step.y}) 等待 ms",
                    stepDelays[i],
                    stepDelayValues[i] == null,
                    "≥ $MIN_DELAY_MS",
                ) { stepDelays[i] = it }
            }
            item { HorizontalDivider() }
            item {
                Text(
                    "位置请在目标应用上用悬浮栏的 ✎ 拖动标记设置。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun NumberField(label: String, value: String, isError: Boolean, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit)) },
        label = { Text(label) },
        supportingText = if (isError) ({ Text(hint) }) else null,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
