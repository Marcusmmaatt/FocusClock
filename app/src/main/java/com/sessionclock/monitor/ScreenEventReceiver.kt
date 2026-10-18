package com.sessionclock.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * # 屏幕 / 解锁事件接收器（PRD 4.3 / F-03）
 *
 * 注意：ACTION_SCREEN_ON / ACTION_SCREEN_OFF / ACTION_USER_PRESENT 均为
 * **隐式广播豁免名单外（SCREEN_*）或动态注册更可靠**的系统广播，
 * 不能依赖 Manifest 静态注册，必须在前台服务存活期间于运行时注册，
 * 随服务生命周期注销，避免泄漏。
 *
 * @param onScreenOn    屏幕点亮（含未解锁）：恢复轮询
 * @param onScreenOff   屏幕熄灭：全局计时清零；目标应用进入 180s 容差；挂起轮询
 * @param onUserPresent 用户成功解锁（指纹/人脸/密码后）：全局秒表从 0 启动
 */
class ScreenEventReceiver(
    private val onScreenOn: () -> Unit,
    private val onScreenOff: () -> Unit,
    private val onUserPresent: () -> Unit,
) {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> onUserPresent()
            }
        }
    }

    fun register(appContext: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // 系统保护广播使用 NOT_EXPORTED 注册即可（仅系统能发，且不受导出标志限制），
        // 同时满足 Android 14 对动态注册接收器必须显式声明导出标志的要求。
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun unregister(appContext: Context) {
        runCatching { appContext.unregisterReceiver(receiver) }
    }
}
