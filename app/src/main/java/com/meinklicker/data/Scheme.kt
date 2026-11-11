package com.meinklicker.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

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
    /** 可选的面板存在检测；旧方案默认不检测。 */
    val panelCheck: PanelCheck? = null,
    val rotationGroup: RotationGroup? = null,
    val updatedAt: Long,
)

@Serializable
data class PanelCheck(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val packageName: String,
    val colors: List<Int>,
    val enabled: Boolean = true,
)

@Serializable
data class Step(
    val x: Int,
    val y: Int,
    val delayBeforeMs: Long = DEFAULT_DELAY_MS,
    val id: String = "",
)

@Serializable
data class RotationGroup(
    val stepIds: List<String>,
    val commitStepId: String,
    val nextStepId: String,
)

/** 组内顺序始终跟随方案步骤顺序。 */
fun Scheme.rotationMembers(): List<Step> = steps.filter { it.id in rotationGroup?.stepIds.orEmpty() }

fun Scheme.rotationError(): String? {
    val group = rotationGroup ?: return null
    val members = rotationMembers()
    return when {
        group.stepIds.size < 2 -> "循环组至少需要 2 个步骤，请重新选择组内步骤"
        group.stepIds.distinct().size != group.stepIds.size || members.size != group.stepIds.size ->
            "循环组包含已删除或重复的步骤，请重新选择组内步骤"
        group.nextStepId !in group.stepIds -> "请指定下次执行的组内步骤"
        group.commitStepId in group.stepIds || steps.none { it.id == group.commitStepId } ->
            "请选择组外的进度确认步骤（例如出征）"
        steps.indexOfFirst { it.id == group.commitStepId } <= steps.indexOfLast { it.id in group.stepIds } ->
            "进度确认步骤必须位于所有组内步骤之后"
        else -> null
    }
}

/** 为旧方案及新增步骤分配稳定标识，不依赖坐标或当前显示编号。 */
internal fun Scheme.withStepIds(): Scheme {
    val used = steps.map { it.id }.filter { it.isNotBlank() }.toMutableSet()
    return copy(steps = steps.mapIndexed { index, step ->
        if (step.id.isNotBlank()) step else {
            var salt = 0
            var stepId: String
            do {
                stepId = UUID.nameUUIDFromBytes("$id:step:$index:${salt++}".toByteArray(Charsets.UTF_8)).toString()
            } while (!used.add(stepId))
            step.copy(id = stepId)
        }
    })
}

/** 悬浮编辑器删点后保留剩余成员身份；确认步骤被删时保留无效状态，要求重新指定。 */
fun Scheme.reconcileRotation(): Scheme {
    val group = rotationGroup ?: return this
    if (steps.isEmpty()) return copy(rotationGroup = null)
    val members = rotationMembers()
    return copy(rotationGroup = group.copy(
        stepIds = members.map { it.id },
        nextStepId = group.nextStepId.takeIf { id -> members.any { it.id == id } }
            ?: members.firstOrNull()?.id.orEmpty(),
    ))
}

@Serializable
data class TriggerConfig(
    val x: Int,
    val y: Int,
    val radiusDp: Int = DEFAULT_TRIGGER_RADIUS_DP,
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
/** 旧数据缺少半径时仍用原尺寸；新建触发点使用 [NEW_TRIGGER_RADIUS_DP]。 */
const val DEFAULT_TRIGGER_RADIUS_DP = 40
const val NEW_TRIGGER_RADIUS_DP = 20
const val MIN_TRIGGER_RADIUS_DP = 10
const val MAX_TRIGGER_RADIUS_DP = 200

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
