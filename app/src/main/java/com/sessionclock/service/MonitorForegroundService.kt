package com.sessionclock.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sessionclock.SessionClockApp
import com.sessionclock.core.interaction.HapticController
import com.sessionclock.core.interaction.HomeNavigator
import com.sessionclock.engine.InterruptionEvent
import com.sessionclock.engine.MonitorEngine
import com.sessionclock.engine.ReminderScope
import com.sessionclock.engine.model.GlobalStatus
import com.sessionclock.engine.model.MonitorSnapshot
import com.sessionclock.engine.model.TargetStatus
import com.sessionclock.overlay.FloatWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * # 监控前台服务（PRD 4.2 / F-02）
 *
 * 职责：
 * 1. 以前台服务形态承载 [MonitorEngine]，挂载不可划除常驻通知；
 * 2. 把引擎快照映射为通知文案，并处理软提醒窗的「随应用进入后台自动销毁」；
 * 3. 订阅打断事件流，Sprint 3 起执行**真实打断**：
 *    软提醒 → 短震 + C01 悬浮卡片；强拦截 → 300ms 震 + C02 全屏遮罩，
 *    1.5s 后由 [HomeNavigator] 强退桌面并移除遮罩；
 * 4. START_STICKY + onTaskRemoved 闹钟重启，配合保活基线。
 */
class MonitorForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var notifications: ServiceNotification
    private lateinit var engine: MonitorEngine
    private lateinit var haptics: HapticController
    private lateinit var floatWindows: FloatWindowManager

    private var lastNotificationText: String? = null

    /** 当前正在展示的软提醒所属作用域；null 表示无软提醒窗（用于后台自动销毁判定） */
    private var activeSoftScope: ReminderScope? = null

    /** ISSUE-02：强拦截遮罩是否处于挂载状态（释放时机由快照驱动，严禁强退即销毁） */
    private var hardShieldActive = false

    override fun onCreate() {
        super.onCreate()
        notifications = ServiceNotification(this).also { it.ensureChannel() }

        // 必须在 5 秒内完成 startForeground，否则 Android 12+ 会抛 Timeout 并杀服务
        startAsForeground("监控启动中…")

        val container = (application as SessionClockApp).container
        engine = container.requireEngine()
        haptics = HapticController(this)
        floatWindows = FloatWindowManager(
            context = applicationContext,
            onPermissionLost = { notifications.notifyPermissionLost() },
        )
        engine.start()

        // 快照订阅：刷新通知文案 + 软提醒窗后台自动销毁 + 强拦截遮罩按状态释放
        serviceScope.launch {
            engine.snapshot.collectLatest { snapshot ->
                updateNotification(snapshot)
                autoDismissSoftReminder(snapshot)
                maybeReleaseHardShield(snapshot)
            }
        }

        // 打断事件订阅：真实执行挂窗 / 震动 / 强退
        // EC-05：该协程不在引擎 Actor 的 runCatching 覆盖范围内，处理异常若逃逸
        // 到主线程 UncaughtExceptionHandler 会直接杀进程，故此处整体兜底。
        serviceScope.launch {
            engine.interruptions.collect { event ->
                runCatching {
                    when (event) {
                        is InterruptionEvent.ShowSoftReminder -> handleSoftReminder(event)
                        is InterruptionEvent.ShowHardBlock -> handleHardBlock(event)
                        InterruptionEvent.PerformGoHome -> handlePerformGoHome()
                    }
                }.onFailure { Log.e(TAG, "处理打断事件失败: $event", it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 重复启动时幂等补齐前台态（START_STICKY 系统重建也走这里）
        startAsForeground(lastNotificationText ?: "正在监控")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 服务停止必须移除全部系统浮窗，避免泄漏窗口
        hardShieldActive = false
        floatWindows.dismissAll()
        engine.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 最近任务被划掉时安排 1 秒后重启（保活基线之一）。
     * Android 12+ 后台启动 FGS 受限，国内 ROM 多数放行；失败则等待
     * BOOT / WorkManager 看门狗（保活组件）兜底。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(this, MonitorForegroundService::class.java)
        val pending = PendingIntent.getService(
            this,
            REQUEST_RESTART,
            restartIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        alarmManager?.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_DELAY_MS,
            pending,
        )
        super.onTaskRemoved(rootIntent)
    }

    // =================================================================================
    // 打断执行
    // =================================================================================

    private fun handleSoftReminder(event: InterruptionEvent.ShowSoftReminder) {
        Log.i(TAG, "收到软提醒事件 scope=${event.scope}，准备挂载浮窗")
        haptics.shortBuzz()
        activeSoftScope = event.scope
        floatWindows.showSoftReminder(
            scope = event.scope,
            appLabel = event.appLabel,
            limitMs = event.limitMs,
            onAcknowledge = {
                // 交互规范：点击【我知道了】销毁浮窗，计时归零并顺延 5 分钟
                Log.i(TAG, "软提醒被确认 scope=${event.scope}")
                activeSoftScope = null
                floatWindows.dismissSoftReminder()
                engine.acknowledgeSoftReminder(event.scope)
            },
        )
    }

    private fun handleHardBlock(event: InterruptionEvent.ShowHardBlock) {
        haptics.hardBlockBuzz()
        val limitMinutes = engine.snapshot.value.config.targetLimitMinutes
        // 重复触发（冷静期内违规重开）时 showHardShield 内部幂等，倒计时流自动续显
        hardShieldActive = true
        floatWindows.showHardShield(
            remainingMsFlow = hardRemainingFlow,
            limitMinutes = limitMinutes,
        )
    }

    private fun handlePerformGoHome() {
        // ISSUE-02：此处【严禁】调用 floatWindows.dismissHardShield()！
        // HOME Intent 从发起到 Launcher 真正接管存在延迟，目标应用也可能被
        // 用户立刻重新打开；立即销毁遮罩等于在冷静期内解除阻断（ISSUE-02 原 Bug）。
        // 遮罩的释放统一由快照订阅 maybeReleaseHardShield 判定。
        HomeNavigator.goHome(this)
    }

    /**
     * ISSUE-02：强拦截遮罩释放判定。
     *
     * 遮罩必须贯穿 HardTriggering（1.5s 驻留）与整个 Cooldown（5 分钟冷静倒计时），
     * 期间目标应用仍在前台或被违规重开时持续全屏阻断；
     * 仅当冷静期结束、状态机回到 Idle（目标确认不在前台）时才允许销毁。
     */
    private fun maybeReleaseHardShield(snapshot: MonitorSnapshot) {
        if (!hardShieldActive) return
        if (snapshot.target is TargetStatus.Idle) {
            hardShieldActive = false
            floatWindows.dismissHardShield()
        }
    }

    /**
     * 交互规范第 3 节：软提醒浮窗随目标应用进入后台自动销毁。
     * 引擎离开 SoftTriggered（切后台转 BUFFER）时同步移除窗口；
     * 用户手动确认的场景已在 [handleSoftReminder] 回调中处理。
     */
    private fun autoDismissSoftReminder(snapshot: MonitorSnapshot) {
        val scope = activeSoftScope ?: return
        val stillTriggered = when (scope) {
            ReminderScope.TARGET_APP -> snapshot.target is TargetStatus.SoftTriggered
            ReminderScope.GLOBAL_SCREEN -> snapshot.global is GlobalStatus.SoftTriggered
        }
        if (!stillTriggered) {
            Log.i(TAG, "自动销毁软提醒 scope=$scope（对应状态已离开 SoftTriggered）")
            activeSoftScope = null
            floatWindows.dismissSoftReminder()
        }
    }

    /** 强拦截遮罩倒计时数据源：HardTriggering / Cooldown 期间输出剩余毫秒。 */
    private val hardRemainingFlow by lazy {
        engine.snapshot
            .map { snapshot ->
                when (val status = snapshot.target) {
                    is TargetStatus.HardTriggering -> status.cooldownRemainingMs
                    is TargetStatus.Cooldown -> status.remainingMs
                    else -> 0L
                }
            }
            .distinctUntilChanged()
    }

    // =================================================================================
    // 通知与服务类型
    // =================================================================================

    private fun updateNotification(snapshot: MonitorSnapshot) {
        val text = buildNotificationText(snapshot)
        if (text == lastNotificationText) return
        lastNotificationText = text
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(
                    ServiceNotification.ONGOING_NOTIFICATION_ID,
                    notifications.buildOngoing(text),
                )
        }
    }

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // ApplicationInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 shl 30
            FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            FOREGROUND_SERVICE_TYPE_NONE_BELOW_34
        }
        ServiceCompat.startForeground(
            this,
            ServiceNotification.ONGOING_NOTIFICATION_ID,
            notifications.buildOngoing(text),
            type,
        )
    }

    /** 快照 → 通知栏一句话状态（ISSUE-06 定稿文案）。 */
    private fun buildNotificationText(snapshot: MonitorSnapshot): String {
        val appLabel = snapshot.config.targetLabel.ifBlank { "目标应用" }
        return when (val status = snapshot.target) {
            is TargetStatus.Idle -> "摸鱼中"
            is TargetStatus.Running ->
                if (status.snoozeRemainingMs > 0L) "顺延5分钟 · $appLabel"
                else "我在盯着 · $appLabel ${formatMmSs(status.elapsedMs)}"

            TargetStatus.SoftTriggered -> "规定时长已达上限"
            is TargetStatus.Buffering ->
                "重置倒计时 ${formatMmSs(status.bufferRemainingMs)}"
            is TargetStatus.HardTriggering -> "正在执行退出拦截"
            is TargetStatus.Cooldown ->
                "冷静期剩余 ${formatMmSs(status.remainingMs)}"
        }
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val REQUEST_RESTART = 20
        private const val RESTART_DELAY_MS = 1_000L

        /** API 34+ specialUse 类型值（等于 ApplicationInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE） */
        private const val FOREGROUND_SERVICE_TYPE_SPECIAL_USE = 1 shl 30

        /** API 33 及以下 startForeground 不传类型，ServiceCompat 传 0 即可 */
        private const val FOREGROUND_SERVICE_TYPE_NONE_BELOW_34 = 0

        /** 启动服务（总开关开启 / 开机恢复）。 */
        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 停止服务（总开关关闭）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }

        /** mm:ss 格式化（通知文案用）。 */
        fun formatMmSs(ms: Long): String {
            val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return "%02d:%02d".format(minutes, seconds)
        }
    }
}
