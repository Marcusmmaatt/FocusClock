package com.sessionclock.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * C02 强拦截全屏遮罩内容（PRD 5.2 / 原型 2.4）。
 *
 * - 不透明深灰背景 #1A1A1A（由组件自绘，不依赖暗色主题，夜间不刺眼）；
 * - 主标题 28sp 加粗「🛑 我想静静」；
 * - 副标题 15sp 浅灰，动态拼接 X 分钟；
 * - 倒计时数字 40sp **等宽字体（Monospace）**，从 05:00 秒级递减，
 *   等宽数字保证字符宽度不变、文本不横向抖动（NFR UI 鲁棒性）；
 * - 底部 13sp「正在退出应用并返回桌面...」。
 *
 * 全屏触控与 Back 拦截、状态栏 / 导航栏隐藏由窗口层完成。
 *
 * @param remainingMs 冷静期剩余毫秒（由引擎快照驱动，0.5s 刷新）
 * @param limitMinutes 本次触发的使用上限（分钟），用于副标题
 */
@Composable
internal fun HardBlockShieldContent(
    remainingMs: Long,
    limitMinutes: Int,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ShieldBackgroundColor),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "🛑 我想静静",
                color = Color.White,
                fontSize = TITLE_SP.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "本次使用已达 $limitMinutes 分钟上限，大脑需要从多巴胺刺激中恢复。",
                color = SubtitleColor,
                fontSize = SUBTITLE_SP.sp,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "⏳ 冷静倒计时",
                color = SubtitleColor,
                fontSize = LABEL_SP.sp,
            )
            Text(
                text = formatMmSs(remainingMs),
                color = Color.White,
                fontSize = COUNTDOWN_SP.sp,
                fontWeight = FontWeight.Medium,
                // 等宽字体：数字位宽恒定，04:11 → 04:10 不抖动
                fontFamily = FontFamily.Monospace,
            )
        }

        Text(
            text = "正在退出应用并返回桌面...",
            color = SubtitleColor,
            fontSize = FOOTER_SP.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = FOOTER_BOTTOM_DP.dp),
        )
    }
}

/** mm:ss 格式化（倒计时专用，最小显示 00:00）。 */
internal fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d".format(minutes, seconds)
}

private val ShieldBackgroundColor = Color(0xFF1A1A1A)
private val SubtitleColor = Color(0xFFB0B0B0)

private const val TITLE_SP = 28
private const val SUBTITLE_SP = 15
private const val LABEL_SP = 14
private const val COUNTDOWN_SP = 40
private const val FOOTER_SP = 13
private const val FOOTER_BOTTOM_DP = 48
