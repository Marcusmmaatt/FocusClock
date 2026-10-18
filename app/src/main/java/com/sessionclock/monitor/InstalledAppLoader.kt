package com.sessionclock.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 已安装的可启动应用（P02 应用选择器数据模型）。
 */
data class InstalledApp(
    val label: String,
    val packageName: String,
)

/**
 * # 已安装应用加载器（PRD 4.1 / F-05）
 *
 * - 仅查询带 `ACTION_MAIN + CATEGORY_LAUNCHER` 的可启动应用，
 *   配合 Manifest 中的 `<queries>` 声明，无需 QUERY_ALL_PACKAGES 敏感权限；
 * - 天然过滤无界面系统服务、壁纸提供器等；
 * - 按包名去重（一个包可能有多个 Launcher Activity，别名场景）；
 * - 按显示名排序；PackageManager 调用放在 IO 线程，避免拖慢首帧。
 */
object InstalledAppLoader {

    suspend fun loadLauncherApps(context: Context): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val packageManager = appContext.packageManager

            val launcherIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)

            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }

            resolved.asSequence()
                .map { resolveInfo ->
                    InstalledApp(
                        label = resolveInfo.loadLabel(packageManager).toString(),
                        packageName = resolveInfo.activityInfo.packageName,
                    )
                }
                .distinctBy { it.packageName }
                // 排除本应用自身（release 包与 .debug 后缀包统一前缀过滤）
                .filterNot { it.packageName == appContext.packageName }
                .filterNot { it.packageName.startsWith(SELF_PACKAGE_PREFIX) }
                .sortedBy { it.label }
                .toList()
        }

    private const val SELF_PACKAGE_PREFIX = "com.sessionclock"
}
