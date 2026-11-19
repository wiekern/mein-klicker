package com.meinklicker.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 方案存储：一个 JSON 文件。构造时同步读取（文件很小），写入先写临时文件再原子替换。
 * 文件损坏时不崩溃：把坏文件改名为 `.corrupt` 保留下来，从空列表开始。
 */
class SchemeRepository(
    private val file: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val _schemes = MutableStateFlow(load())
    val schemes: StateFlow<List<Scheme>> = _schemes.asStateFlow()

    fun get(id: String): Scheme? = _schemes.value.find { it.id == id }

    /** 按 id 新增或替换。 */
    suspend fun save(scheme: Scheme, overrideRotationProgress: Boolean = false) = update { list ->
        val index = list.indexOfFirst { it.id == scheme.id }
        var updated = scheme.withStepIds()
        val previous = list.getOrNull(index)?.rotationGroup
        val group = updated.rotationGroup
        if (!overrideRotationProgress && previous != null && group != null && previous.nextStepId in group.stepIds) {
            updated = updated.copy(rotationGroup = group.copy(nextStepId = previous.nextStepId))
        }
        if (index < 0) list + updated else list.toMutableList().also { it[index] = updated }
    }

    /** 只推进本轮启动时的组，绝不使用运行快照覆盖其他设置或恢复已删除的方案。 */
    suspend fun advanceRotation(snapshot: Scheme) = update { list ->
        val expected = snapshot.rotationGroup ?: return@update list
        list.map { current ->
            val group = current.rotationGroup
            if (current.id != snapshot.id || group != expected) current else {
                val members = current.rotationMembers()
                val index = members.indexOfFirst { it.id == expected.nextStepId }
                check(index >= 0 && current.rotationError() == null) { "循环组配置已改变，请检查下次执行项" }
                current.copy(rotationGroup = group.copy(nextStepId = members[(index + 1) % members.size].id))
            }
        }
    }

    /** 按用户看到的组和点位校准进度，保留最新方案的其余设置。 */
    suspend fun calibrateRotation(snapshot: Scheme, stepId: String) = update { list ->
        val index = list.indexOfFirst { it.id == snapshot.id }
        val current = list.getOrNull(index)
        val group = current?.rotationGroup
        val expected = snapshot.rotationGroup
        check(current != null && group != null && expected != null && group.stepIds == expected.stepIds &&
            group.commitStepId == expected.commitStepId && current.rotationError() == null &&
            current.rotationMembers() == snapshot.rotationMembers()) {
            "循环组或点位已改变，请重新选择"
        }
        check(stepId in group.stepIds) { "请选择一个组内点位" }
        if (stepId == group.nextStepId) return@update list
        list.toMutableList().apply {
            this[index] = current.copy(rotationGroup = group.copy(nextStepId = stepId))
        }
    }

    suspend fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** 合并备份中的副本，保留步骤 ID 和轮转进度，不覆盖已有方案。 */
    internal suspend fun importCopies(schemes: List<Scheme>) = update { list ->
        val usedNames = list.mapTo(mutableSetOf()) { it.name }
        val usedIds = list.mapTo(mutableSetOf()) { it.id }
        list + schemes.map { scheme ->
            var name = scheme.name
            if (!usedNames.add(name)) {
                name = "${scheme.name}（导入）"
                var suffix = 2
                while (!usedNames.add(name)) name = "${scheme.name}（导入 ${suffix++}）"
            }
            var id: String
            do { id = UUID.randomUUID().toString() } while (!usedIds.add(id))
            scheme.copy(id = id, name = name)
        }
    }

    private suspend fun update(transform: (List<Scheme>) -> List<Scheme>) = mutex.withLock {
        val newList = transform(_schemes.value)
        if (newList != _schemes.value) {
            // 写盘成功后，即使界面已退出也要同步更新内存中的列表。
            withContext(NonCancellable) {
                withContext(ioDispatcher) { write(newList) }
                _schemes.value = newList
            }
        }
    }

    private fun load(): List<Scheme> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<SchemeFile>(file.readText()).schemes.map { it.migrated().withStepIds() }
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, file.name + ".corrupt"))
            emptyList()
        }
    }

    private fun write(list: List<Scheme>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(SchemeFile.serializer(), SchemeFile(schemes = list)))
        Files.move(
            tmp.toPath(), file.toPath(),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
        )
    }

    companion object {
        const val FILE_VERSION = 1

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
