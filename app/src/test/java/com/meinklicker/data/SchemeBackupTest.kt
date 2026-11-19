package com.meinklicker.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SchemeBackupTest {
    @get:Rule val temp = TemporaryFolder()

    private val scheme = Scheme(
        id = "local-id", name = "我的方案", screenWidth = 1080, screenHeight = 2400,
        triggers = listOf(TriggerConfig(500, 600, radiusDp = 20, delayAfterMs = 0)),
        steps = listOf(Step(10, 20, id = "first"), Step(20, 30, id = "second"), Step(30, 40, id = "commit")),
        loop = LoopConfig(count = 0, intervalMs = 100), pressMs = 80,
        panelCheck = PanelCheck(10, 20, 30, 40, 1080, 2400, "example.app", List(768) { it }),
        rotationGroup = RotationGroup(listOf("first", "second"), "commit", "second"),
        updatedAt = 123,
    )

    @Test fun roundTripPreservesAllSchemeSettings() {
        val schemes = listOf(scheme, scheme.copy(id = "other", name = "空方案", steps = emptyList(), rotationGroup = null))
        assertEquals(schemes, SchemeBackup.read(SchemeBackup.encode(schemes).inputStream()))
    }

    @Test fun rejectsMalformedUnrelatedNewerAndOversizedFiles() {
        val raw = SchemeBackup.encode(listOf(scheme)).decodeToString()
        for (invalid in listOf("{", "{\"schemes\":[]}", raw.replace("\"version\": 1", "\"version\": 2"),
            raw.replace("\"pressMs\": 80", "\"pressMs\": -1"))) {
            assertThrows(BackupException::class.java) { SchemeBackup.read(invalid.byteInputStream()) }
        }
        assertThrows(BackupException::class.java) {
            SchemeBackup.read(ByteArray(SchemeBackup.MAX_BYTES + 1).inputStream())
        }
    }

    @Test fun importingSameBackupAddsCopiesAndPreservesExistingScheme() = runTest {
        val file = File(temp.root, "schemes.json")
        val repository = SchemeRepository(file, StandardTestDispatcher(testScheduler))
        repository.save(scheme)
        val source = SchemeBackup.read(SchemeBackup.encode(listOf(scheme, scheme)).inputStream())
        repository.importCopies(source)

        val reloaded = SchemeRepository(file).schemes.value
        assertEquals(scheme, reloaded.first())
        assertEquals(listOf("我的方案", "我的方案（导入）", "我的方案（导入 2）"), reloaded.map { it.name })
        assertEquals(3, reloaded.map { it.id }.distinct().size)
        assertTrue(reloaded.drop(1).all { it.copy(id = scheme.id, name = scheme.name) == scheme })
    }
}
