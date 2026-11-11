package com.meinklicker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

internal const val PRIVACY_PREFERENCES = "privacy"
internal const val PRIVACY_ACCEPTED_VERSION_KEY = "accepted_version"
internal const val PRIVACY_POLICY_VERSION = "2026-11-11"

@Composable
fun PrivacyConsentScreen(onAgree: () -> Unit, onReject: () -> Unit) {
    var readingPolicy by rememberSaveable { mutableStateOf(false) }
    if (readingPolicy) {
        BackHandler { readingPolicy = false }
        PrivacyPolicyScreen(onBack = { readingPolicy = false })
    } else {
        BackHandler(onBack = onReject)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AlertDialog(
                onDismissRequest = onReject,
                properties = DialogProperties(dismissOnClickOutside = false),
                title = { Text("欢迎使用 MeinKlicker") },
                text = {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("请先阅读《隐私政策》，了解数据处理和权限用途，再由您主动选择是否同意。")
                        Text("方案和设置保存在本机，应用不自动联网。无障碍服务用于自动点击，需您另行在系统设置中开启；可选的面板检测会临时截取窗口或屏幕画面，并在本机保存选区颜色采样，不上传截图。")
                        Text("同意隐私政策不代表授权无障碍或其他可选功能。您可在“关于”中随时查看政策和反馈渠道。")
                        TextButton(onClick = { readingPolicy = true }) { Text("阅读完整隐私政策") }
                    }
                },
                confirmButton = { TextButton(onClick = onAgree) { Text("同意并进入") } },
                dismissButton = { TextButton(onClick = onReject) { Text("不同意并退出") } },
            )
        }
    }
}
