package com.sessionclock.core.di

import android.content.Context
import com.sessionclock.core.permission.PermissionManager
import com.sessionclock.core.time.SystemTimeProvider
import com.sessionclock.data.CooldownStore
import com.sessionclock.data.SettingsRepository
import com.sessionclock.engine.MonitorEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * # 手动依赖容器（轻量 Service Locator）
 *
 * MVP 工程类数量少，不引入 Hilt/KSP，降低构建复杂度。
 * 全进程单例，由 [com.sessionclock.SessionClockApp] 创建并持有。
 *
 * [MonitorEngine] 懒加载：UI（只读快照）与前台服务（start/stop）拿到的是同一实例，
 * 服务被销毁重建时引擎状态不丢失。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** 进程级协程作用域，独立于任何 Service / Activity 生命周期 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val permissionManager: PermissionManager = PermissionManager.from(appContext)
    val settingsRepository: SettingsRepository = SettingsRepository(appContext)
    val cooldownStore: CooldownStore = CooldownStore(appContext)

    @Volatile
    private var monitorEngine: MonitorEngine? = null

    fun requireEngine(): MonitorEngine {
        monitorEngine?.let { return it }
        return synchronized(this) {
            monitorEngine?.let { return it }
            MonitorEngine(
                appContext = appContext,
                appScope = applicationScope,
                clock = SystemTimeProvider,
                settingsRepository = settingsRepository,
                cooldownStore = cooldownStore,
            ).also { monitorEngine = it }
        }
    }
}
