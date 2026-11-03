package com.meinklicker

import android.content.Context
import com.meinklicker.service.ClickAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 手写的依赖容器，在 [App] 中创建，进程内单例。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val _serviceFlow = MutableStateFlow<ClickAccessibilityService?>(null)
    /** 当前在线的无障碍服务实例，null 表示服务未连接。 */
    val serviceFlow: StateFlow<ClickAccessibilityService?> = _serviceFlow.asStateFlow()

    fun attachService(service: ClickAccessibilityService) {
        _serviceFlow.value = service
    }

    fun detachService(service: ClickAccessibilityService) {
        _serviceFlow.compareAndSet(service, null)
    }
}

val Context.container: AppContainer get() = (applicationContext as App).container
