package com.meinklicker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.filters.SdkSuppress
import com.meinklicker.container
import com.meinklicker.data.Scheme
import com.meinklicker.data.SchemeBackup
import com.meinklicker.data.Step
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class SchemeBackupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val source = Scheme(
        id = "backup-ui-fixture", name = "跨屏备份测试", screenWidth = 1, screenHeight = 1,
        steps = listOf(Step(100, 200, id = "one")), updatedAt = 1,
    )
    private val repository get() = compose.activity.container.repository
    private lateinit var file: File

    @Before fun acceptPrivacy() {
        if (compose.onAllNodes(hasText("同意并进入")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("同意并进入").performClick()
        }
        val cache = File(compose.activity.cacheDir, "scheme-backups/test")
        cache.mkdirs()
        file = File(cache, "fixture.mkbackup")
        file.writeBytes(SchemeBackup.encode(listOf(source)))
    }

    @After fun cleanup() {
        runBlocking {
            repository.schemes.value.filter { it.name == source.name }.forEach { repository.delete(it.id) }
        }
        file.delete()
    }

    @Test fun openingFilePromptsBeforeImportAndDoesNotChangeSelection() {
        importFromExternalApp(file, "application/octet-stream")
    }

    @SdkSuppress(minSdkVersion = 31)
    @Test fun openingChatAttachmentWithDotsInPathPromptsForRestore() {
        val attachmentDir = File(file.parentFile, "Android/data/com.tencent.mm/MicroMsg/Download")
        val attachment = File(attachmentDir, "MeinKlicker.方案(1).mkbackup")
        file.copyTo(attachment, overwrite = true)
        try {
            importFromExternalApp(attachment, "*/*")
        } finally {
            attachment.delete()
        }
    }

    @Test fun resolvesChatAttachmentWithoutDeclaredMimeType() {
        val activity = compose.activity
        val uri = Uri.parse("content://com.meinklicker.fixture-provider/Download/MeinKlicker.mkbackup")
        val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(activity.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(activity.packageManager)?.className)
    }

    @Test fun doesNotAdvertiseImportForUnrelatedAttachments() {
        val activity = compose.activity
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.scheme-backups", File(file.parentFile, "other.pdf"),
        )
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream").setPackage(activity.packageName)
        assertNull(intent.resolveActivity(activity.packageManager))
    }

    private fun importFromExternalApp(file: File, mimeType: String) {
        val activity = compose.activity
        val selected = activity.container.selectedSchemeId.value
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.scheme-backups", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .setPackage(activity.packageName)
        assertEquals(MainActivity::class.java.name, intent.resolveActivity(activity.packageManager)?.className)
        compose.runOnIdle { activity.startActivity(intent) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("分辨率与本机不同", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(repository.schemes.value.none { it.name == source.name })
        compose.onNodeWithText("导入", substring = false).performClick()
        compose.waitUntil(5_000) { repository.schemes.value.any { it.name == source.name } }
        val imported = repository.schemes.value.single { it.name == source.name }
        assertTrue(imported.id != source.id)
        assertEquals(source, imported.copy(id = source.id))
        assertEquals(selected, activity.container.selectedSchemeId.value)
    }
}
