package com.sessionclock.engine

import com.sessionclock.core.time.VirtualTimeProvider
import com.sessionclock.data.model.InterruptMode
import com.sessionclock.engine.model.TargetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 目标应用状态机单元测试（对照 F-07 / F-08 / F-09 / F-10 / F-11 与 EC-01~EC-03）。
 *
 * 全部基于 [VirtualTimeProvider] 在 JVM 上运行，不依赖设备 / Robolectric。
 */
class TargetSessionStateMachineTest {

    private val limit60s = 60_000L
    private val limit30min = 1_800_000L

    /** 以 500ms 为步长推进虚拟时钟并逐拍 tick，模拟真实引擎节拍。 */
    private fun TargetSessionStateMachine.advance(
        clock: VirtualTimeProvider,
        ms: Long,
        limitMs: Long,
        mode: InterruptMode = InterruptMode.HARD_BLOCK,
        step: Long = 500L,
    ) {
        var remaining = ms
        while (remaining > 0) {
            val chunk = minOf(step, remaining)
            clock.advance(chunk)
            remaining -= chunk
            onTick(limitMs, mode)
        }
    }

    private fun newMachine(
        clock: VirtualTimeProvider = VirtualTimeProvider(),
    ): Pair<TargetSessionStateMachine, MutableList<TargetEffect>> {
        val effects = mutableListOf<TargetEffect>()
        return TargetSessionStateMachine(clock) { effects.add(it) } to effects
    }

    // F-07 AC-2：179 秒内切回，计时在 10 分钟基础上继续累加
    @Test
    fun `buffer within 180s resumes accumulated time`() {
        val clock = VirtualTimeProvider()
        val (sm, _) = newMachine(clock)

        sm.onForegroundChanged(true)
        sm.advance(clock, 600_000L, limit30min) // 已使用 10 分钟
        assertEquals(600_000L, (sm.snapshotStatus() as TargetStatus.Running).elapsedMs)

        sm.onForegroundChanged(false) // 切出（如回消息）
        assertTrue(sm.state is TargetState.Buffering)

        clock.advance(179_000L)
        sm.onTick(limit30min, InterruptMode.HARD_BLOCK) // 第 179 秒，容差未归零
        sm.onForegroundChanged(true) // 切回

        sm.advance(clock, 1_000L, limit30min)
        val status = sm.snapshotStatus() as TargetStatus.Running
        assertTrue("应在 10 分钟基础上继续，实际=${status.elapsedMs}", status.elapsedMs in 600_000L..602_000L)
    }

    // F-07 AC-3 / EC-01：超过 180 秒（来电通话 4 分钟）未返回，彻底清零，重开为新会话
    @Test
    fun `buffer beyond 180s resets session to zero`() {
        val clock = VirtualTimeProvider()
        val (sm, _) = newMachine(clock)

        sm.onForegroundChanged(true)
        sm.advance(clock, 600_000L, limit30min)
        sm.onForegroundChanged(false)

        clock.advance(181_000L) // 181 秒 > 180 秒容差
        sm.onTick(limit30min, InterruptMode.HARD_BLOCK)
        assertTrue(sm.state is TargetState.Idle)

        sm.onForegroundChanged(true) // 之后重新打开
        sm.advance(clock, 1_000L, limit30min)
        val elapsed = (sm.snapshotStatus() as TargetStatus.Running).elapsedMs
        assertTrue("新会话应从 0 开始，实际=$elapsed", elapsed in 0L..2_000L)
    }

    // F-08：软提醒触发后确认，计时归零并顺延 5 分钟，顺延期内不重复提醒
    @Test
    fun `soft reminder acknowledges then snoozes five minutes`() {
        val clock = VirtualTimeProvider()
        val (sm, effects) = newMachine(clock)

        sm.onForegroundChanged(true)
        sm.advance(clock, limit60s, limit60s, InterruptMode.SOFT_REMINDER)
        assertTrue(sm.state is TargetState.SoftTriggered)
        assertTrue(effects.any { it is TargetEffect.EmitSoftReminder })

        sm.onSoftAcknowledged()
        // 顺延期内再经过 4 分钟：计时冻结在 0，不再次触发
        sm.advance(clock, 240_000L, limit60s, InterruptMode.SOFT_REMINDER)
        val duringSnooze = sm.snapshotStatus() as TargetStatus.Running
        assertEquals(0L, duringSnooze.elapsedMs)
        assertTrue(duringSnooze.snoozeRemainingMs in 59_000L..61_000L)
        assertEquals(1, effects.count { it is TargetEffect.EmitSoftReminder })

        // 走完顺延剩下的 1 分钟：刚好到顺延截止，仍然不触发
        sm.advance(clock, 60_000L, limit60s, InterruptMode.SOFT_REMINDER)
        assertEquals(1, effects.count { it is TargetEffect.EmitSoftReminder })

        // 顺延结束后重新计满 1 分钟，才第二次提醒
        sm.advance(clock, 60_000L, limit60s, InterruptMode.SOFT_REMINDER)
        assertEquals(2, effects.count { it is TargetEffect.EmitSoftReminder })
    }

    // F-09 / F-10：强拦截落盘 Epoch → 1.5s 强退 → 冷静期重开再拦截 → 到期解除
    @Test
    fun `hard block persists epoch goes home reblocks and releases after cooldown`() {
        val clock = VirtualTimeProvider()
        val (sm, effects) = newMachine(clock)

        sm.onForegroundChanged(true)
        sm.advance(clock, limit60s, limit60s, InterruptMode.HARD_BLOCK)

        // 触发瞬间：先落盘绝对时间戳，再弹遮罩
        val persist = effects.filterIsInstance<TargetEffect.PersistCooldown>().single()
        assertTrue(effects.any { it is TargetEffect.EmitHardBlock })
        assertTrue(sm.state is TargetState.HardTriggering)
        assertEquals(clock.wallClockMs() + COOLDOWN_MS, persist.epochMs)

        // 驻留 1.5 秒后强退桌面，进入冷静期
        sm.advance(clock, HARD_SHIELD_DWELL_MS, limit60s)
        assertEquals(1, effects.count { it is TargetEffect.PerformGoHome })
        assertTrue(sm.state is TargetState.Cooldown)

        // 冷静期第 10 秒违规重开：0.5s 节拍内再弹遮罩，1.5s 后再次强退
        clock.advance(10_000L)
        sm.onTick(limit60s, InterruptMode.HARD_BLOCK)
        sm.onForegroundChanged(true)
        assertEquals(2, effects.count { it is TargetEffect.EmitHardBlock })
        sm.advance(clock, HARD_SHIELD_DWELL_MS, limit60s)
        assertEquals(2, effects.count { it is TargetEffect.PerformGoHome })
        assertTrue(sm.state is TargetState.Cooldown)

        // 走到冷静期结束：清除标记，恢复 Idle，可正常使用
        sm.advance(clock, COOLDOWN_MS, limit60s)
        assertTrue(effects.any { it is TargetEffect.ClearCooldown })
        assertTrue(sm.state is TargetState.Idle)

        sm.onForegroundChanged(true)
        assertTrue(sm.state is TargetState.Running)
    }

    // F-11 / EC-03：重启后恢复未结束的冷静期，剩余时间继续生效
    @Test
    fun `cooldown restored after reboot and expires correctly`() {
        val clock = VirtualTimeProvider()
        val (sm, effects) = newMachine(clock)

        // 模拟重启发生在冷静期第 2 分钟：剩余 3 分钟
        val epoch = clock.wallClockMs() + 180_000L
        sm.restoreCooldown(epoch)
        assertTrue(sm.state is TargetState.Cooldown)
        val remaining = (sm.snapshotStatus() as TargetStatus.Cooldown).remainingMs
        assertTrue("剩余应约 180s，实际=$remaining", remaining in 179_000L..180_500L)

        // 冷静期内重开仍被拦截
        sm.onForegroundChanged(true)
        assertTrue(effects.any { it is TargetEffect.EmitHardBlock })

        // 剩余时间走完后解除
        sm.advance(clock, 181_000L, limit60s)
        assertTrue(sm.state is TargetState.Idle)
        assertTrue(effects.any { it is TargetEffect.ClearCooldown })
    }

    // EC-03 分支：重启耗时超过 5 分钟，开机即解除
    @Test
    fun `expired cooldown after long reboot clears immediately`() {
        val clock = VirtualTimeProvider()
        val (sm, effects) = newMachine(clock)

        sm.restoreCooldown(clock.wallClockMs() - 1_000L)
        assertTrue(sm.state is TargetState.Idle)
        assertTrue(effects.any { it is TargetEffect.ClearCooldown })
    }
}
