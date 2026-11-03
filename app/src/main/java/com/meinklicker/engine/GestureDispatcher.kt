package com.meinklicker.engine

interface GestureDispatcher {
    /** 单击，挂起直到手势完成；返回 false 表示被系统取消。 */
    suspend fun tap(x: Int, y: Int, durationMs: Long = 30): Boolean
}
