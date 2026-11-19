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
    const val EXTENSION = "mkbackup"
    const val MIME_TYPE = "application/vnd.meinklicker.backup+json"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val FORMAT = "meinklicker-scheme-backup"
    private const val VERSION = 1
    private val json = SchemeRepository.json

    fun encode(schemes: List<Scheme>): ByteArray {
        if (schemes.isEmpty()) throw BackupException("没有可导出的方案。")
        return json.encodeToString(BackupFile.serializer(), BackupFile(FORMAT, VERSION, schemes))
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
                throw BackupException("请选择 MeinKlicker 的 .$EXTENSION 方案备份文件。")
            }
            val version = root["version"] as? JsonPrimitive
            if (version == null || version.isString || version.intOrNull == null) throw invalidBackup()
            if (version.intOrNull != VERSION) {
                throw BackupException("当前版本无法导入此格式，请更新 MeinKlicker 后重试。")
            }
            json.decodeFromJsonElement(BackupFile.serializer(), root).schemes.map { it.migrated().withStepIds() }
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

    private fun Scheme.isValidBackupScheme(): Boolean {
        if (name.isBlank() || screenWidth <= 0 || screenHeight <= 0 || pressMs !in MIN_PRESS_MS..MAX_PRESS_MS) return false
        if (loop.count < 0 || loop.intervalMs < 0 || triggers.size > MAX_TRIGGERS) return false
        if (triggers.any { it.delayAfterMs < 0 || it.radiusDp !in MIN_TRIGGER_RADIUS_DP..MAX_TRIGGER_RADIUS_DP }) return false
        return steps.all { it.delayBeforeMs >= MIN_DELAY_MS } && steps.map { it.id }.toSet().size == steps.size
    }
}
