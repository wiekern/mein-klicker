package com.meinklicker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 坐标一律为屏幕绝对像素，与 dispatchGesture 的坐标系一致。 */
@Serializable
data class Scheme(
    val id: String,
    val name: String,
    /** 创建时的真实屏幕像素，运行时不一致则提示。 */
    val screenWidth: Int,
    val screenHeight: Int,
    /** 点中任意一个都开始运行；最多 [MAX_TRIGGERS] 个。 */
    val triggers: List<TriggerConfig> = emptyList(),
    /** 旧版本的单个触发点，只在读取旧文件时出现；加载时迁移到 [triggers]。 */
    @SerialName("trigger") val legacyTrigger: TriggerConfig? = null,
    val steps: List<Step> = emptyList(),
    val loop: LoopConfig = LoopConfig(),
    /** 每次点击的按压时长，步骤和触发补发共用。旧文件没有该字段时取默认值。 */
    val pressMs: Long = DEFAULT_PRESS_MS,
    val updatedAt: Long,
)

@Serializable
data class Step(val x: Int, val y: Int, val delayBeforeMs: Long = DEFAULT_DELAY_MS)

@Serializable
data class TriggerConfig(
    val x: Int,
    val y: Int,
    val radiusDp: Int = 40,
    /** 补发点击后到第一个步骤的等待。 */
    val delayAfterMs: Long = DEFAULT_DELAY_MS,
)

@Serializable
data class LoopConfig(
    /** 0 = 无限循环。 */
    val count: Int = 1,
    val intervalMs: Long = 0,
) {
    val infinite: Boolean get() = count == 0
}

const val DEFAULT_DELAY_MS = 100L
const val MIN_DELAY_MS = 10L
const val DEFAULT_PRESS_MS = 30L
const val MIN_PRESS_MS = 10L
/** 低于系统长按阈值（约 400 ms），避免被识别为长按。 */
const val MAX_PRESS_MS = 300L
const val MAX_TRIGGERS = 5

/** 触发点在标记和提示里的名字：只有 1 个时叫 T，多个时叫 T1、T2…。 */
fun triggerLabel(index: Int, count: Int): String = if (count == 1) "T" else "T${index + 1}"

/** 把旧版本的单个触发点迁移到 [Scheme.triggers]。 */
internal fun Scheme.migrated(): Scheme = when {
    legacyTrigger == null -> this
    triggers.isEmpty() -> copy(triggers = listOf(legacyTrigger), legacyTrigger = null)
    else -> copy(legacyTrigger = null)
}

/** schemes.json 的顶层结构，version 为以后的格式升级预留。 */
@Serializable
internal data class SchemeFile(
    val version: Int = SchemeRepository.FILE_VERSION,
    val schemes: List<Scheme> = emptyList(),
)
