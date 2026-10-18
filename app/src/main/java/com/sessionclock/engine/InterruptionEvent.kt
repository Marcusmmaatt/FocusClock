package com.sessionclock.engine

/**
 * 打断发生的作用域：目标应用防沉迷 / 全局亮屏提醒。
 */
enum class ReminderScope {
    TARGET_APP,
    GLOBAL_SCREEN,
}

/**
 * # 引擎对外发出的一次性打断事件（Sprint 3 由 OverlayManager / HomeNavigator / HapticController 订阅执行）
 *
 * Sprint 2 阶段前台服务仅记录日志，**不会真的弹遮罩或强退用户**，
 * 以便先在真机上安全验证状态机时序。
 */
sealed interface InterruptionEvent {

    /**
     * 挂载 C01 软提醒悬浮窗。
     * @param appLabel 目标应用名（全局提醒时为「手机」）
     * @param limitMs 触发阈值，用于文案拼接「超过 X 分钟」
     */
    data class ShowSoftReminder(
        val scope: ReminderScope,
        val appLabel: String,
        val limitMs: Long,
    ) : InterruptionEvent

    /**
     * 挂载 C02 全屏强拦截遮罩。
     * @param cooldownEpochMs 冷静期截止的绝对时间戳（遮罩倒计时以此为准，支持重启恢复）
     * @param cooldownDeadlineElapsed 本次开机周期内的单调时钟截止点（运行期防改表辅助）
     */
    data class ShowHardBlock(
        val cooldownEpochMs: Long,
        val cooldownDeadlineElapsed: Long,
    ) : InterruptionEvent

    /** 遮罩驻留满 1.5s，执行 ACTION_MAIN + CATEGORY_HOME 强退桌面。 */
    data object PerformGoHome : InterruptionEvent
}
