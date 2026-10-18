package com.sessionclock.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * SessionClock 正式 Material 3 配色（Sprint 4）。
 *
 * 品牌基调：冷静、克制的靛蓝（数字健康 / 专注），辅以青绿色表示「恢复 / 完成」；
 * 强拦截色（#1A1A1A 遮罩）不进入主题，由 C02 组件固定自绘，保证夜间不刺眼。
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF00174C),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF171A2C),
    tertiary = Color(0xFF0E8C7F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8F2E6),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFD7263D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB3C5FF),
    onPrimary = Color(0xFF002B6E),
    primaryContainer = Color(0xFF1E4093),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFF9CD6CB),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFFB8F2E6),
    error = Color(0xFFFFB3B0),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline = Color(0xFF90909A),
)

/** 应用主题：默认跟随系统暗色模式（交互规范第 4 节暗色自适应）。 */
@Composable
fun SessionClockTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
