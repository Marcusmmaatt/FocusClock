package com.sessionclock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sessionclock.data.model.SessionConfig
import com.sessionclock.monitor.InstalledApp
import com.sessionclock.ui.dashboard.DashboardScreen
import com.sessionclock.ui.guide.KeepAliveGuideScreen
import com.sessionclock.ui.picker.AppPickerScreen
import com.sessionclock.ui.settings.SettingsScreen
import com.sessionclock.ui.theme.SessionClockTheme
import kotlinx.coroutines.launch

/**
 * 单 Activity 宿主：以轻量页面状态切换承载四个目的地
 * （P01 控制台 / P02 应用选择器 / 设置页 / 保活指引页），
 * 不引入导航框架开销。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SessionClockTheme {
                AppRoot()
            }
        }
    }
}

/**
 * 应用级页面路由（无参目的地，选择结果通过 DataStore 回流，无需传参）。
 * ISSUE-05：新增 [TopScreen.SETTINGS]。
 */
private enum class TopScreen { DASHBOARD, APP_PICKER, SETTINGS, KEEP_ALIVE_GUIDE }

@androidx.compose.runtime.Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val composeScope = rememberCoroutineScope()
    val container = remember(context) {
        (context.applicationContext as SessionClockApp).container
    }
    val config by container.settingsRepository.configFlow.collectAsStateWithLifecycle(
        initialValue = SessionConfig(),
    )

    var screen by remember { mutableStateOf(TopScreen.DASHBOARD) }
    // 保活指引页从「设置」进入时，返回应回到设置页而非控制台
    var guideReturnScreen by remember { mutableStateOf(TopScreen.SETTINGS) }

    // 非控制台页面拦截系统返回键：设置 / 选择器 / 指引页统一先回控制台，
    // 指引页从设置进入时回设置页
    BackHandler(enabled = screen != TopScreen.DASHBOARD) {
        screen = if (screen == TopScreen.KEEP_ALIVE_GUIDE) guideReturnScreen
        else TopScreen.DASHBOARD
    }

    when (screen) {
        TopScreen.DASHBOARD -> DashboardScreen(
            onOpenPicker = { screen = TopScreen.APP_PICKER },
            onOpenSettings = { screen = TopScreen.SETTINGS },
        )

        TopScreen.APP_PICKER -> AppPickerScreen(
            selectedPackage = config.targetPackage,
            onSelected = { app: InstalledApp ->
                composeScope.launch {
                    container.settingsRepository.setTargetApp(app.packageName, app.label)
                }
                screen = TopScreen.DASHBOARD
            },
            onBack = { screen = TopScreen.DASHBOARD },
        )

        TopScreen.SETTINGS -> SettingsScreen(
            onBack = { screen = TopScreen.DASHBOARD },
            onOpenKeepAliveGuide = {
                guideReturnScreen = TopScreen.SETTINGS
                screen = TopScreen.KEEP_ALIVE_GUIDE
            },
        )

        TopScreen.KEEP_ALIVE_GUIDE -> KeepAliveGuideScreen(
            onBack = { screen = guideReturnScreen },
        )
    }
}
