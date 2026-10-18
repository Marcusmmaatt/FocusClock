package com.sessionclock.core.interaction

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * # 震动反馈控制器（PRD 5.1 / 5.2）
 *
 * - 软提醒（C01）：短震动 1 次（[SOFT_DURATION_MS]，系统默认振幅）；
 * - 强拦截（C02）：遮罩挂载时震动 [HARD_DURATION_MS] = 300ms，强振幅。
 *
 * 无震动硬件 / 调用异常时静默忽略，绝不让反馈逻辑导致崩溃（EC-05 同源原则）。
 */
class HapticController(context: Context) {

    private val appContext: Context = context.applicationContext

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ 统一走 VibratorManager
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }

    /** 软提醒：一次短促震动。 */
    fun shortBuzz() {
        oneShot(durationMs = SOFT_DURATION_MS, amplitude = VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** 强拦截：300ms 强震动。 */
    fun hardBlockBuzz() {
        oneShot(durationMs = HARD_DURATION_MS, amplitude = HARD_AMPLITUDE)
    }

    private fun oneShot(durationMs: Long, amplitude: Int) {
        val vibrator = this.vibrator ?: return
        runCatching {
            if (!vibrator.hasVibrator()) return@runCatching
            // VibrationEffect 自 API 26 起提供，minSdk 28 无需版本分支
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        }
    }

    private companion object {
        const val SOFT_DURATION_MS = 100L
        const val HARD_DURATION_MS = 300L
        const val HARD_AMPLITUDE = 255 // VibrationEffect 最大振幅
    }
}
