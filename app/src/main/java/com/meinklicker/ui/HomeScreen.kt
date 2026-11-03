package com.meinklicker.ui

import android.util.Size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meinklicker.AppContainer
import com.meinklicker.container
import com.meinklicker.data.DEFAULT_PRESS_MS
import com.meinklicker.data.Scheme
import com.meinklicker.engine.RunState
import com.meinklicker.util.ScreenInfo
import com.meinklicker.util.SystemSettings
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenScheme: (String) -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val schemes by container.repository.schemes.collectAsStateWithLifecycle()
    val selectedId by container.selectedSchemeId.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Scheme?>(null) }
    var deleting by remember { mutableStateOf<Scheme?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("MeinKlicker") }) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("方案", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { container.newScheme(ScreenInfo.realSize(context)) }) {
                        Text("＋ 新建")
                    }
                }
            }
            if (schemes.isEmpty()) {
                item {
                    Text(
                        "还没有方案。新建后，在目标应用上点悬浮栏的 ✎ 拖动标记设置位置。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(schemes, key = { it.id }) { scheme ->
                SchemeCard(
                    scheme = scheme,
                    selected = scheme.id == selectedId,
                    onClick = { onOpenScheme(scheme.id) },
                    onSelect = { container.selectScheme(scheme.id) },
                    onDuplicate = { container.duplicateScheme(scheme) },
                    onRename = { renaming = scheme },
                    onDelete = { deleting = scheme },
                )
            }
        }
    }

    renaming?.let { scheme ->
        var name by remember(scheme.id) { mutableStateOf(scheme.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    container.saveScheme(scheme.copy(name = name.trim()))
                    renaming = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
    deleting?.let { scheme ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除「${scheme.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    container.deleteScheme(scheme.id)
                    deleting = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun StatusCard() {
    val context = LocalContext.current
    val container = context.container
    val service by container.serviceFlow.collectAsStateWithLifecycle()
    val barEnabled by container.controlBarEnabled.collectAsStateWithLifecycle()
    val tapEffect by container.tapEffectEnabled.collectAsStateWithLifecycle()
    val runState by container.runController.state.collectAsStateWithLifecycle()
    // 电池优化状态只能轮询，回到前台时刷新
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    val batteryOk = remember(resumeTick) { SystemSettings.isIgnoringBatteryOptimizations(context) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusRow(
                label = "无障碍服务",
                ok = service != null,
                okText = "已开启",
                badText = "未开启",
                action = "去开启",
            ) { SystemSettings.openAccessibilitySettings(context) }
            StatusRow(
                label = "电池优化",
                ok = batteryOk,
                okText = "已忽略",
                badText = "未忽略",
                action = "去设置",
            ) { SystemSettings.requestIgnoreBatteryOptimizations(context) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自启动 / 后台运行", Modifier.weight(1f))
                TextButton(onClick = { SystemSettings.openAutoStartSettings(context) }) { Text("去设置") }
            }
            Text(
                "ColorOS / OriginOS 会在清理后台时关闭无障碍服务：请允许自启动和后台运行，" +
                    "并在最近任务中下拉锁定 MeinKlicker。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示悬浮栏")
                    Text(
                        when (val s = runState) {
                            RunState.Idle -> "空闲"
                            is RunState.Armed -> "待命中"
                            is RunState.Running -> if (s.round > 0) "运行中 第${s.round}轮" else "运行中"
                        } + " · 在本页自动隐藏",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = barEnabled, onCheckedChange = container::setControlBarEnabled)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("点击特效")
                    Text("运行时在点击位置显示扩散圆圈", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = tapEffect, onCheckedChange = container::setTapEffectEnabled)
            }
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    okText: String,
    badText: String,
    action: String,
    onAction: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Text(
            if (ok) okText else badText,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        if (!ok) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun SchemeCard(
    scheme: Scheme,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    onDuplicate: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (selected) "${scheme.name}（当前）" else scheme.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(scheme.summary(), style = MaterialTheme.typography.bodySmall)
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!selected) {
                        DropdownMenuItem(text = { Text("设为当前") }, onClick = { menu = false; onSelect() })
                    }
                    DropdownMenuItem(text = { Text("复制") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun Scheme.summary(): String {
    val loopText = if (loop.infinite) "无限循环" else "循环 ${loop.count} 次"
    val triggerText = if (trigger != null) "有触发点" else "无触发点"
    val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
    val pressText = if (pressMs != DEFAULT_PRESS_MS) " · 按压 $pressMs ms" else ""
    return "${steps.size} 步 · $triggerText · $loopText$pressText · $time"
}

// ---- 方案操作：写文件在进程级作用域里做，离开页面也不会中断 ----

private fun AppContainer.saveScheme(scheme: Scheme) {
    scope.launch { repository.save(scheme.copy(updatedAt = System.currentTimeMillis())) }
}

private fun AppContainer.newScheme(screen: Size) {
    val scheme = Scheme(
        id = UUID.randomUUID().toString(),
        name = "方案 ${repository.schemes.value.size + 1}",
        screenWidth = screen.width,
        screenHeight = screen.height,
        updatedAt = System.currentTimeMillis(),
    )
    scope.launch {
        repository.save(scheme)
        selectScheme(scheme.id)
    }
}

private fun AppContainer.duplicateScheme(scheme: Scheme) {
    saveScheme(scheme.copy(id = UUID.randomUUID().toString(), name = "${scheme.name} 副本"))
}

private fun AppContainer.deleteScheme(id: String) {
    val state = runController.state.value
    val activeId = (state as? RunState.Armed)?.schemeId ?: (state as? RunState.Running)?.schemeId
    if (activeId == id) runController.reset()
    if (selectedSchemeId.value == id) selectScheme(null)
    scope.launch { repository.delete(id) }
}
