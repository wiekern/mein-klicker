package com.meinklicker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import com.meinklicker.data.DEFAULT_PRESS_MS
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.MAX_PRESS_MS
import com.meinklicker.data.MIN_DELAY_MS
import com.meinklicker.data.MIN_PRESS_MS
import com.meinklicker.data.Scheme
import com.meinklicker.data.triggerLabel
import kotlinx.coroutines.CancellationException
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
    var press by remember(scheme.id) { mutableStateOf("${scheme.pressMs}") }
    var showPressTips by remember { mutableStateOf(false) }
    var panelCheckEnabled by remember(scheme.id) { mutableStateOf(scheme.panelCheck?.enabled == true) }
    var saving by remember(scheme.id) { mutableStateOf(false) }
    var saveError by remember(scheme.id) { mutableStateOf<String?>(null) }
    val triggerDelays = remember(scheme.id, scheme.triggers) { mutableStateListOf(*scheme.triggers.map { "${it.delayAfterMs}" }.toTypedArray()) }
    val triggerRadii = remember(scheme.id, scheme.triggers) { mutableStateListOf(*scheme.triggers.map { "${it.radiusDp}" }.toTypedArray()) }
    val stepDelays = remember(scheme.id, scheme.steps) { mutableStateListOf(*scheme.steps.map { "${it.delayBeforeMs}" }.toTypedArray()) }

    val countValue = count.toIntOrNull()?.takeIf { it >= 1 }
    val intervalValue = interval.toLongOrNull()?.takeIf { it >= 0 }
    val pressValue = press.toLongOrNull()?.takeIf { it in MIN_PRESS_MS..MAX_PRESS_MS }
    val triggerDelayValues = triggerDelays.map { it.toLongOrNull()?.takeIf { v -> v >= 0 } }
    val triggerRadiusValues = triggerRadii.map { it.toIntOrNull()?.takeIf { v -> v in 10..200 } }
    val stepDelayValues = stepDelays.map { it.toLongOrNull()?.takeIf { v -> v >= MIN_DELAY_MS } }
    val valid = name.isNotBlank() &&
        (infinite || countValue != null) &&
        intervalValue != null &&
        pressValue != null &&
        triggerDelayValues.all { it != null } &&
        triggerRadiusValues.all { it != null } &&
        stepDelayValues.all { it != null }

    fun save() {
        if (!valid || saving) return
        val updated = scheme.copy(
            name = name.trim(),
            loop = LoopConfig(count = if (infinite) 0 else countValue!!, intervalMs = intervalValue!!),
            pressMs = pressValue!!,
            panelCheck = scheme.panelCheck?.copy(enabled = panelCheckEnabled),
            triggers = scheme.triggers.mapIndexed { i, t ->
                t.copy(delayAfterMs = triggerDelayValues[i]!!, radiusDp = triggerRadiusValues[i]!!)
            },
            steps = scheme.steps.mapIndexed { i, step -> step.copy(delayBeforeMs = stepDelayValues[i]!!) },
            updatedAt = System.currentTimeMillis(),
        )
        saving = true
        container.scope.launch {
            try {
                container.repository.save(updated)
                onBack()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                saveError = "保存失败，请重试。输入的内容已保留。"
            } finally {
                saving = false
            }
        }
    }

    BackHandler(enabled = saving) { }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("方案设置") },
                navigationIcon = { TextButton(enabled = !saving, onClick = onBack) { Text("返回") } },
                // 局部函数引用的相等性不包含捕获值，会让按钮保留旧的校验结果。
                actions = {
                    TextButton(enabled = valid && !saving, onClick = { save() }) {
                        Text(if (saving) "保存中…" else "保存")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text("名称") },
                    singleLine = true, isError = name.isBlank(), modifier = Modifier.fillMaxWidth(),
                )
            }
            item { SectionTitle("点击") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        NumberField("按压时长 (ms)", press, pressValue == null, "$MIN_PRESS_MS–$MAX_PRESS_MS") { press = it }
                    }
                    TextButton(onClick = { showPressTips = true }) { Text("ⓘ") }
                }
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

            item { SectionTitle("面板消失自动停止") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("点击前检测面板", Modifier.weight(1f))
                        Switch(checked = panelCheckEnabled, enabled = scheme.panelCheck != null,
                            onCheckedChange = { panelCheckEnabled = it })
                    }
                    Text(
                        if (scheme.panelCheck == null)
                            "在目标面板打开时，点悬浮栏 ✎ → 面板检测，框选标题等固定特征并保存。需要 Android 11 或以上。"
                        else
                            "每次点击前检查选区。选区变化时先暂停，复查仍不匹配则停止并退出待命；截图失败也停止。检测会降低连点速度，最后截图与点击之间仍可能发生画面变化。重新框选请用悬浮栏 ✎ → 面板检测。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (scheme.triggers.isNotEmpty()) {
                item { SectionTitle(if (scheme.triggers.size == 1) "触发点" else "触发点（点中任意一个开始）") }
            }
            itemsIndexed(scheme.triggers) { i, t ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (scheme.triggers.size > 1) {
                        Text("${triggerLabel(i, scheme.triggers.size)} (${t.x}, ${t.y})", style = MaterialTheme.typography.bodyMedium)
                    }
                    NumberField("触发后延迟 (ms)", triggerDelays[i], triggerDelayValues[i] == null, "≥ 0") {
                        triggerDelays[i] = it
                    }
                    NumberField("触发区半径 (dp)", triggerRadii[i], triggerRadiusValues[i] == null, "10–200") {
                        triggerRadii[i] = it
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

    if (showPressTips) PressTipsDialog(onDismiss = { showPressTips = false })
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            title = { Text("未能保存方案") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun PressTipsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按压时长建议") },
        text = {
            Text(
                "• 默认 $DEFAULT_PRESS_MS ms，适合大多数应用的按钮。\n" +
                    "• 点了没反应（部分游戏、自绘界面）：调到 50–150 ms 再试。\n" +
                    "• 不要超过约 $MAX_PRESS_MS ms，太长可能被识别为长按（系统长按约 400 ms）。\n" +
                    "• 按压时长会计入每一步的耗时：实际间隔 ≈ 等待时长 + 按压时长。\n" +
                    "• 所有步骤和触发点的补发都使用这个值。"
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
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
