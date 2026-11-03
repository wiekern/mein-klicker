package com.meinklicker

import android.content.Context
import com.meinklicker.data.SchemeRepository
import com.meinklicker.engine.ClickEngine
import com.meinklicker.engine.GestureDispatcher
import com.meinklicker.engine.RunController
import com.meinklicker.service.ClickAccessibilityService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** 手写的依赖容器，在 [App] 中创建，进程内单例。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 进程级主线程作用域，RunController 的 Job 跑在这里。 */
    val scope = MainScope()

    val repository = SchemeRepository(File(appContext.filesDir, "schemes.json"))

    private val _serviceFlow = MutableStateFlow<ClickAccessibilityService?>(null)
    /** 当前在线的无障碍服务实例，null 表示服务未连接。 */
    val serviceFlow: StateFlow<ClickAccessibilityService?> = _serviceFlow.asStateFlow()

    /** 把点击转给当前在线的服务；服务不在线时视为被取消。 */
    private val gestures = object : GestureDispatcher {
        override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean =
            _serviceFlow.value?.gestures?.tap(x, y, durationMs) ?: false
    }

    val runController = RunController(ClickEngine(gestures), scope) { repository.get(it) }

    private val _selectedSchemeId = MutableStateFlow(prefs.getString(KEY_SELECTED, null))
    /** 悬浮栏当前选中的方案。 */
    val selectedSchemeId: StateFlow<String?> = _selectedSchemeId.asStateFlow()

    fun selectScheme(id: String?) {
        _selectedSchemeId.value = id
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    private val _controlBarEnabled = MutableStateFlow(prefs.getBoolean(KEY_CONTROL_BAR, true))
    /** 是否显示悬浮栏；服务观察它来显示或隐藏。 */
    val controlBarEnabled: StateFlow<Boolean> = _controlBarEnabled.asStateFlow()

    fun setControlBarEnabled(enabled: Boolean) {
        _controlBarEnabled.value = enabled
        prefs.edit().putBoolean(KEY_CONTROL_BAR, enabled).apply()
    }

    fun attachService(service: ClickAccessibilityService) {
        _serviceFlow.value = service
    }

    fun detachService(service: ClickAccessibilityService) {
        if (_serviceFlow.compareAndSet(service, null)) runController.reset()
    }

    private companion object {
        const val KEY_SELECTED = "selected_scheme"
        const val KEY_CONTROL_BAR = "control_bar"
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
