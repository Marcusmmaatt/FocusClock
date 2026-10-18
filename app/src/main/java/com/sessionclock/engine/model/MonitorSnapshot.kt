package com.sessionclock.engine.model

import com.sessionclock.data.model.SessionConfig

/**
 * 目标应用会话的对外状态（驱动 P01 区域 E 实时状态栏与通知文案）。
 * 与引擎内部状态机一一对应，但只暴露 UI 需要的已计算毫秒值。
 */
sealed interface TargetStatus {

    /** 空闲：未选择目标 / 会话已终结 / 容差超时清零 */
    data object Idle : TargetStatus

    /**
     * 前台计时中。
     * @param elapsedMs 本次会话已累计毫秒（容差内切回会在原值上继续）
     * @param snoozeRemainingMs 软提醒顺延剩余毫秒；0 表示不在顺延期
     */
    data class Running(
        val elapsedMs: Long,
        val snoozeRemainingMs: Long,
    ) : TargetStatus

    /** 软提醒弹窗已弹出，等待用户点击【我知道了】，计时冻结 */
    data object SoftTriggered : TargetStatus

    /**
     * 180s 容差倒计时中（EC-01 来电 / EC-02 熄屏均进入此态）。
     * @param accumulatedMs 已冻结保留的累计时长
     * @param bufferRemainingMs 容差剩余毫秒（归零则会话清零）
     */
    data class Buffering(
        val accumulatedMs: Long,
        val bufferRemainingMs: Long,
    ) : TargetStatus

    /** 强拦截遮罩驻留的 1.5s 内（即将强退桌面） */
    data class HardTriggering(
        val cooldownRemainingMs: Long,
    ) : TargetStatus

    /** 300s 冷静期中；期间重开目标应用会被 0.5s 内再次拦截 */
    data class Cooldown(
        val remainingMs: Long,
    ) : TargetStatus
}

/** 全局解锁单次计时状态（PRD 4.3）。 */
sealed interface GlobalStatus {

    /** 未解锁 / 已熄屏：计时为 0 */
    data object Idle : GlobalStatus

    data class Running(
        val elapsedMs: Long,
        val snoozeRemainingMs: Long,
    ) : GlobalStatus

    data object SoftTriggered : GlobalStatus
}

/**
 * 引擎对外发布的完整快照，每 500ms  tick 或状态变化时更新一次。
 */
data class MonitorSnapshot(
    val config: SessionConfig,
    val target: TargetStatus,
    val global: GlobalStatus,
)
