package com.meinklicker.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.meinklicker.container
import com.meinklicker.data.Scheme
import com.meinklicker.data.SchemeBackup
import com.meinklicker.data.Step
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class SchemeBackupScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val source = Scheme(
        id = "backup-ui-fixture", name = "跨屏备份测试", screenWidth = 1, screenHeight = 1,
        steps = listOf(Step(100, 200, id = "one")), updatedAt = 1,
    )
    private val repository get() = compose.activity.container.repository
    private lateinit var file: File
    private lateinit var uri: Uri
    private lateinit var initialIds: Set<String>

    @Before fun setUp() {
        if (compose.onAllNodes(hasText("同意并进入")).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("同意并进入").performClick()
        }
        val activity = compose.activity
        val cache = File(activity.cacheDir, "scheme-backups/test")
        cache.mkdirs()
        file = File(cache, "fixture.json")
        file.writeBytes(byteArrayOf())
        uri = FileProvider.getUriForFile(activity, "${activity.packageName}.scheme-backups", file)
        initialIds = repository.schemes.value.map { it.id }.toSet()
        runBlocking { repository.save(source) }
    }

    @After fun cleanup() {
        runBlocking {
            repository.schemes.value.filter { it.id !in initialIds }
                .forEach { repository.delete(it.id) }
        }
        file.delete()
    }

    @Test fun exportsJsonAndRestoresItFromTheFilePicker() {
        val selected = compose.activity.container.selectedSchemeId.value
        val create = selectFile(Intent.ACTION_CREATE_DOCUMENT, "备份全部方案")
        assertEquals("application/json", create.type)
        assertTrue(create.getStringExtra(Intent.EXTRA_TITLE)?.endsWith(".json") == true)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("已备份", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("确定", substring = false).performClick()
        assertTrue(source in file.inputStream().use(SchemeBackup::read))

        selectFile(Intent.ACTION_OPEN_DOCUMENT, "从文件导入")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("分辨率与本机不同", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(repository.schemes.value.none { it.name == "${source.name}（导入）" })
        compose.onNodeWithText("导入", substring = false).performClick()
        compose.waitUntil(5_000) { repository.schemes.value.any { it.name == "${source.name}（导入）" } }
        val imported = repository.schemes.value.single { it.name == "${source.name}（导入）" }
        assertTrue(imported.id != source.id)
        assertEquals(source, imported.copy(id = source.id, name = source.name))
        assertEquals(selected, compose.activity.container.selectedSchemeId.value)
    }

    private fun selectFile(action: String, menuItem: String): Intent {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launched = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != action) return null
                launched.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("备份", substring = false).performClick()
            compose.onNodeWithText(menuItem, substring = false).performClick()
            compose.waitUntil(5_000) { launched.get() != null }
            return checkNotNull(launched.get())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}
