package com.sessionclock.core.permission

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.CheckResult
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * # 权限聚合状态（PRD 4.1 / F-01 的单一可信数据源）
 *
 * @property hasUsageStats                是否授予「使用情况访问权限」（PACKAGE_USAGE_STATS）
 * @property canDrawOverlays              是否授予「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）
 * @property hasPostNotifications         是否授予通知权限（API 33+ 才有此运行时权限，低版本恒为 true）
 * @property isBatteryOptimizationExempt  是否已加入电池优化白名单（保活基线，非 PRD 核心权限）
 */
data class PermissionState(
    val hasUsageStats: Boolean,
    val canDrawOverlays: Boolean,
    val hasPostNotifications: Boolean,
    val isBatteryOptimizationExempt: Boolean,
) {
    /**
     * PRD 4.1 定义的**两项核心权限**是否全部授予。
     * 主界面 P01 区域 A 的警示条仅由该字段驱动。
     */
    val coreGranted: Boolean
        get() = hasUsageStats && canDrawOverlays

    /** 核心权限 + 通知 + 电池白名单全部就绪（保活引导完成的判定）。 */
    val allReady: Boolean
        get() = coreGranted && hasPostNotifications && isBatteryOptimizationExempt
}

/**
 * PRD 两项核心权限的枚举，用于警示条点击后定向跳转对应设置页。
 */
enum class CorePermission {
    /** 使用情况访问权限：UsageStatsManager 前台包名检测的前提 */
    USAGE_STATS,

    /** 悬浮窗权限：软提醒卡片 / 强拦截全屏遮罩挂载的前提 */
    OVERLAY,
}

/**
 * # 权限管理器（Sprint 1）
 *
 * 职责：
 * 1. 检测 [CorePermission] 两项核心权限，以及通知、电池优化白名单两项配套权限的实时状态；
 * 2. 以 [StateFlow] 形式对外暴露 [PermissionState]，供 P01 区域 A 警示条订阅；
 * 3. 封装各厂商 ROM 通用的系统设置页 Intent，并提供**多级降级跳转**
 *    （专属页 → 通用列表页 → 应用详情页），避免在深度定制系统上抛
 *    [ActivityNotFoundException] 而崩溃（对齐 EC-05「不崩溃」原则）。
 *
 * 生命周期与线程：
 * - 仅持有 **applicationContext**，不存在 Activity 泄漏风险，可作为单例挂在 AppContainer；
 * - 检测方法均为轻量同步调用（AppOps 为快速 Binder 缓存查询），可在任意线程调用；
 * - 权限授予发生在系统设置页，**返回 App 时不会有回调**，调用方必须在
 *   `Activity.onResume()` / `Service.onStartCommand()` 中主动调用 [refresh]。
 *
 * 使用示例（P01）：
 * ```
 * val permissionManager = PermissionManager.from(context)
 * lifecycleScope.launch {
 *     repeatOnLifecycle(Lifecycle.State.RESUMED) {
 *         // 每次回到前台先刷新一次，再订阅状态
 *         permissionManager.refresh()
 *         permissionManager.state.collect { renderBanner(it) }
 *     }
 * }
 * // 警示条点击：
 * permissionManager.launch(CorePermission.USAGE_STATS)
 * ```
 */
class PermissionManager private constructor(
    private val appContext: Context,
) {

    private val appOpsManager: AppOpsManager? =
        appContext.getSystemService(AppOpsManager::class.java)

    private val powerManager: PowerManager? =
        appContext.getSystemService(PowerManager::class.java)

    /** 当前权限状态快照；初始值在构造时即完成一次真实检测。 */
    private val _state = MutableStateFlow(createSnapshot())

    /** 对外只读状态流。 */
    val state: StateFlow<PermissionState> = _state.asStateFlow()

    // =================================================================================
    // 一、权限状态检测
    // =================================================================================

    /**
     * 重新执行一次全部权限检测并更新 [state]。
     *
     * 必须调用的时机：
     * 1. MainActivity.onResume()（用户从系统设置页授权返回，F-01 AC-3 要求 1 秒内消隐）；
     * 2. 前台服务启动 / 通知栏「点击重新激活」跳转返回后（EC-05）。
     */
    fun refresh(): PermissionState {
        val snapshot = createSnapshot()
        _state.value = snapshot
        return snapshot
    }

    /**
     * 检测「使用情况访问权限」是否已授予。
     *
     * 实现要点：
     * - 该权限不是普通 runtime permission，[Context.checkSelfPermission] 恒返回 denied，
     *   必须通过 [AppOpsManager] 检查 appop `OPSTR_GET_USAGE_STATS`；
     * - 仅当结果为 [AppOpsManager.MODE_ALLOWED] 时视为授权；
     *   MODE_DEFAULT / MODE_IGNORED / MODE_ERRORED 均按未授权处理；
     * - API 29+ 使用 [AppOpsManager.unsafeCheckOpNoThrow]（应用自查 appop 的官方推荐入口，
     *   旧的 checkOpNoThrow 在 Q 起对非系统应用做了限制并被废弃）。
     */
    fun hasUsageStatsPermission(): Boolean {
        val manager = appOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            manager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                appContext.packageName,
            )
        } else {
            @Suppress("DEPRECATION", "DEPRECATED_ERROR")
            manager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                appContext.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * 检测「悬浮窗 / 显示在其他应用上层」权限是否已授予。
     *
     * 直接使用 [Settings.canDrawOverlays]，API 23+ 官方判定方法（minSdk 28 无需版本判断）。
     * 注意：用户在设置页关闭后，WindowManager.addView 会抛 SecurityException，
     * OverlayManager 层仍需 try/catch 兜底（EC-05）。
     */
    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(appContext)

    /**
     * 检测通知权限（Android 13 / API 33 新增的运行时权限）。
     *
     * - API 33+：检查 POST_NOTIFICATIONS；
     * - API 32 及以下：通知默认开启，恒返回 true。
     *
     * 未授权时前台服务仍可存活，但常驻通知对用户不可见（影响 F-02 体感）。
     */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检测是否已被加入电池优化白名单（即 Doze 省电策略对本应用豁免）。
     * 属于保活增强项，不纳入 [PermissionState.coreGranted]。
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        val manager = powerManager ?: return false
        return manager.isIgnoringBatteryOptimizations(appContext.packageName)
    }

    /** 返回当前缺失的核心权限列表（空列表表示两项核心权限均已授予）。 */
    fun missingCorePermissions(): List<CorePermission> = buildList {
        if (!hasUsageStatsPermission()) add(CorePermission.USAGE_STATS)
        if (!canDrawOverlays()) add(CorePermission.OVERLAY)
    }

    /** 执行一次全量检测，生成不可变快照。 */
    private fun createSnapshot(): PermissionState = PermissionState(
        hasUsageStats = hasUsageStatsPermission(),
        canDrawOverlays = canDrawOverlays(),
        hasPostNotifications = hasNotificationPermission(),
        isBatteryOptimizationExempt = isIgnoringBatteryOptimizations(),
    )

    // =================================================================================
    // 二、系统设置页 Intent 封装（含厂商 ROM 多级降级）
    // =================================================================================

    /**
     * 构造跳转指定核心权限设置页的 Intent（首选目标）。
     *
     * 仅在需要把 Intent 交给其他组件（如通知 PendingIntent）时使用本方法；
     * 应用内直接跳转请调用 [launch]，它自带异常降级保护。
     */
    @CheckResult
    fun createSettingsIntent(permission: CorePermission): Intent =
        when (permission) {
            CorePermission.USAGE_STATS -> createUsageAccessSettingsIntent()
            CorePermission.OVERLAY -> createOverlaySettingsIntent(withPackage = true)
        }

    /**
     * 跳转指定核心权限的系统设置页。
     *
     * 按「专属页 → 通用页 → 应用详情页」的顺序依次尝试，任一成功即返回；
     * 全部失败时静默放弃（理论上应用详情页在所有 ROM 均存在）。
     */
    fun launch(permission: CorePermission) {
        val candidates = when (permission) {
            CorePermission.USAGE_STATS -> listOf(
                createUsageAccessSettingsIntent(),
                createAppDetailsSettingsIntent(),
            )
            CorePermission.OVERLAY -> listOf(
                // 带包名可直接定位本应用；部分 ROM 不接受 data 参数，故再备一个不带 data 的
                createOverlaySettingsIntent(withPackage = true),
                createOverlaySettingsIntent(withPackage = false),
                createAppDetailsSettingsIntent(),
            )
        }
        launchWithFallback(candidates)
    }

    /**
     * 跳转通知权限设置（API 26+ 有应用级通知设置页），失败降级到应用详情页。
     * 建议在申请 POST_NOTIFICATIONS 被用户拒绝（shouldShowRequestPermission 为 false）后使用。
     */
    fun launchNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, appContext.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchWithFallback(listOf(intent, createAppDetailsSettingsIntent()))
    }

    /**
     * 跳转电池优化白名单：
     * 首选直接弹出「允许忽略电池优化」系统对话框，其次进入白名单列表，最后降级应用详情页。
     */
    fun launchBatteryOptimizationSettings() {
        val requestDialog = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val whitelist = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        launchWithFallback(listOf(requestDialog, whitelist, createAppDetailsSettingsIntent()))
    }

    /** 直接跳转本应用的系统详情页（最终降级页，AOSP 与各厂商 ROM 均支持）。 */
    fun launchAppDetailsSettings() {
        launchWithFallback(listOf(createAppDetailsSettingsIntent()))
    }

    // ---------------------------------------------------------------------------------
    // 私有 Intent 构造器
    // ---------------------------------------------------------------------------------

    /**
     * 「设置 - 安全/隐私 - 使用情况访问权限」页面。
     * 该 Action 不支持携带包名参数（部分 ROM 携带后会直接拒绝解析），故不加 data。
     */
    private fun createUsageAccessSettingsIntent(): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 「显示在其他应用上层」开关页。
     *
     * @param withPackage 是否携带 `package:<包名>` data；携带后 AOSP 与多数 ROM
     *                    会直接定位到本应用的开关项，个别 ROM 解析失败时改用不带 data 的列表页。
     */
    private fun createOverlaySettingsIntent(withPackage: Boolean): Intent {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) {
            intent.data = Uri.fromParts("package", appContext.packageName, null)
        }
        return intent
    }

    /** 应用详情页 Intent。 */
    private fun createAppDetailsSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", appContext.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 按候选顺序尝试启动 Intent。
     *
     * 不使用 [Intent.resolveActivity] 预判：Android 11+ 包可见性限制会让系统设置页
     * 在未声明 QUERY_ALL_PACKAGES 时返回 null，从而误判「不可跳转」。
     * 直接 try/catch [ActivityNotFoundException] 是最稳妥的跨 ROM 做法。
     */
    private fun launchWithFallback(candidates: List<Intent>) {
        for (intent in candidates) {
            try {
                appContext.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // 当前 ROM 不支持该 Action，尝试下一级降级页
            } catch (_: SecurityException) {
                // 个别 ROM 对后台跳转设置页有限制，继续尝试降级页
            }
        }
    }

    companion object {
        /**
         * 推荐工厂方法：内部统一收敛为 applicationContext，
         * 避免调用方误传 Activity 造成泄漏。
         */
        fun from(context: Context): PermissionManager =
            PermissionManager(context.applicationContext)
    }
}
