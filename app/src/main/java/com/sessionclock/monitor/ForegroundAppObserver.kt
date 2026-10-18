package com.sessionclock.monitor

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import com.sessionclock.core.time.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * # 前台应用观察者（PRD 4.4 / F-06）
 *
 * 通过 [UsageStatsManager.queryEvents] 轮询系统活动事件，识别顶层前台包名。
 *
 * ## ISSUE-01 修复要点（v1.1）
 * `queryEvents` 只在 Activity 切换**瞬时**产生事件；用户持续停留在同一应用内时，
 * 近期窗口查不到任何新事件。旧实现把「无事件」误判为 `null`（退到桌面），
 * 导致目标应用刚运行几秒就被错误切进 180s 容差并反复归零，永远无法达到上限。
 *
 * 新实现严格遵守：
 * 1. 实例级维护 [currentPackage]，轮询期间持续保持；
 * 2. 过滤系统界面 `com.android.systemui`（下拉通知栏会产生其前台事件，
 *    不应被视为应用切换）；
 * 3. **近期窗口无新前台事件时，严格保持当前已确立的包名，严禁置空**；
 *    只有明确检测到顶层包名变成其他 App 或 Launcher 时才触发更新。
 *
 * 自适应节拍（NFR 功耗约束）：
 * - 熄屏：轮询协程挂起，零 CPU；
 * - COOLDOWN / HARD_TRIGGER：500ms（满足 F-10「0.5 秒内再拦截」）；
 * - RUNNING：1000ms（PRD 规定）；
 * - 其他亮屏态：2000ms。
 *
 * EC-04（分屏 / 画中画）：应用在多窗口下可见且渲染活跃时，系统仍会上报
 * MOVE_TO_FOREGROUND / ACTIVITY_RESUMED 事件，状态机据此保持 RUNNING；
 * 各 ROM 多窗口事件口径存在差异，列入真机回归项。
 */
class ForegroundAppObserver(
    private val appContext: Context,
    private val clock: TimeProvider,
) {
    private val usageStatsManager: UsageStatsManager? =
        appContext.getSystemService(UsageStatsManager::class.java)

    private var pollJob: Job? = null

    @Volatile
    private var screenInteractive: Boolean = true

    /**
     * 当前已确立的顶层前台包名（ISSUE-01：实例级保持，不随单次轮询丢失）。
     * null 表示尚未确立任何前台应用（服务刚启动且重建窗口无事件）。
     */
    @Volatile
    private var currentPackage: String? = null

    /** 熄屏期间挂起、亮屏唤醒的信号 */
    private val wakeSignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * 启动轮询。
     * @param intervalProvider 动态返回当前所需轮询间隔（毫秒）
     * @param onPackage 仅在顶层包名**发生变化**时回调（上升沿 / 下降沿由引擎判定）
     */
    fun start(
        scope: CoroutineScope,
        intervalProvider: () -> Long,
        onPackage: (packageName: String?) -> Unit,
    ) {
        stop()
        pollJob = scope.launch(Dispatchers.Default) {
            // 服务启动时回查更长时间窗，重建当前前台状态（避免漏检启动前已打开的目标应用）
            detectLatestForegroundPackage(REBUILD_LOOKBACK_MS)?.let { rebuilt ->
                if (rebuilt != currentPackage) {
                    currentPackage = rebuilt
                    onPackage(rebuilt)
                }
            }

            while (isActive) {
                // 熄屏：完全挂起，等待亮屏信号（NFR：熄屏 CPU 占用 0）
                while (!screenInteractive) {
                    wakeSignal.receiveCatching().getOrNull() ?: break
                }

                // ISSUE-01：近期窗口可能没有新事件（用户持续停留在同一应用），
                // 此时 detectLatestForegroundPackage 返回 null，必须保持 currentPackage。
                val latest = detectLatestForegroundPackage(NORMAL_LOOKBACK_MS)
                val resolved = latest ?: currentPackage

                if (resolved != currentPackage) {
                    currentPackage = resolved
                    onPackage(resolved)
                }

                val interval = intervalProvider().coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                delay(interval)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** 由屏幕广播驱动；亮屏时唤醒挂起的轮询。 */
    fun setScreenInteractive(interactive: Boolean) {
        if (interactive && !screenInteractive) {
            wakeSignal.trySend(Unit)
        }
        screenInteractive = interactive
    }

    /**
     * 查询 [lookbackMs] 时间窗内**最后一条**有效前台事件的包名。
     *
     * @return 窗口内无新前台事件时返回 null（调用方必须保持既有包名）；
     *         系统界面（[SYSTEM_UI_PACKAGE] 及其子包）与本应用自身的事件被过滤，
     *         过滤后窗口内无有效事件同样返回 null。
     */
    private fun detectLatestForegroundPackage(lookbackMs: Long): String? {
        val manager = usageStatsManager ?: return null
        val end = clock.wallClockMs()
        val events = runCatching { manager.queryEvents(end - lookbackMs, end) }
            .getOrNull() ?: return null

        val event = UsageEvents.Event()
        var latestPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForegroundEvent =
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)

            if (isForegroundEvent && isValidForegroundPackage(event.packageName)) {
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    /** 过滤系统通知栏界面（下拉通知栏误切）与本应用自身。 */
    private fun isValidForegroundPackage(packageName: String?): Boolean {
        if (packageName == null) return false
        if (packageName == appContext.packageName) return false
        return !(packageName == SYSTEM_UI_PACKAGE ||
            packageName.startsWith("$SYSTEM_UI_PACKAGE."))
    }

    private companion object {
        /** 系统界面包名：下拉通知栏 / 锁屏控制中心等，不视为真实应用切换 */
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /** 常规窗口覆盖 2 个轮询周期，避免边界丢事件 */
        const val NORMAL_LOOKBACK_MS = 4_000L

        /** 启动重建窗口：覆盖服务不在前台的一小段时间 */
        const val REBUILD_LOOKBACK_MS = 15_000L

        const val MIN_INTERVAL_MS = 500L
        const val MAX_INTERVAL_MS = 5_000L
    }
}
