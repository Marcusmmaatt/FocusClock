package com.sessionclock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.sessionclock.SessionClockApp
import com.sessionclock.service.MonitorForegroundService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * # 开机完成接收器（EC-03 / F-11：冷静期跨重启防御）
 *
 * 开机后：
 * 1. 读取持久化的 CooldownEpoch——若仍在冷静期，无论总开关状态如何都必须拉起服务继续拦截；
 * 2. 否则尊重用户的总开关设置（默认开启）；
 * 3. Android 12+ 后台启动 FGS 受限，BOOT_COMPLETED 属豁免广播，
 *    仍 try/catch ForegroundServiceStartNotAllowedException，失败交由看门狗兜底。
 *
 * 不监听 LOCKED_BOOT_COMPLETED：Direct Boot 阶段内部存储 / DataStore 尚不可读。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        val pendingResult = goAsync()
        // goAsync 只给约 10s，DataStore 读取放到后台线程并加 1.5s 超时
        Thread {
            try {
                val app = context.applicationContext as SessionClockApp
                val container = app.container

                val now = System.currentTimeMillis()
                val cooldownRemainingMs = container.cooldownStore.readEpochMs()
                    ?.let { it - now }
                    ?: 0L

                val masterEnabled = runBlocking {
                    withTimeoutOrNull(DATASTORE_READ_TIMEOUT_MS) {
                        container.settingsRepository.currentConfig().masterEnabled
                    }
                } ?: true // 读取失败默认拉起，宁可不漏掉冷静期拦截

                if (cooldownRemainingMs > 0L || masterEnabled) {
                    startMonitorService(context, cooldownRemainingMs)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "开机恢复失败", t)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun startMonitorService(context: Context, cooldownRemainingMs: Long) {
        try {
            Log.i(TAG, "开机拉起监控服务，冷静期剩余=${cooldownRemainingMs}ms")
            val intent = Intent(context, MonitorForegroundService::class.java)
                .setAction(ACTION_RESTORE_AFTER_BOOT)
            ContextCompat.startForegroundService(context, intent)
        } catch (t: Throwable) {
            // 典型为 ForegroundServiceStartNotAllowedException；Sprint 1 看门狗组件将做二次兜底
            Log.e(TAG, "开机启动前台服务被系统拒绝，等待看门狗兜底", t)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
        const val ACTION_RESTORE_AFTER_BOOT = "com.sessionclock.action.RESTORE_AFTER_BOOT"
        const val DATASTORE_READ_TIMEOUT_MS = 1_500L
    }
}
