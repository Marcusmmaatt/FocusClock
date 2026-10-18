package com.sessionclock.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sessionclock.engine.COOLDOWN_MS
import com.sessionclock.engine.ReminderScope
import com.sessionclock.ui.overlay.HardBlockShieldContent
import com.sessionclock.ui.overlay.SoftReminderCard
import com.sessionclock.ui.theme.SessionClockTheme
import kotlinx.coroutines.flow.Flow

/**
 * # 系统浮窗管理器（C01 / C02 的唯一挂载入口）
 *
 * 统一封装 [WindowManager] + [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]：
 * - 软提醒窗：MATCH_PARENT 吞触摸容器 + 居中卡片 + FLAG_DIM_BEHIND（dim=0.6）；
 * - 强拦截窗：MATCH_PARENT 不透明遮罩，可获焦以拦截 Back，隐藏系统栏；
 * - 两类窗口各自单实例，重复触发安全幂等；
 * - addView 抛 SecurityException（权限被中途撤销，EC-05）时不崩溃，
 *   通过 [onPermissionLost] 回调通知服务发告警通知。
 *
 * ComposeView 的生命周期由 [InterceptRootView] 自身承载（它直接实现三大 Owner
 * 接口，androidx.lifecycle 2.8 的 findViewTree* 沿父布局即可发现）。
 */
internal class FloatWindowManager(
    context: Context,
    private val onPermissionLost: () -> Unit,
) {
    private val appContext: Context = context.applicationContext

    private val windowManager: WindowManager? =
        appContext.getSystemService(WindowManager::class.java)

    // 两类窗口的根布局（同时是 Compose 的生命周期宿主）
    private var softRoot: InterceptRootView? = null
    private var hardRoot: InterceptRootView? = null

    @Volatile
    private var permissionLostNotified = false

    // =================================================================================
    // C01 软提醒悬浮窗
    // =================================================================================

    /**
     * 挂载软提醒卡片。已在展示时先移除旧窗再挂新窗（避免重复堆叠）。
     *
     * @param onAcknowledge 用户点击【我知道了】：由服务层负责引擎顺延 + 调 [dismissSoftReminder]
     */
    fun showSoftReminder(
        scope: ReminderScope,
        appLabel: String,
        limitMs: Long,
        onAcknowledge: () -> Unit,
    ) {
        if (!ensureOverlayPermission()) return
        dismissSoftReminder()

        val bodyText = buildSoftBodyText(scope)
        val root = createComposeRoot {
            SoftReminderCard(bodyText = bodyText, onAcknowledge = onAcknowledge)
        }

        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
            // 可获焦以拦截 Back；DIM_BEHIND 提供 60% 黑色遮罩
            flags = (
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                )
            dimAmount = DIM_AMOUNT
            windowAnimations = 0
            title = WINDOW_TITLE_SOFT
        }

        if (addViewSafely(root, params)) softRoot = root
    }

    fun dismissSoftReminder() {
        softRoot?.let { removeView(it) }
        softRoot = null
    }

    // =================================================================================
    // C02 强拦截全屏遮罩
    // =================================================================================

    /**
     * 挂载强拦截遮罩。重复调用（冷静期内多次违规重开）保持同一实例，
     * 倒计时由 [remainingMsFlow] 自动刷新。
     *
     * @param remainingMsFlow 冷静期剩余毫秒流（引擎快照映射，0.5s 粒度）
     */
    fun showHardShield(remainingMsFlow: Flow<Long>, limitMinutes: Int) {
        if (hardRoot != null) return // 已在展示：倒计时流会自动更新，禁止重复 addView
        if (!ensureOverlayPermission()) return

        val root = createComposeRoot {
            val remainingMs by remainingMsFlow.collectAsStateWithLifecycle(
                initialValue = COOLDOWN_MS,
            )
            HardBlockShieldContent(remainingMs = remainingMs, limitMinutes = limitMinutes)
        }

        val params = baseParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
            // 全屏覆盖到系统栏区域；可获焦拦截 Back；不使用 DIM（遮罩自身不透明）
            flags = (
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            windowAnimations = 0
            title = WINDOW_TITLE_HARD
        }

        if (addViewSafely(root, params)) {
            hardRoot = root
            applyImmersive(root)
        }
    }

    fun dismissHardShield() {
        hardRoot?.let { removeView(it) }
        hardRoot = null
    }

    /** 服务销毁时清理全部浮窗。 */
    fun dismissAll() {
        dismissSoftReminder()
        dismissHardShield()
    }

    // =================================================================================
    // 内部实现
    // =================================================================================

    /**
     * 创建 [InterceptRootView] + [ComposeView] 组合。
     *
     * v1.1.2 修复：三大 Owner 的 Tag 必须写到**直接挂到 WindowManager 的窗口根视图**
     * （即 [InterceptRootView] 自身）上，而不是只写到内层 ComposeView 上。
     * Compose 的 WindowRecomposer 在窗口根视图上创建、并从根视图调用
     * findViewTreeLifecycleOwner 查找 Owner，内层 ComposeView 上的 Tag 它读不到，
     * 于是 attach / 首次组合时抛 `IllegalStateException: ViewTreeLifecycleOwner not found`
     * 导致闪退。v1.1.1 误把 Tag 只写到 ComposeView 上，正是「到点后遮罩不显示、
     * 应用闪退」的根因。
     */
    @Suppress("DEPRECATION") // 2.8 起官方推荐 View 自身实现接口，但 Tag 写入仍需该扩展
    private fun createComposeRoot(
        content: @androidx.compose.runtime.Composable () -> Unit,
    ): InterceptRootView {
        val root = InterceptRootView(appContext).apply {
            // 关键修复：Owner 必须注册在「窗口根视图」自身。WindowRecomposer
            // 在窗口根视图上创建并从根视图查找 findViewTreeLifecycleOwner，
            // 内层 ComposeView 上的 Tag 它读不到（v1.1.1 闪退根因）。
            setViewTreeLifecycleOwner(this)
            setViewTreeViewModelStoreOwner(this)
            setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(appContext).apply {
            // 内层 ComposeView 也同时写入（幂等），保证 attach / 首次组合、
            // DisposeOnViewTreeLifecycleDestroyed 等无论从根视图还是自身起查都能命中。
            setViewTreeLifecycleOwner(root)
            setViewTreeViewModelStoreOwner(root)
            setViewTreeSavedStateRegistryOwner(root)
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
            )
            setContent {
                SessionClockTheme(darkTheme = true) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        content()
                    }
                }
            }
        }
        root.addView(
            composeView,
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            ),
        )
        return root
    }

    private fun baseParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

    private fun addViewSafely(
        root: InterceptRootView,
        params: WindowManager.LayoutParams,
    ): Boolean {
        // EC-05：onCreate / addView / onResume 全程兜底，任何一步失败都不得让进程崩溃
        return try {
            root.onCreate()
            windowManager?.addView(root, params)
            root.onResume()
            true
        } catch (t: Throwable) {
            // 悬浮窗权限被撤销 / ROM 禁止后台弹窗 / Compose attach 异常：
            // 尽力移除半挂载窗口并销毁生命周期，避免 WindowLeak 与二次崩溃
            Log.e(TAG, "挂载浮窗失败", t)
            runCatching { windowManager?.removeView(root) }
            runCatching { root.onDestroy() }
            notifyPermissionLostOnce()
            false
        }
    }

    private fun removeView(root: InterceptRootView) {
        runCatching { root.onPause() }
        runCatching { windowManager?.removeView(root) }
            .onFailure { Log.w(TAG, "移除浮窗失败", it) }
        runCatching { root.onDestroy() }
    }

    /** 隐藏状态栏与导航手势栏，达成 C02 彻底物理阻断的视觉效果。 */
    private fun applyImmersive(root: InterceptRootView) {
        // EC-05：沉浸模式在部分 ROM 上可能抛异常，仅影响视觉不影响阻断，兜底忽略
        runCatching {
            val controller = ViewCompat.getWindowInsetsController(root)
            if (controller != null) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // API 28/29 兼容回退：使用旧 systemUiVisibility 沉浸标志
                @Suppress("DEPRECATION")
                root.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
            }
        }.onFailure { Log.w(TAG, "应用沉浸模式失败", it) }
    }

    private fun ensureOverlayPermission(): Boolean {
        val granted = Settings.canDrawOverlays(appContext)
        if (!granted) notifyPermissionLostOnce()
        return granted
    }

    private fun notifyPermissionLostOnce() {
        if (permissionLostNotified) return
        permissionLostNotified = true
        onPermissionLost()
    }

    /**
     * ISSUE-06 定稿：C01 正文按作用域使用固定文案，
     * 不再动态拼接应用名 / 分钟数。
     */
    private fun buildSoftBodyText(scope: ReminderScope): String = when (scope) {
        ReminderScope.TARGET_APP ->
            "阿伟你又在打电动哦，我才说你几句你就说我烦"
        ReminderScope.GLOBAL_SCREEN ->
            "热死了热死了热死了啊啊啊啊啊啊"
    }

    private companion object {
        const val TAG = "FloatWindowManager"
        const val DIM_AMOUNT = 0.6f
        const val WINDOW_TITLE_SOFT = "SessionClock:soft_reminder"
        const val WINDOW_TITLE_HARD = "SessionClock:hard_shield"
    }
}
