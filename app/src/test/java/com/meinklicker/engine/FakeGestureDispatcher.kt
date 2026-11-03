package com.meinklicker.engine

import kotlinx.coroutines.test.TestCoroutineScheduler

/** 记录每次点击的坐标和虚拟时间。 */
class FakeGestureDispatcher(private val scheduler: TestCoroutineScheduler) : GestureDispatcher {
    data class Tap(val x: Int, val y: Int, val atMs: Long)

    val taps = mutableListOf<Tap>()

    override suspend fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        taps += Tap(x, y, scheduler.currentTime)
        return true
    }
}
