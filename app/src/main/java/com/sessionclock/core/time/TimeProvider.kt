package com.sessionclock.core.time

import android.os.SystemClock

/**
 * # 时间源抽象
 *
 * 项目内存在两套不可混用的时间语义（技术方案「双时间源」决策）：
 * - [elapsedRealtimeMs]：开机以来的单调时间（[SystemClock.elapsedRealtime]），
 *   不受用户改系统时钟、时区影响，**秒表累加 / 180s 容差 / 5 分钟顺延一律用它**；
 * - [wallClockMs]：墙上时钟（[System.currentTimeMillis]，Epoch 绝对时间戳），
 *   仅用于 **300s 冷静期跨重启持久化**（单调时钟重启归零，无法跨重启）。
 *
 * 抽象成接口是为了在 JVM 单元测试中注入虚拟时钟，精确构造 179s / 181s / 重启等边界。
 */
interface TimeProvider {

    /** 单调时钟，毫秒。适合测量时间间隔。 */
    fun elapsedRealtimeMs(): Long

    /** 墙上时钟（Epoch ms），仅用于冷静期绝对时间戳。 */
    fun wallClockMs(): Long
}

/** 生产环境时间源单例。 */
object SystemTimeProvider : TimeProvider {
    override fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()
    override fun wallClockMs(): Long = System.currentTimeMillis()
}

/**
 * 测试用虚拟时钟：初始值固定，可通过 [advance] 精确推进，
 * 单调时钟与墙上时钟同步推进（模拟真实世界两者速率一致）。
 */
class VirtualTimeProvider(
    private var elapsedMs: Long = 0L,
    private var wallMs: Long = 1_700_000_000_000L,
) : TimeProvider {

    override fun elapsedRealtimeMs(): Long = elapsedMs

    override fun wallClockMs(): Long = wallMs

    /** 将时间向前推进 [ms] 毫秒（不允许回拨）。 */
    fun advance(ms: Long) {
        require(ms >= 0) { "虚拟时钟不允许回拨" }
        elapsedMs += ms
        wallMs += ms
    }
}
