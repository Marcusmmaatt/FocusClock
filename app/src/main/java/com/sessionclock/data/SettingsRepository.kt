package com.sessionclock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sessionclock.data.model.InterruptMode
import com.sessionclock.data.model.SessionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 顶层委托：整个进程唯一一个 DataStore 实例（重复创建会抛异常）。 */
private val Context.sessionClockDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "sessionclock_settings",
)

/**
 * # 配置仓库（PRD NFR-2 / F-12）
 *
 * 持久化内容：总开关、目标应用包名/名称、两条时长阈值、打断模式。
 * **不持久化**任何秒表累计值与浏览内容；全局计时器按拍板结论不做跨进程落盘。
 * 冷静期绝对时间戳也不放在这里（DataStore 异步写盘不保证强退/重启前落盘），
 * 由 [CooldownStore] 同步文件承载。
 */
class SettingsRepository(private val appContext: Context) {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val TARGET_PACKAGE = stringPreferencesKey("target_package")
        val TARGET_LABEL = stringPreferencesKey("target_label")
        val TARGET_LIMIT_MIN = intPreferencesKey("target_limit_min")
        val GLOBAL_LIMIT_MIN = intPreferencesKey("global_limit_min")
        val GLOBAL_SCREEN_ENABLED = booleanPreferencesKey("global_screen_enabled")
        val INTERRUPT_MODE = stringPreferencesKey("interrupt_mode")
    }

    /** 配置热流：UI 开关、滑块与后台引擎共同订阅，写入后各方自动生效。 */
    val configFlow: Flow<SessionConfig> =
        appContext.sessionClockDataStore.data
            .catch { error ->
                // DataStore 读盘异常时回落为默认配置，绝不让引擎崩溃（EC-05 同源原则）
                if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
                else throw error
            }
            .map { prefs ->
                SessionConfig(
                    masterEnabled = prefs[Keys.MASTER_ENABLED] ?: true,
                    targetPackage = prefs[Keys.TARGET_PACKAGE],
                    targetLabel = prefs[Keys.TARGET_LABEL] ?: "",
                    targetLimitMinutes = prefs[Keys.TARGET_LIMIT_MIN] ?: 15,
                    globalLimitMinutes = prefs[Keys.GLOBAL_LIMIT_MIN] ?: 30,
                    globalScreenEnabled = prefs[Keys.GLOBAL_SCREEN_ENABLED] ?: true,
                    interruptMode = InterruptMode.fromName(prefs[Keys.INTERRUPT_MODE]),
                )
            }

    /** 一次性读取当前配置（BootReceiver 在短超时内使用）。 */
    suspend fun currentConfig(): SessionConfig = configFlow.first()

    suspend fun setMasterEnabled(enabled: Boolean) = edit { it[Keys.MASTER_ENABLED] = enabled }

    suspend fun setTargetApp(packageName: String?, displayLabel: String) = edit { prefs ->
        if (packageName == null) {
            prefs.remove(Keys.TARGET_PACKAGE)
            prefs.remove(Keys.TARGET_LABEL)
        } else {
            prefs[Keys.TARGET_PACKAGE] = packageName
            prefs[Keys.TARGET_LABEL] = displayLabel
        }
    }

    /** UI 层已做范围校验，此处再兜底 coerce 到合法区间。 */
    suspend fun setTargetLimitMinutes(minutes: Int) = edit {
        it[Keys.TARGET_LIMIT_MIN] = minutes.coerceIn(5, 60)
    }

    suspend fun setGlobalLimitMinutes(minutes: Int) = edit {
        it[Keys.GLOBAL_LIMIT_MIN] = minutes.coerceIn(10, 120)
    }

    /** ISSUE-04：全局亮屏独立开关持久化。 */
    suspend fun setGlobalScreenEnabled(enabled: Boolean) = edit {
        it[Keys.GLOBAL_SCREEN_ENABLED] = enabled
    }

    suspend fun setInterruptMode(mode: InterruptMode) = edit {
        it[Keys.INTERRUPT_MODE] = mode.name
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        appContext.sessionClockDataStore.edit(block)
    }
}
