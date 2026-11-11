package com.meinklicker.util

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.meinklicker.TAG
import com.meinklicker.service.ClickAccessibilityService

/** 无障碍、电池优化、厂商自启动页的检测与跳转。 */
object SystemSettings {

    fun openAccessibilitySettings(context: Context) {
        val component = ComponentName(context, ClickAccessibilityService::class.java)
        // 系统服务详情入口未公开为 SDK 常量，部分厂商也未提供对应 Activity。
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
        if (start(context, details)) return

        // 支持这些参数的系统设置会滚动到服务条目并高亮。
        val serviceKey = component.flattenToString()
        val arguments = Bundle().apply {
            putString(":settings:fragment_args_key", serviceKey)
        }
        val settings = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", serviceKey)
            .putExtra(":settings:show_fragment_args", arguments)
        start(context, settings)
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (!start(context, request)) start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** 依次尝试 ColorOS / OriginOS / 一加 的自启动管理页，都打不开时退回应用详情页。 */
    fun openAutoStartSettings(context: Context) {
        val opened = AUTO_START_COMPONENTS.any { (pkg, cls) ->
            start(context, Intent().setComponent(ComponentName(pkg, cls)))
        }
        if (!opened) openAppDetails(context)
    }

    fun openAppDetails(context: Context) {
        start(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}")),
        )
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        // ActivityNotFoundException 或厂商页未导出导致的 SecurityException
        Log.d(TAG, "cannot open ${intent.component ?: intent.action}: ${e.javaClass.simpleName}")
        false
    }

    private val AUTO_START_COMPONENTS = listOf(
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    )
}
