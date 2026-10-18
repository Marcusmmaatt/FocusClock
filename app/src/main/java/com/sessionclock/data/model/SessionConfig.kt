package com.sessionclock.data.model

/**
 * 打断模式（PRD 4.4「打断模式分流」）。
 * 默认 [HARD_BLOCK]：PRD 低保真 P01 中强拦截为默认勾选项。
 */
enum class InterruptMode {
    /** 模式一：软提醒悬浮窗，确认后计时归零并顺延 5 分钟 */
    SOFT_REMINDER,

    /** 模式二：全屏强拦截遮罩 + 强退桌面 + 300s 冷静期 */
    HARD_BLOCK;

    companion object {
        /** DataStore 只存枚举名字符串；读取异常 / 历史缺省值时回落到默认强拦截。 */
        fun fromName(name: String?): InterruptMode =
            entries.firstOrNull { it.name == name } ?: HARD_BLOCK
    }
}

/**
 * 全局会话配置（DataStore 持久化的全部键值，PRD NFR-2：仅存必要状态）。
 *
 * @param targetPackage 目标应用包名；null 表示尚未选择（P01 显示「未选择」）
 * @param targetLabel   目标应用显示名，用于浮窗文案拼接
 */
data class SessionConfig(
    val masterEnabled: Boolean = true,
    val targetPackage: String? = null,
    val targetLabel: String = "",
    /** 目标应用单次上限（分钟）：5~60，步进 5，默认 15 */
    val targetLimitMinutes: Int = 15,
    /** 全局亮屏单次上限（分钟）：10~120，步进 5，默认 30 */
    val globalLimitMinutes: Int = 30,
    /** ISSUE-04：全局亮屏独立开关；false 时挂起全局亮屏计时与软提醒，默认开启 */
    val globalScreenEnabled: Boolean = true,
    val interruptMode: InterruptMode = InterruptMode.HARD_BLOCK,
) {
    val targetLimitMs: Long get() = targetLimitMinutes * MILLIS_PER_MINUTE
    val globalLimitMs: Long get() = globalLimitMinutes * MILLIS_PER_MINUTE

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
