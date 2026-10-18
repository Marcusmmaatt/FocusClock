package com.sessionclock.engine

import com.sessionclock.core.time.TimeProvider
import com.sessionclock.data.model.InterruptMode
import com.sessionclock.engine.model.TargetStatus

/* =====================================================================================
 * 业务固定常量（PRD v1.0 锁定，不开放用户配置）
 * ===================================================================================== */

/** 切后台心理学容差：180 秒（PRD 2.2 假设一 / F-07） */
internal const val BUFFER_TOLERANCE_MS = 180_000L

/** 强拦截冷静期：300 秒（PRD 2.2 假设二 / F-09、F-10） */
internal const val COOLDOWN_MS = 300_000L

/** 软提醒确认后的顺延保护期：5 分钟（PRD 4.3 / 4.4） */
internal const val SOFT_SNOOZE_MS = 300_000L

/** 强拦截遮罩驻留时长：1.5 秒，之后强退桌面（PRD 5.2） */
internal const val HARD_SHIELD_DWELL_MS = 1_500L

/**
 * # 目标应用会话内部状态（对照《02_业务状态流转与时序图.md》第 2 节状态机）
 *
 * 计时采用「锚点记账」而非每秒 +1：
 * 进入计时态时记录 resumedAtElapsed，当前时长 = accumulatedMs + (now - resumedAtElapsed)，
 * 协程 tick 抖动 / 短暂卡顿不会造成累计误差。
 */
internal sealed interface TargetState {

    /** 初始 / 会话终结 */
    data object Idle : TargetState

    /**
     * 前台计时中
     * @param snoozeUntilElapsed 顺延截止单调时间；0 表示无顺延
     */
    data class Running(
        val accumulatedMs: Long,
        val resumedAtElapsed: Long,
        val snoozeUntilElapsed: Long,
    ) : TargetState

    /** 软提醒已弹出，计时冻结，等待【我知道了】 */
    data class SoftTriggered(val accumulatedMs: Long) : TargetState

    /** 切出前台 / 熄屏，180s 容差倒计时中 */
    data class Buffering(
        val accumulatedMs: Long,
        val bufferDeadlineElapsed: Long,
        val snoozeUntilElapsed: Long,
    ) : TargetState

    /** 强拦截遮罩驻留的 1.5s，到时发 GoHome 并转入 [Cooldown] */
    data class HardTriggering(
        val cooldownEpochMs: Long,
        val cooldownDeadlineElapsed: Long,
        val dwellDeadlineElapsed: Long,
    ) : TargetState

    /**
     * 冷静期锁定中
     * @param reblockHomeAtElapsed 违规重开时遮罩驻留截止点；0 表示当前没有重开遮罩驻留
     */
    data class Cooldown(
        val cooldownEpochMs: Long,
        val cooldownDeadlineElapsed: Long,
        val reblockHomeAtElapsed: Long,
    ) : TargetState
}

/** 状态机产生的副作用，由 [MonitorEngine] 落盘 / 转发为 [InterruptionEvent]。 */
internal sealed interface TargetEffect {

    data class EmitSoftReminder(val limitMs: Long) : TargetEffect

    data class EmitHardBlock(
        val cooldownEpochMs: Long,
        val cooldownDeadlineElapsed: Long,
    ) : TargetEffect

    data object PerformGoHome : TargetEffect

    data class PersistCooldown(val epochMs: Long) : TargetEffect

    data object ClearCooldown : TargetEffect
}

/**
 * # 目标应用单次防沉迷状态机
 *
 * 严格对照《02_业务状态流转与时序图.md》第 5 节「状态转换矩阵」10 条转换逐条实现。
 *
 * 线程模型：本类**非线程安全**，所有方法只允许在 [MonitorEngine] 的单消费者协程
 * （Actor 模型）中调用；测试中则在单测试线程内顺序调用。这样设计避免了锁，
 * 且使「事件 → 状态 → 副作用」严格串行、可复现。
 *
 * 异常用例覆盖：
 * - EC-01 来电 4 分钟：来电使前台包名变化 → [onForegroundChanged](false) 进入 BUFFER，
 *   240s > 180s，tick 判定归零（与普通切后台同一路径，无需特判）；
 * - EC-02 熄屏：引擎收到 SCREEN_OFF 后同样调用 [onForegroundChanged](false)；
 * - EC-03 重启：开机后引擎调用 [restoreCooldown] 注入持久化的 Epoch；
 * - EC-04 分屏 / PiP：只要观察者仍上报目标包名在前台，状态保持 RUNNING 自增。
 */
internal class TargetSessionStateMachine(
    private val clock: TimeProvider,
    private val onEffect: (TargetEffect) -> Unit,
) {
    private var current: TargetState = TargetState.Idle

    val state: TargetState get() = current

    /** 切换监控目标 / 总开关关闭时彻底复位。 */
    fun reset() {
        current = TargetState.Idle
    }

    /**
     * EC-03 / F-11：服务启动（含开机自启）时恢复冷静期。
     * 以 Epoch 算出剩余毫秒，换算成本次开机周期的单调截止点；
     * 已过期则回落 Idle 并要求清除文件标记。
     */
    fun restoreCooldown(cooldownEpochMs: Long) {
        val nowElapsed = clock.elapsedRealtimeMs()
        val remainingMs = cooldownEpochMs - clock.wallClockMs()
        current = if (remainingMs > 0L) {
            TargetState.Cooldown(
                cooldownEpochMs = cooldownEpochMs,
                cooldownDeadlineElapsed = nowElapsed + remainingMs,
                reblockHomeAtElapsed = 0L,
            )
        } else {
            onEffect(TargetEffect.ClearCooldown)
            TargetState.Idle
        }
    }

    /**
     * 前台焦点变化（引擎做上升沿 / 下降沿判定后调用，重复状态不会调用）。
     *
     * 转换矩阵对应：
     * - IDLE + 前台(无冷却) → RUNNING
     * - RUNNING + 失焦/熄屏 → BUFFER
     * - BUFFER + ≤180s 回归 → RUNNING（保留累计）
     * - BUFFER + >180s 回归 → 视为新会话 RUNNING(0)
     * - COOLDOWN + 违规重开 → 立即弹遮罩，1.5s 后再次强退（F-10，0.5s 轮询保证响应）
     */
    fun onForegroundChanged(targetInFront: Boolean) {
        val now = clock.elapsedRealtimeMs()

        if (targetInFront) {
            when (val s = current) {
                is TargetState.Idle -> {
                    current = TargetState.Running(
                        accumulatedMs = 0L,
                        resumedAtElapsed = now,
                        snoozeUntilElapsed = 0L,
                    )
                }

                is TargetState.Buffering -> {
                    current = if (now <= s.bufferDeadlineElapsed) {
                        // 分支 A：180s 内返回，保留 accumulatedMs 继续累加
                        TargetState.Running(
                            accumulatedMs = s.accumulatedMs,
                            resumedAtElapsed = now,
                            snoozeUntilElapsed = s.snoozeUntilElapsed,
                        )
                    } else {
                        // 分支 B：容差已超时（tick 未来得及清算），归零并作为全新会话
                        TargetState.Running(0L, now, 0L)
                    }
                }

                is TargetState.Cooldown -> {
                    // F-10：冷静期违规重开 → 0.5s 内再弹遮罩（引擎 500ms 轮询保证）
                    onEffect(
                        TargetEffect.EmitHardBlock(
                            cooldownEpochMs = s.cooldownEpochMs,
                            cooldownDeadlineElapsed = s.cooldownDeadlineElapsed,
                        ),
                    )
                    // 驻留 1.5s 后由 tick 发出 PerformGoHome
                    current = s.copy(reblockHomeAtElapsed = now + HARD_SHIELD_DWELL_MS)
                }

                else -> Unit // Running / SoftTriggered / HardTriggering：上升沿忽略
            }
        } else {
            when (val s = current) {
                is TargetState.Running -> {
                    // 冻结当前累计（顺延期内则保持冻结值），启动 180s 容差
                    val frozenMs = if (now < s.snoozeUntilElapsed) {
                        s.accumulatedMs
                    } else {
                        s.accumulatedMs + (now - s.resumedAtElapsed)
                    }
                    current = TargetState.Buffering(
                        accumulatedMs = frozenMs,
                        bufferDeadlineElapsed = now + BUFFER_TOLERANCE_MS,
                        snoozeUntilElapsed = s.snoozeUntilElapsed,
                    )
                }

                is TargetState.SoftTriggered -> {
                    // 浮窗随目标应用进入后台而销毁（交互规范第 3 节），进入容差
                    current = TargetState.Buffering(
                        accumulatedMs = s.accumulatedMs,
                        bufferDeadlineElapsed = now + BUFFER_TOLERANCE_MS,
                        snoozeUntilElapsed = 0L,
                    )
                }

                else -> Unit // Idle / Buffering / HardTriggering(遮罩即将 Home) / Cooldown：忽略
            }
        }
    }

    /**
     * 周期 tick（引擎在亮屏时以 500ms 基准节拍调用）。
     *
     * 转换矩阵对应：
     * - RUNNING + 达阈值(软提醒) → SOFT_TRIGGER
     * - RUNNING + 达阈值(强拦截) → HARD_TRIGGER（先持久化 Epoch，再弹遮罩）
     * - BUFFER + 倒计时归零 → IDLE（累计清零）
     * - HARD_TRIGGER + 满 1.5s → 发 GoHome → COOLDOWN
     * - COOLDOWN + 到期 → 清除标记 → IDLE
     */
    fun onTick(limitMs: Long, mode: InterruptMode) {
        val now = clock.elapsedRealtimeMs()
        val nowWall = clock.wallClockMs()

        when (val s = current) {
            is TargetState.Idle -> Unit

            is TargetState.Running -> {
                // 顺延保护期内：冻结计时锚点（累计停在 0），不做阈值检测
                if (s.snoozeUntilElapsed != 0L && now < s.snoozeUntilElapsed) {
                    current = s.copy(resumedAtElapsed = now)
                    return
                }

                val elapsedMs = s.accumulatedMs + (now - s.resumedAtElapsed)
                if (elapsedMs >= limitMs) {
                    when (mode) {
                        InterruptMode.SOFT_REMINDER -> {
                            current = TargetState.SoftTriggered(elapsedMs)
                            onEffect(TargetEffect.EmitSoftReminder(limitMs))
                        }

                        InterruptMode.HARD_BLOCK -> {
                            // PRD：CooldownEpoch = 当前绝对时间 + 300s，先落盘再弹遮罩
                            val epoch = nowWall + COOLDOWN_MS
                            val deadlineElapsed = now + COOLDOWN_MS
                            current = TargetState.HardTriggering(
                                cooldownEpochMs = epoch,
                                cooldownDeadlineElapsed = deadlineElapsed,
                                dwellDeadlineElapsed = now + HARD_SHIELD_DWELL_MS,
                            )
                            onEffect(TargetEffect.PersistCooldown(epoch))
                            onEffect(TargetEffect.EmitHardBlock(epoch, deadlineElapsed))
                        }
                    }
                } else if (s.snoozeUntilElapsed != 0L) {
                    // 顺延刚结束：清除顺延标记，从 0 开始正常计时
                    current = s.copy(snoozeUntilElapsed = 0L)
                }
            }

            is TargetState.SoftTriggered -> Unit // 等待用户确认，不重复触发

            is TargetState.Buffering -> {
                // 超过 180s 未返回：心流自然瓦解，秒表彻底归零
                if (now >= s.bufferDeadlineElapsed) {
                    current = TargetState.Idle
                }
            }

            is TargetState.HardTriggering -> {
                if (now >= s.dwellDeadlineElapsed) {
                    onEffect(TargetEffect.PerformGoHome)
                    current = TargetState.Cooldown(
                        cooldownEpochMs = s.cooldownEpochMs,
                        cooldownDeadlineElapsed = s.cooldownDeadlineElapsed,
                        reblockHomeAtElapsed = 0L,
                    )
                }
            }

            is TargetState.Cooldown -> {
                // 违规重开的遮罩驻留满 1.5s → 再次强退
                if (s.reblockHomeAtElapsed != 0L && now >= s.reblockHomeAtElapsed) {
                    onEffect(TargetEffect.PerformGoHome)
                    current = s.copy(reblockHomeAtElapsed = 0L)
                }
                // 运行期以单调时钟为准判定到期（抗用户改表）；Epoch 仅用于跨重启
                if (now >= s.cooldownDeadlineElapsed) {
                    onEffect(TargetEffect.ClearCooldown)
                    current = TargetState.Idle
                }
            }
        }
    }

    /** 软提醒弹窗点击【我知道了】：计时归零，顺延 5 分钟后重新检测。 */
    fun onSoftAcknowledged() {
        val now = clock.elapsedRealtimeMs()
        if (current is TargetState.SoftTriggered) {
            current = TargetState.Running(
                accumulatedMs = 0L,
                resumedAtElapsed = now,
                snoozeUntilElapsed = now + SOFT_SNOOZE_MS,
            )
        }
    }

    /** 计算对外快照（纯读，毫秒值已换算好）。 */
    fun snapshotStatus(now: Long = clock.elapsedRealtimeMs()): TargetStatus =
        when (val s = current) {
            is TargetState.Idle -> TargetStatus.Idle

            is TargetState.Running -> {
                if (s.snoozeUntilElapsed != 0L && now < s.snoozeUntilElapsed) {
                    TargetStatus.Running(
                        elapsedMs = s.accumulatedMs,
                        snoozeRemainingMs = s.snoozeUntilElapsed - now,
                    )
                } else {
                    TargetStatus.Running(
                        elapsedMs = s.accumulatedMs + (now - s.resumedAtElapsed),
                        snoozeRemainingMs = 0L,
                    )
                }
            }

            is TargetState.SoftTriggered -> TargetStatus.SoftTriggered

            is TargetState.Buffering -> TargetStatus.Buffering(
                accumulatedMs = s.accumulatedMs,
                bufferRemainingMs = (s.bufferDeadlineElapsed - now).coerceAtLeast(0L),
            )

            is TargetState.HardTriggering -> TargetStatus.HardTriggering(
                cooldownRemainingMs = (s.cooldownDeadlineElapsed - now).coerceAtLeast(0L),
            )

            is TargetState.Cooldown -> TargetStatus.Cooldown(
                remainingMs = (s.cooldownDeadlineElapsed - now).coerceAtLeast(0L),
            )
        }
}
