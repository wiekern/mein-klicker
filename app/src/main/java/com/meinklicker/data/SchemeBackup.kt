package com.meinklicker.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException

@Serializable
private data class BackupFile(val format: String, val version: Int, val schemes: List<Scheme>)

internal class BackupException(message: String) : IllegalArgumentException(message)

internal object SchemeBackup {
    const val EXTENSION = "json"
    const val MIME_TYPE = "application/json"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val FORMAT = "meinklicker-scheme-backup"
    private const val VERSION = 1
    private const val PANEL_BRANCH_VERSION = 2
    private const val PANEL_DETECTIONS_VERSION = 3
    private const val PANEL_ACTIONS_VERSION = 4
    private const val END_ROUND_VERSION = 5
    private val json = SchemeRepository.json

    fun encode(schemes: List<Scheme>): ByteArray {
        if (schemes.isEmpty()) throw BackupException("没有可导出的方案。")
        // 旧版忽略分支或操作字段会改变计划的执行行为。
        val version = schemes.maxOf { it.requiredBackupVersion() }
        return json.encodeToString(BackupFile.serializer(), BackupFile(FORMAT, version, schemes))
            .encodeToByteArray().also { checkSize(it.size) }
    }

    fun read(input: InputStream): List<Scheme> {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            checkSize(output.size() + count)
            output.write(buffer, 0, count)
        }
        val schemes = try {
            val text = output.toByteArray().decodeToString(throwOnInvalidSequence = true)
            val root = json.parseToJsonElement(text) as? JsonObject
            if ((root?.get("format") as? JsonPrimitive)?.content != FORMAT) {
                throw BackupException("请选择由 MeinKlicker 导出的方案备份文件。")
            }
            val version = root["version"] as? JsonPrimitive
            if (version == null || version.isString || version.intOrNull == null) throw invalidBackup()
            if (version.intOrNull !in VERSION..END_ROUND_VERSION) {
                throw BackupException("当前版本无法导入此格式，请更新 MeinKlicker 后重试。")
            }
            val backup = json.decodeFromJsonElement(BackupFile.serializer(), root)
            if (backup.schemes.any { it.requiredBackupVersion() > backup.version }) {
                throw invalidBackup()
            }
            backup.schemes.map { it.migrated().withStepIds() }
        } catch (e: BackupException) {
            throw e
        } catch (_: IllegalArgumentException) {
            throw invalidBackup()
        } catch (_: CharacterCodingException) {
            throw invalidBackup()
        }
        if (schemes.isEmpty() || schemes.any { !it.isValidBackupScheme() }) throw invalidBackup()
        return schemes
    }

    private fun checkSize(size: Int) {
        if (size > MAX_BYTES) throw BackupException("备份文件不能超过 4 MB。")
    }

    private fun invalidBackup() = BackupException("备份文件内容不完整或已损坏。")

    private fun Scheme.requiredBackupVersion(): Int {
        val actions = detectionSteps().flatMap { listOf(it.branch.presentAction, it.branch.absentAction) }
        return when {
            PanelAction.END_ROUND in actions -> END_ROUND_VERSION
            actions.any { it != PanelAction.CLICK } -> PANEL_ACTIONS_VERSION
            panelDetections.isNotEmpty() -> PANEL_DETECTIONS_VERSION
            panelBranch != null -> PANEL_BRANCH_VERSION
            else -> VERSION
        }
    }

    private fun Scheme.isValidBackupScheme(): Boolean {
        if (name.isBlank() || screenWidth <= 0 || screenHeight <= 0 || pressMs !in MIN_PRESS_MS..MAX_PRESS_MS) return false
        if (loop.count < 0 || loop.intervalMs < 0 || triggers.size > MAX_TRIGGERS) return false
        val detectionIds = panelDetections.map { it.id }
        if (detectionIds.any { it.isBlank() } || detectionIds.distinct().size != detectionIds.size) return false
        if (panelBranch != null && panelDetections.isNotEmpty()) return false
        if (triggers.any { it.delayAfterMs < 0 || it.radiusDp !in MIN_TRIGGER_RADIUS_DP..MAX_TRIGGER_RADIUS_DP }) return false
        return steps.all { it.delayBeforeMs >= MIN_DELAY_MS } && steps.map { it.id }.toSet().size == steps.size
    }
}
