package com.example.an_biliticketsbuy.ntp

import java.util.concurrent.atomic.AtomicLong

/** 全局时间偏移持有者，NTP 同步后写入，倒计时和定时调度时读取 */
object TimeOffsetHolder {
    private val offset = AtomicLong(0L)

    @JvmStatic
    fun setOffset(offsetMillis: Long) {
        offset.set(offsetMillis)
    }

    @JvmStatic
    fun getOffset(): Long = offset.get()

    /** 获取校准后的当前时间戳 */
    @JvmStatic
    fun currentTimeMillis(): Long = System.currentTimeMillis() + offset.get()
}
