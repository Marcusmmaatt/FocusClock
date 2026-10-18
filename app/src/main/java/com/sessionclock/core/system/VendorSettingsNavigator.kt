package com.sessionclock.core.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * # 厂商专属设置页导航器（保活指引页使用）
 *
 * 国内深度定制 ROM 没有公开 SDK 暴露「自启动管理」页面，
 * 业界通行做法是按厂商安全中心 App 的已知 ComponentName 直接拉起，
 * 失败（版本改类名 / 组件禁用）时返回 false，由 UI 降级展示手动步骤。
 *
 * 全部跳转 try/catch，绝不崩溃（EC-05）。
 */
object VendorSettingsNavigator {

    /** 厂商识别归一化后的品牌标识。 */
    enum class Vendor(val displayName: String) {
        XIAOMI("小米 (HyperOS / MIUI)"),
        HUAWEI("华为 (HarmonyOS / EMUI)"),
        OPPO("OPPO / 一加 (ColorOS)"),
        VIVO("vivo / iQOO (OriginOS / Funtouch OS)"),
        MEIZU("魅族 (Flyme)"),
        SAMSUNG("三星 (One UI)"),
        OTHER("其他 Android 设备"),
    }

    fun detectVendor(): Vendor {
        val brand = Build.BRAND?.lowercase().orEmpty()
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") ||
                manufacturer.contains("xiaomi") -> Vendor.XIAOMI

            brand.contains("huawei") || brand.contains("honor") ||
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> Vendor.HUAWEI

            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ||
                manufacturer.contains("oppo") || manufacturer.contains("oneplus") -> Vendor.OPPO

            brand.contains("vivo") || brand.contains("iqoo") ||
                manufacturer.contains("vivo") -> Vendor.VIVO

            brand.contains("meizu") || manufacturer.contains("meizu") -> Vendor.MEIZU
            brand.contains("samsung") || manufacturer.contains("samsung") -> Vendor.SAMSUNG
            else -> Vendor.OTHER
        }
    }

    /**
     * 尝试拉起厂商「自启动 / 后台管理」页面。
     * @return true 表示成功拉起；false 表示当前 ROM 无已知入口，调用方应提示手动路径。
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val components = when (detectVendor()) {
            Vendor.XIAOMI -> listOf(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
            Vendor.OPPO -> listOf(
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                ComponentName(
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity",
                ),
            )
            Vendor.VIVO -> listOf(
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
                ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            )
            Vendor.HUAWEI -> listOf(
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )
            Vendor.MEIZU -> listOf(
                ComponentName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SHOW_APPSEC",
                ),
            )
            Vendor.SAMSUNG -> listOf(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            )
            Vendor.OTHER -> emptyList()
        }

        for (component in components) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.applicationContext.startActivity(intent)
                return true
            } catch (_: Exception) {
                // 该 ROM 版本中类名不存在或 Activity 被禁用，继续尝试下一个候选
            }
        }
        return false
    }

    /**
     * 厂商手动设置路径文案（自动拉起失败时展示）。
     */
    fun manualSteps(vendor: Vendor): List<String> = when (vendor) {
        Vendor.XIAOMI -> listOf(
            "打开「手机管家」→「应用管理」→「权限」→「自启动管理」",
            "找到 FocusClock 并允许后台运行 / 自启动",
            "最近任务页长按 FocusClock 卡片，加锁防止被一键清理",
        )
        Vendor.HUAWEI -> listOf(
            "打开「设置」→「应用和服务」→「应用启动管理」",
            "将 FocusClock 改为手动管理，并允许自启动 / 关联启动 / 后台活动",
            "最近任务页给 FocusClock 卡片加锁",
        )
        Vendor.OPPO -> listOf(
            "打开「手机管家」→「权限与隐私」→「自启动管理」",
            "允许 FocusClock 自启动",
            "最近任务页锁定 FocusClock，并在电池设置中允许后台运行",
        )
        Vendor.VIVO -> listOf(
            "打开「i 管家」→「应用管理」→「权限管理」→「自启动」",
            "允许 FocusClock 后台启动",
            "最近任务页下滑 FocusClock 卡片加锁，并在电池中设为允许后台高耗电",
        )
        Vendor.MEIZU -> listOf(
            "打开「手机管家」→「权限管理」→「后台管理」",
            "将 FocusClock 设为「允许后台运行」",
        )
        Vendor.SAMSUNG -> listOf(
            "打开「设置」→「电池和设备维护」→「电池」→「后台使用限制」",
            "将 FocusClock 加入「从不休眠的应用」",
        )
        Vendor.OTHER -> listOf(
            "在系统「设置」→「应用」→ FocusClock →「电池 / 耗电管理」中",
            "允许后台运行、后台活动，并关闭对本应用的电池优化",
            "如有自启动管理入口，请一并允许 FocusClock 自启动",
        )
    }
}
