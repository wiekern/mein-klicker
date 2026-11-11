package com.meinklicker.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import com.meinklicker.R
import kotlinx.coroutines.launch

private const val DEVELOPER_URL = "https://github.com/wiekern"
private const val REPOSITORY_URL = "https://github.com/wiekern/mein-klicker"
private const val ISSUES_URL = "$REPOSITORY_URL/issues"
private const val APP_REGISTRATION_NUMBER = "粤ICP备2025384729号-3A"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showLicense by rememberSaveable { mutableStateOf(false) }
    val version = remember(context) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }

    fun openLink(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("无法打开链接，请先安装浏览器") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(24.dp))
                        .background(colorResource(R.color.ic_launcher_background)),
                )
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                Text(
                    "一个纯粹的自动点击器",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "应用信息",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Card(Modifier.fillMaxWidth()) {
                    AboutInfoRow("当前版本", version, "点击复制") {
                        clipboard.setText(AnnotatedString(version))
                        scope.launch { snackbar.showSnackbar("版本号已复制") }
                    }
                    HorizontalDivider()
                    AboutInfoRow(
                        "APP 备案号", APP_REGISTRATION_NUMBER, "打开工信部备案网站",
                        onCopy = {
                            clipboard.setText(AnnotatedString(APP_REGISTRATION_NUMBER))
                            scope.launch { snackbar.showSnackbar("备案号已复制") }
                        },
                    ) {
                        openLink("https://beian.miit.gov.cn/")
                    }
                    HorizontalDivider()
                    AboutInfoRow("开发者", "Yafei", "打开 GitHub 主页") { openLink(DEVELOPER_URL) }
                    HorizontalDivider()
                    AboutInfoRow("源码仓库", "wiekern/mein-klicker", "打开源码仓库") { openLink(REPOSITORY_URL) }
                    HorizontalDivider()
                    AboutInfoRow("问题与建议", "通过 GitHub 提交问题或建议", "打开 GitHub Issues") { openLink(ISSUES_URL) }
                    HorizontalDivider()
                    AboutInfoRow("开源许可", "MIT", "查看许可证") { showLicense = true }
                }
            }
            Text(
                "© 2026 Yafei",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (showLicense) {
        val license = remember(context) {
            context.resources.openRawResource(R.raw.mit_license).bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("MIT 开源许可") },
            text = {
                SelectionContainer {
                    Text(
                        license,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun AboutInfoRow(
    title: String,
    value: String,
    actionLabel: String,
    onCopy: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(
                    painter = painterResource(R.drawable.ic_content_copy),
                    contentDescription = "复制备案号",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text("›", modifier = Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
