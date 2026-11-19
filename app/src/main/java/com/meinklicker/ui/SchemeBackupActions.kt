package com.meinklicker.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.meinklicker.R
import com.meinklicker.container
import com.meinklicker.data.BackupException
import com.meinklicker.data.Scheme
import com.meinklicker.data.SchemeBackup
import com.meinklicker.util.ScreenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.UUID

private const val WECHAT_PACKAGE = "com.tencent.mm"

internal data class BackupActions(
    val busy: Boolean,
    val importFile: () -> Unit,
    val importFromWeChat: () -> Unit,
    val exportFile: (Scheme?) -> Unit,
    val shareToWeChat: (Scheme) -> Unit,
)

@Composable
internal fun rememberBackupActions(incomingUri: Uri?, consumeIncoming: () -> Unit): BackupActions {
    val context = LocalContext.current
    val repository = context.container.repository
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showWeChatImport by rememberSaveable { mutableStateOf(false) }
    var importUri by rememberSaveable { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<List<Scheme>?>(null) }
    var exportSchemeId by rememberSaveable { mutableStateOf<String?>(null) }

    fun perform(errorMessage: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                message = e.message
            } catch (_: Exception) {
                message = errorMessage
            } finally {
                busy = false
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importUri = uri.toString()
    }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SchemeBackup.MIME_TYPE),
    ) { uri ->
        val id = exportSchemeId
        exportSchemeId = null
        if (id != null && uri != null) perform("无法保存备份文件，请重新选择保存位置。") {
            val schemes = if (id.isEmpty()) repository.schemes.value else {
                listOf(repository.get(id) ?: throw BackupException("该方案已删除，无法导出。"))
            }
            withContext(Dispatchers.IO) {
                val bytes = SchemeBackup.encode(schemes)
                val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException()
                stream.use { it.write(bytes) }
            }
            message = "已备份 ${schemes.size} 个方案。"
        }
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            showWeChatImport = false
            importUri = incomingUri.toString()
            consumeIncoming()
        }
    }
    LaunchedEffect(importUri) {
        preview = null
        val uri = importUri?.let(Uri::parse) ?: return@LaunchedEffect
        try {
            preview = withContext(Dispatchers.IO) {
                val stream = context.contentResolver.openInputStream(uri) ?: throw IOException()
                stream.use(SchemeBackup::read)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = (e as? BackupException)?.message ?: "无法读取文件，请重新选择备份文件。"
            importUri = null
        }
    }

    fun dismissImport() {
        importUri = null
        preview = null
    }
    preview?.let { schemes ->
        val screen = ScreenInfo.realSize(context)
        val differentSize = schemes.count { scheme ->
            val panel = scheme.panelCheck?.takeIf { it.enabled }
            scheme.screenWidth != screen.width || scheme.screenHeight != screen.height ||
                (panel != null && (panel.screenWidth != screen.width || panel.screenHeight != screen.height))
        }
        val label = if (schemes.size == 1) "导入「${schemes.single().name.take(80)}」为新方案？"
            else "将 ${schemes.size} 个方案导入为副本？"
        AlertDialog(
            onDismissRequest = { if (!busy) dismissImport() },
            title = { Text("导入方案") },
            text = {
                Text(if (differentSize == 0) "$label\n\n运行前请在目标应用中核对点击位置。" else
                    "$label\n\n其中 $differentSize 个方案的分辨率与本机不同。运行前请重新核对点位和面板检测区域。")
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    perform("无法保存导入的方案，请重试。") {
                        repository.importCopies(schemes)
                        dismissImport()
                        message = "已导入 ${schemes.size} 个方案。"
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { dismissImport() }) { Text("取消") } },
        )
    }
    if (showWeChatImport) {
        AlertDialog(
            onDismissRequest = { showWeChatImport = false },
            title = { Text("从微信导入") },
            text = {
                val appName = stringResource(R.string.app_name)
                Text(
                    "打开会话后，点右上角「⋯」，再点「查找聊天记录 → 文件」。\n\n" +
                        "打开 .mkbackup 附件，用「其他应用打开」选择「$appName」，然后点「导入」。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWeChatImport = false
                    val unavailable = "无法打开微信。可从「备份 → 从文件导入」选择已保存的备份。"
                    try {
                        val weChat = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
                            ?: throw ActivityNotFoundException()
                        context.startActivity(weChat)
                    } catch (_: ActivityNotFoundException) {
                        message = unavailable
                    } catch (_: SecurityException) {
                        message = unavailable
                    }
                }) { Text("打开微信") }
            },
            dismissButton = { TextButton(onClick = { showWeChatImport = false }) { Text("取消") } },
        )
    }
    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("确定") } },
        )
    }

    return BackupActions(
        busy = busy || importUri != null || exportSchemeId != null,
        importFile = {
            try {
                openDocument.launch(arrayOf("*/*"))
            } catch (_: ActivityNotFoundException) {
                message = "未找到可打开文件的应用。"
            }
        },
        importFromWeChat = { showWeChatImport = true },
        exportFile = { scheme ->
            try {
                exportSchemeId = scheme?.id.orEmpty()
                createDocument.launch(backupFilename(scheme))
            } catch (_: ActivityNotFoundException) {
                exportSchemeId = null
                message = "未找到可保存文件的应用。"
            }
        },
        shareToWeChat = { scheme ->
            perform("无法分享方案，可先保存备份，再从微信发送文件。") {
                val uri = withContext(Dispatchers.IO) { stageBackup(context, scheme) }
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(context.contentResolver, backupFilename(scheme), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    context.startActivity(Intent(share).setPackage(WECHAT_PACKAGE))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent.createChooser(share, "分享方案"))
                } catch (_: SecurityException) {
                    context.startActivity(Intent.createChooser(share, "分享方案"))
                }
            }
        },
    )
}

private fun backupFilename(scheme: Scheme?): String {
    val label = if (scheme == null) "全部方案-${DateFormat.format("yyyyMMdd-HHmmss", Date())}" else {
        scheme.name.replace(Regex("[\\p{C}\\\\/:*?\"<>|.]"), "-").trim().take(60).ifBlank { "方案" }
    }
    return "MeinKlicker-$label.${SchemeBackup.EXTENSION}"
}

private fun stageBackup(context: Context, scheme: Scheme): Uri {
    val root = File(context.cacheDir, "scheme-backups")
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { dir ->
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }
    val bytes = SchemeBackup.encode(listOf(scheme))
    val folder = File(root, UUID.randomUUID().toString())
    if (!folder.mkdirs()) throw IOException()
    val file = File(folder, backupFilename(scheme))
    file.writeBytes(bytes)
    return FileProvider.getUriForFile(context, "${context.packageName}.scheme-backups", file)
}
