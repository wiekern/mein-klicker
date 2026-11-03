package com.meinklicker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meinklicker.container
import com.meinklicker.data.LoopConfig
import com.meinklicker.data.Scheme
import com.meinklicker.data.Step
import com.meinklicker.util.ScreenInfo
import com.meinklicker.util.SystemSettings
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { ServiceStatus() }
            }
        }
    }
}

@Composable
private fun ServiceStatus() {
    val context = LocalContext.current
    val container = context.container
    val service by container.serviceFlow.collectAsStateWithLifecycle()
    val barEnabled by container.controlBarEnabled.collectAsStateWithLifecycle()
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            if (service != null) "无障碍服务：已开启" else "无障碍服务：未开启",
            style = MaterialTheme.typography.titleLarge,
        )
        if (service == null) {
            Button(onClick = { SystemSettings.openAccessibilitySettings(context) }) { Text("去开启") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示悬浮栏", Modifier.weight(1f))
            Switch(checked = barEnabled, onCheckedChange = container::setControlBarEnabled)
        }
        // TODO(T4): 编辑器完成后删除
        OutlinedButton(onClick = {
            val size = ScreenInfo.realSize(context)
            val scheme = Scheme(
                id = UUID.randomUUID().toString(),
                name = "测试方案",
                screenWidth = size.width,
                screenHeight = size.height,
                steps = listOf(
                    Step(size.width / 4, size.height * 2 / 5, 500),
                    Step(size.width / 2, size.height / 2, 500),
                    Step(size.width * 3 / 4, size.height * 3 / 5, 500),
                ),
                loop = LoopConfig(count = 3, intervalMs = 1000),
                updatedAt = System.currentTimeMillis(),
            )
            container.scope.launch {
                container.repository.save(scheme)
                container.selectScheme(scheme.id)
            }
        }) { Text("生成测试方案") }
    }
}
