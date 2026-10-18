package com.sessionclock.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sessionclock.MainActivity

/**
 * # 前台服务常驻通知（PRD 4.2 / F-02）
 *
 * - 渠道重要性 LOW：无声无弹窗，避免打扰；
 * - setOngoing(true)：通知不可被手动划除；
 * - 文案随引擎快照更新（监控中 / 容差中 / 冷静期中）。
 */
class ServiceNotification(private val appContext: Context) {

    private val notificationManager: NotificationManager? =
        appContext.getSystemService(NotificationManager::class.java)

    /** 创建低优先级常驻渠道（API 26+ 必需，重复创建安全）。 */
    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "专注力监控服务",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "FocusClock 后台监控运行状态常驻提示"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        notificationManager?.createNotificationChannel(channel)
    }

    /** 构建常驻通知。 */
    fun buildOngoing(contentText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_CONTENT,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            // Sprint 4 替换为正式品牌小图标；当前借用系统时钟图标，避免缺失资源导致编译/运行问题
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("正在保护你的专注力")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .build()
    }

    /**
     * EC-05：悬浮窗权限被中途撤销时的告警通知，
     * 点击跳转回 App（权限警示条引导重新授权）。
     */
    fun buildPermissionLost(): Notification {
        val contentIntent = PendingIntent.getActivity(
            appContext,
            REQUEST_PERMISSION_LOST,
            Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("权限已丢失，监控暂停")
            .setContentText("点击重新激活")
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
    }

    fun notifyPermissionLost() {
        notificationManager?.notify(PERMISSION_LOST_ID, buildPermissionLost())
    }

    fun cancelPermissionLost() {
        notificationManager?.cancel(PERMISSION_LOST_ID)
    }

    companion object {
        const val CHANNEL_ID = "sessionclock_monitor"
        const val ONGOING_NOTIFICATION_ID = 1001
        const val PERMISSION_LOST_ID = 1002

        private const val REQUEST_CONTENT = 10
        private const val REQUEST_PERMISSION_LOST = 11
    }
}
