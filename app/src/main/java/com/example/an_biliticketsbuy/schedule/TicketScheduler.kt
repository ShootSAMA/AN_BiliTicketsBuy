package com.example.an_biliticketsbuy.schedule

import android.util.Log
import com.example.an_biliticketsbuy.ntp.TimeOffsetHolder
import kotlinx.coroutines.*

/**
 * 定时调度器
 *
 * 精确到毫秒级的定时触发，使用 NTP 校准时间。
 * 采用自适应轮询策略：
 * - > 10秒：每 500ms 检查
 * - ≤ 10秒 且 > 1秒：每 50ms 检查
 * - ≤ 1秒：每 10ms 检查
 */
class TicketScheduler {

    private var schedulerJob: Job? = null

    fun start(
        targetTimeMillis: Long,
        scope: CoroutineScope,
        onTick: (Long) -> Unit,
        onTrigger: () -> Unit
    ) {
        schedulerJob?.cancel()
        schedulerJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Scheduler started, target: $targetTimeMillis")

            while (isActive) {
                val now = TimeOffsetHolder.currentTimeMillis()
                val remaining = targetTimeMillis - now

                if (remaining <= 0) {
                    Log.i(TAG, "Target time reached!")
                    withContext(Dispatchers.Main) { onTrigger() }
                    break
                }

                withContext(Dispatchers.Main) { onTick(remaining) }

                val delayMs = when {
                    remaining > 10_000 -> 500L
                    remaining > 1_000 -> 50L
                    else -> 10L
                }
                delay(delayMs)
            }
        }
    }

    fun stop() {
        schedulerJob?.cancel()
        schedulerJob = null
        Log.i(TAG, "Scheduler stopped")
    }

    companion object {
        private const val TAG = "TicketScheduler"
    }
}
