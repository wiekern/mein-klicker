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
import com.meinklicker.util.SystemSettings

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
    }
}
