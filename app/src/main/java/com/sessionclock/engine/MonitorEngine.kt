package com.sessionclock.engine

import android.content.Context
import android.app.KeyguardManager
import android.os.PowerManager
import android.util.Log
import com.sessionclock.core.time.TimeProvider
import com.sessionclock.data.CooldownStore
import com.sessionclock.data.SettingsRepository
import com.sessionclock.data.model.SessionConfig
import com.sessionclock.engine.model.GlobalStatus
import com.sessionclock.engine.model.MonitorSnapshot
import com.sessionclock.engine.model.TargetStatus
import com.sessionclock.monitor.ForegroundAppObserver
import com.sessionclock.monitor.ScreenEventReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # 监控引擎编排器（Actor 模型）
 *
 * 所有外部输入（前台包名变化、屏幕广播、配置变更、软提醒确认、周期 tick）
 * 都先进入 [eventChannel]，由**唯一的消费者协程串行处理**，
 * 从而保证两个状态机的方法不存在并发访问，副作用顺序严格可复现。
 *
 * 输出：
 * - [snapshot]：当前完整状态（UI 状态栏 / 通知文案订阅）；
 * - [interruptions]：一次性打断事件（Sprint 3 的 OverlayManager 订阅）。
 */
class MonitorEngine(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val clock: TimeProvider,
    private val settingsRepository: SettingsRepository,
    private val cooldownStore: CooldownStore,
) {
    private val _snapshot = MutableStateFlow(
        MonitorSnapshot(SessionConfig(), TargetStatus.Idle, GlobalStatus.Idle),
    )
    val snapshot: StateFlow<MonitorSnapshot> = _snapshot.asStateFlow()

    private val _interruptions = MutableSharedFlow<InterruptionEvent>(
        replay = 0,
        extraBufferCapacity = EXTRA_BUFFER,
    )
    val interruptions: SharedFlow<InterruptionEvent> = _interruptions.asSharedFlow()

    private val eventChannel = Channel<EngineEvent>(Channel.UNLIMITED)

    // 引擎内部组件（start 时初始化，保证 stop/start 可重复）
    private lateinit var target: TargetSessionStateMachine
    private lateinit var global: GlobalSessionController
    private lateinit var observer: ForegroundAppObserver
    private lateinit var screenReceiver: ScreenEventReceiver

    private val jobs = mutableListOf<Job>()
    private var tickerJob: Job? = null

    private var config = SessionConfig()
    private var lastTopPackage: String? = null
    private var lastTargetInFront = false

    @Volatile
    private var screenInteractive = true

    @Volatile
    private var started = false

    /** 启动引擎（前台服务 onCreate 调用，幂等）。 */
    fun start() {
        if (started) return
        started = true

        target = TargetSessionStateMachine(clock = clock, onEffect = ::handleTargetEffect)
        global = GlobalSessionController(clock) { limitMs ->
            Log.i(TAG, "全局亮屏达上限 limitMs=$limitMs，发送全局软提醒事件")
            _interruptions.tryEmit(
                InterruptionEvent.ShowSoftReminder(
                    scope = ReminderScope.GLOBAL_SCREEN,
                    appLabel = "手机",
                    limitMs = limitMs,
                ),
            )
        }
        observer = ForegroundAppObserver(appContext, clock)
        screenReceiver = ScreenEventReceiver(
            onScreenOn = { eventChannel.trySend(EngineEvent.ScreenOn) },
            onScreenOff = { eventChannel.trySend(EngineEvent.ScreenOff) },
            onUserPresent = { eventChannel.trySend(EngineEvent.UserPresent) },
        )

        // 初始屏幕态（服务在熄屏期间被拉起时不应空转轮询）
        screenInteractive = appContext.getSystemService(PowerManager::class.java)
            ?.isInteractive ?: true

        // ISSUE-03：服务在「已解锁」状态下被拉起（用户开启总开关 / START_STICKY 重建）
        // 时，系统不会再补发 ACTION_USER_PRESENT，全局锚点若不初始化将永远不计时。
        // 按当前交互态 + 锁屏态立即补建锚点；亮屏但仍锁屏则等待 USER_PRESENT。
        if (isDeviceUnlocked()) global.onUserUnlocked()

        // EC-03 / F-11：优先恢复持久化冷静期
        cooldownStore.readEpochMs()?.let { epoch -> target.restoreCooldown(epoch) }

        screenReceiver.register(appContext)
        observer.start(
            scope = appScope,
            intervalProvider = ::currentPollInterval,
            onPackage = { pkg -> eventChannel.trySend(EngineEvent.ForegroundPackage(pkg)) },
        )

        // 配置订阅
        jobs += appScope.launch {
            settingsRepository.configFlow.collect { cfg ->
                eventChannel.trySend(EngineEvent.ConfigLoaded(cfg))
            }
        }
        // 唯一事件消费者
        jobs += appScope.launch {
            for (event in eventChannel) {
                runCatching { handle(event) }
                    .onFailure { Log.e(TAG, "处理引擎事件失败: $event", it) }
            }
        }

        if (screenInteractive) startTicker()
        publishSnapshot()
    }

    /** 停止引擎（总开关关闭 / 服务 onDestroy）。 */
    fun stop() {
        if (!started) return
        started = false
        tickerJob?.cancel()
        tickerJob = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        observer.stop()
        if (this::screenReceiver.isInitialized) screenReceiver.unregister(appContext)
        // 排空残留事件（Channel 不能复用关闭后的实例，故只清空不 close，保证可再次 start）
        while (true) {
            val result = eventChannel.tryReceive()
            if (result.isSuccess) continue else break
        }
    }

    /** 软提醒【我知道了】点击回调（Sprint 3 浮窗按钮接入）。 */
    fun acknowledgeSoftReminder(scope: ReminderScope) {
        eventChannel.trySend(EngineEvent.AckSoft(scope))
    }

    // =================================================================================
    // 事件处理（全部运行在单消费者协程）
    // =================================================================================

    private fun handle(event: EngineEvent) {
        when (event) {
            is EngineEvent.ConfigLoaded -> {
                val oldPackage = config.targetPackage
                val oldGlobalEnabled = config.globalScreenEnabled
                config = event.config
                // 切换监控目标：旧目标的会话立即作废
                if (oldPackage != null && oldPackage != event.config.targetPackage) {
                    target.reset()
                    lastTargetInFront = false
                }
                // ISSUE-04：全局亮屏独立开关
                if (!event.config.globalScreenEnabled) {
                    // 关闭：立即挂起全局计时（复位 Idle），不再触发软提醒
                    global.onScreenOff()
                } else if (!oldGlobalEnabled && isDeviceUnlocked()) {
                    // 重新开启且当前已解锁：从 0 建立锚点；仍锁屏则等 USER_PRESENT
                    global.onUserUnlocked()
                }
                // 配置可能晚于首个前台事件到达，此处用最新配置补判一次
                evaluateForeground(lastTopPackage)
                publishSnapshot()
            }

            is EngineEvent.ForegroundPackage -> evaluateForeground(event.packageName)

            EngineEvent.ScreenOn -> {
                screenInteractive = true
                observer.setScreenInteractive(true)
                startTicker()
                // 自愈修复：亮屏且已解锁（无锁屏/智能锁等不发 USER_PRESENT 的场景）时，
                // 立即重建全局计时锚点，避免熄屏后全局卡在 Idle 无法重新计时。
                if (config.globalScreenEnabled && isDeviceUnlocked()) {
                    Log.i(TAG, "ScreenOn 且已解锁，重建全局锚点")
                    global.onUserUnlocked()
                }
                publishSnapshot()
            }

            EngineEvent.ScreenOff -> {
                screenInteractive = false
                observer.setScreenInteractive(false)
                Log.i(TAG, "ScreenOff：全局清零、停止 ticker")
                // EC-02：熄屏统一视作切出前台 → 目标进入 180s 容差
                if (target.state is TargetState.Running ||
                    target.state is TargetState.SoftTriggered
                ) {
                    target.onForegroundChanged(targetInFront = false)
                }
                // 全局秒表立即清零
                global.onScreenOff()
                stopTicker()
                publishSnapshot()
            }

            EngineEvent.UserPresent -> {
                screenInteractive = true
                observer.setScreenInteractive(true)
                startTicker()
                // ISSUE-04：全局开关关闭时，解锁不启动全局计时
                if (config.globalScreenEnabled) {
                    Log.i(TAG, "UserPresent：解锁，重建全局锚点")
                    global.onUserUnlocked()
                }
                publishSnapshot()
            }

            EngineEvent.Tick -> {
                // 自愈修复：设备已解锁但全局仍为 Idle（漏收 USER_PRESENT / 锁屏状态变化）
                // 时自动重建锚点，保证解锁状态下全局始终在计时。先做 Idle 短路判断，
                // 仅当全局为空闲时才查 isDeviceUnlocked()，避免每 500ms 的无谓 binder 调用。
                if (config.globalScreenEnabled && global.snapshot() is GlobalStatus.Idle &&
                    isDeviceUnlocked()
                ) {
                    Log.i(TAG, "Tick 自愈：全局 Idle 且已解锁，重建全局锚点")
                    global.onUserUnlocked()
                }
                target.onTick(config.targetLimitMs, config.interruptMode)
                // ISSUE-04：开关关闭时挂起全局 tick（计时与软提醒均不发生）
                if (config.globalScreenEnabled) global.onTick(config.globalLimitMs)
                publishSnapshot()
            }

            is EngineEvent.AckSoft -> {
                when (event.scope) {
                    ReminderScope.TARGET_APP -> target.onSoftAcknowledged()
                    ReminderScope.GLOBAL_SCREEN -> global.onSoftAcknowledged()
                }
                publishSnapshot()
            }
        }
    }

    /** 根据顶层包名与配置做上升沿 / 下降沿判定。 */
    private fun evaluateForeground(topPackage: String?) {
        lastTopPackage = topPackage
        val targetPackage = config.targetPackage
        val inFront = targetPackage != null && topPackage == targetPackage
        if (inFront != lastTargetInFront) {
            lastTargetInFront = inFront
            target.onForegroundChanged(inFront)
            publishSnapshot()
        }
    }

    /** 状态 → 自适应轮询间隔（熄屏时观察者自行挂起，此处仅给亮屏值）。 */
    private fun currentPollInterval(): Long = when (target.state) {
        is TargetState.HardTriggering, is TargetState.Cooldown -> INTERVAL_COOLDOWN_MS
        is TargetState.Running, is TargetState.SoftTriggered -> INTERVAL_RUNNING_MS
        else -> INTERVAL_IDLE_MS
    }

    /**
     * ISSUE-03：当前设备是否处于「亮屏且已解锁」状态。
     * 不依赖任何网络；KeyguardManager 直接反映锁屏状态。
     */
    private fun isDeviceUnlocked(): Boolean {
        val interactive = appContext.getSystemService(PowerManager::class.java)
            ?.isInteractive ?: false
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        return interactive && keyguard != null && !keyguard.isKeyguardLocked
    }

    /** 目标状态机副作用：落盘 / 清除 / 转发为打断事件。 */
    private fun handleTargetEffect(effect: TargetEffect) {
        when (effect) {
            is TargetEffect.PersistCooldown -> {
                val ok = cooldownStore.write(effect.epochMs)
                Log.i(TAG, "冷静期时间戳落盘: epoch=${effect.epochMs}, success=$ok")
            }

            TargetEffect.ClearCooldown -> cooldownStore.clear()

            is TargetEffect.EmitSoftReminder -> _interruptions.tryEmit(
                InterruptionEvent.ShowSoftReminder(
                    scope = ReminderScope.TARGET_APP,
                    appLabel = config.targetLabel.ifBlank { "目标应用" },
                    limitMs = effect.limitMs,
                ),
            )

            is TargetEffect.EmitHardBlock -> _interruptions.tryEmit(
                InterruptionEvent.ShowHardBlock(
                    cooldownEpochMs = effect.cooldownEpochMs,
                    cooldownDeadlineElapsed = effect.cooldownDeadlineElapsed,
                ),
            )

            TargetEffect.PerformGoHome -> _interruptions.tryEmit(InterruptionEvent.PerformGoHome)
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = appScope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MS)
                eventChannel.trySend(EngineEvent.Tick)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun publishSnapshot() {
        val now = clock.elapsedRealtimeMs()
        _snapshot.value = MonitorSnapshot(
            config = config,
            target = target.snapshotStatus(now),
            global = global.snapshot(now),
        )
    }

    /** 引擎内部事件总线。 */
    private sealed interface EngineEvent {
        data class ConfigLoaded(val config: SessionConfig) : EngineEvent
        data class ForegroundPackage(val packageName: String?) : EngineEvent
        data object ScreenOn : EngineEvent
        data object ScreenOff : EngineEvent
        data object UserPresent : EngineEvent
        data object Tick : EngineEvent
        data class AckSoft(val scope: ReminderScope) : EngineEvent
    }

    private companion object {
        const val TAG = "MonitorEngine"

        /** 引擎基准节拍 500ms：最细粒度只需满足冷静期 0.5s 重开响应 */
        const val TICK_INTERVAL_MS = 500L
        const val INTERVAL_IDLE_MS = 2_000L
        const val INTERVAL_RUNNING_MS = 1_000L
        const val INTERVAL_COOLDOWN_MS = 500L
        const val EXTRA_BUFFER = 8
    }
}
