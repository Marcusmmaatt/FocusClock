package com.sessionclock.engine

import com.sessionclock.core.time.TimeProvider
import com.sessionclock.engine.model.GlobalStatus

/**
 * # 全局解锁单次计时器（PRD 4.3 / 文档第 4 节时序）
 *
 * 与目标应用状态机完全独立，直接由系统广播驱动：
 * - ACTION_USER_PRESENT（成功解锁，亮屏但未解锁不会触发，满足 F-03 AC-2）→ 从 0 开始；
 * - ACTION_SCREEN_OFF（任何原因熄屏）→ 立即清零复位；
 * - 达阈值只发软提醒；点击确认后归零并顺延 5 分钟。
 *
 * 按拍板结论：**不做跨进程 / 跨重启落盘**，进程重启后自然复位。
 */
internal class GlobalSessionController(
    private val clock: TimeProvider,
    private val onSoftReminder: (limitMs: Long) -> Unit,
) {
    private sealed interface GlobalState {
        data object Idle : GlobalState

        data class Running(
            val accumulatedMs: Long,
            val resumedAtElapsed: Long,
            val snoozeUntilElapsed: Long,
        ) : GlobalState

        data object SoftTriggered : GlobalState
    }

    private var current: GlobalState = GlobalState.Idle

    /** 用户成功解锁：无论之前什么状态，单次秒表一律从 0 开始。 */
    fun onUserUnlocked() {
        current = GlobalState.Running(
            accumulatedMs = 0L,
            resumedAtElapsed = clock.elapsedRealtimeMs(),
            snoozeUntilElapsed = 0L,
        )
    }

    /** 熄屏 / 锁屏：立即停止并复位清零（F-03 AC-3）。 */
    fun onScreenOff() {
        current = GlobalState.Idle
    }

    /** 软提醒确认：归零 + 顺延 5 分钟。 */
    fun onSoftAcknowledged() {
        val now = clock.elapsedRealtimeMs()
        if (current is GlobalState.SoftTriggered) {
            current = GlobalState.Running(
                accumulatedMs = 0L,
                resumedAtElapsed = now,
                snoozeUntilElapsed = now + SOFT_SNOOZE_MS,
            )
        }
    }

    /** 周期 tick；仅在亮屏（引擎 ticker 运行）时被调用。 */
    fun onTick(limitMs: Long) {
        val now = clock.elapsedRealtimeMs()
        val s = current as? GlobalState.Running ?: return

        // 顺延保护期：冻结锚点
        if (s.snoozeUntilElapsed != 0L && now < s.snoozeUntilElapsed) {
            current = s.copy(resumedAtElapsed = now)
            return
        }

        val elapsedMs = s.accumulatedMs + (now - s.resumedAtElapsed)
        if (elapsedMs >= limitMs) {
            current = GlobalState.SoftTriggered
            onSoftReminder(limitMs)
        } else if (s.snoozeUntilElapsed != 0L) {
            current = s.copy(snoozeUntilElapsed = 0L)
        }
    }

    fun snapshot(now: Long = clock.elapsedRealtimeMs()): GlobalStatus =
        when (val s = current) {
            is GlobalState.Idle -> GlobalStatus.Idle

            is GlobalState.Running -> {
                if (s.snoozeUntilElapsed != 0L && now < s.snoozeUntilElapsed) {
                    GlobalStatus.Running(
                        elapsedMs = s.accumulatedMs,
                        snoozeRemainingMs = s.snoozeUntilElapsed - now,
                    )
                } else {
                    GlobalStatus.Running(
                        elapsedMs = s.accumulatedMs + (now - s.resumedAtElapsed),
                        snoozeRemainingMs = 0L,
                    )
                }
            }

            is GlobalState.SoftTriggered -> GlobalStatus.SoftTriggered
        }
}
