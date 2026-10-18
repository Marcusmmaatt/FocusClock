package com.sessionclock.core.interaction

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * # 强退桌面执行器（PRD 5.2 / 文档 3.1）
 *
 * 强拦截遮罩驻留满 1.5 秒后，静默派发 HOME Intent 将用户退回系统桌面：
 *
 *     Intent(ACTION_MAIN) + CATEGORY_HOME + FLAG_ACTIVITY_NEW_TASK
 *
 * 不直接 finish 目标应用（无权限），而是把 Launcher 拉到前台，
 * 配合 5 分钟冷静期监听实现等效强退。启动失败仅记录日志，不崩溃。
 */
object HomeNavigator {

    private const val TAG = "HomeNavigator"

    private val homeIntent: Intent = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_HOME)
        .setFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                // ISSUE-02：复位任务栈并清栈顶，确保 Launcher 被真正拉到前台，
                // 而不是把目标应用任务以原状态留在可立即返回的位置
                or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                or Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )

    fun goHome(context: Context) {
        runCatching {
            context.applicationContext.startActivity(homeIntent)
        }.onFailure {
            Log.e(TAG, "返回桌面失败", it)
        }
    }
}
