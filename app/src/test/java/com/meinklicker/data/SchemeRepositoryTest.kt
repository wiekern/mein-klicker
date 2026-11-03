package com.meinklicker.data

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SchemeRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val sample = Scheme(
        id = "a", name = "测试", screenWidth = 1080, screenHeight = 2400,
        trigger = TriggerConfig(540, 1800),
        steps = listOf(Step(200, 300), Step(900, 2100, 150)),
        loop = LoopConfig(count = 5, intervalMs = 20),
        updatedAt = 123,
    )

    private fun file() = File(tmp.root, "schemes.json")

    @Test
    fun missingFileStartsEmpty() {
        assertTrue(SchemeRepository(file()).schemes.value.isEmpty())
    }

    @Test
    fun saveAndReloadRoundTrip() = runTest {
        val repo = SchemeRepository(file(), StandardTestDispatcher(testScheduler))
        repo.save(sample)
        repo.save(sample.copy(id = "b", trigger = null, steps = emptyList()))

        val reloaded = SchemeRepository(file())
        assertEquals(repo.schemes.value, reloaded.schemes.value)
        assertEquals(sample, reloaded.get("a"))
        assertTrue(file().readText().contains("\"version\": 1"))
        assertFalse(File(tmp.root, "schemes.json.tmp").exists())
    }

    @Test
    fun saveReplacesById() = runTest {
        val repo = SchemeRepository(file(), StandardTestDispatcher(testScheduler))
        repo.save(sample)
        repo.save(sample.copy(name = "改名"))
        assertEquals(listOf("改名"), repo.schemes.value.map { it.name })
    }

    @Test
    fun delete() = runTest {
        val repo = SchemeRepository(file(), StandardTestDispatcher(testScheduler))
        repo.save(sample)
        repo.delete("a")
        assertNull(repo.get("a"))
        assertTrue(SchemeRepository(file()).schemes.value.isEmpty())
    }

    @Test
    fun corruptFileDoesNotCrashAndIsKept() {
        file().writeText("{ this is not json")
        val repo = SchemeRepository(file())
        assertTrue(repo.schemes.value.isEmpty())
        assertTrue(File(tmp.root, "schemes.json.corrupt").exists())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        file().writeText(
            """{"version":2,"future":true,"schemes":[{"id":"x","name":"n","screenWidth":1,
               |"screenHeight":2,"updatedAt":3,"color":"red"}]}""".trimMargin()
        )
        val s = SchemeRepository(file()).get("x")!!
        assertEquals(LoopConfig(), s.loop)
        assertEquals(DEFAULT_PRESS_MS, s.pressMs)
        assertNull(s.trigger)
    }
}
