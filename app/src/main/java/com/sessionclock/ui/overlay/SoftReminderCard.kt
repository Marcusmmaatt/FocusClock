package com.sessionclock.ui.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * C01 软提醒悬浮卡片（PRD 5.1 / 原型 2.3）。
 *
 * 视觉规格：
 * - 屏幕居中，宽度 85%，最大宽度 360dp，16dp 圆角；
 * - 主标题 20sp 加粗「休息一下好不好」；
 * - 正文 14sp 灰体，按作用域使用定稿文案（ISSUE-06）；
 * - 主按钮高 48dp、横向撑满，文案「拜托你很弱哎」。
 *
 * 外部 60% 黑色遮罩与触摸拦截由 WindowManager 窗口参数 + InterceptRootView 承担，
 * 本组件只负责卡片本身；点击外部 / Back 的拦截在窗口层统一处理。
 *
 * @param bodyText 已确定的正文描述
 * @param onAcknowledge 点击【拜托你很弱哎】：销毁浮窗，计时归零并顺延 5 分钟
 */
@Composable
internal fun SoftReminderCard(
    bodyText: String,
    onAcknowledge: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(CARD_CORNER_DP.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(CARD_WIDTH_RATIO)
                .widthIn(max = CARD_MAX_WIDTH_DP.dp),
        ) {
            Column(
                modifier = Modifier.padding(CARD_PADDING_DP.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CARD_SPACING_DP.dp),
            ) {
                Text(
                    text = "休息一下好不好",
                    fontSize = TITLE_SP.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = bodyText,
                    fontSize = BODY_SP.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onAcknowledge,
                    shape = RoundedCornerShape(BUTTON_CORNER_DP.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BUTTON_HEIGHT_DP.dp),
                ) {
                    Text(text = "拜托你很弱哎", fontSize = BUTTON_TEXT_SP.sp)
                }
            }
        }
    }
}

private const val CARD_CORNER_DP = 16
private const val CARD_WIDTH_RATIO = 0.85f
private const val CARD_MAX_WIDTH_DP = 360
private const val CARD_PADDING_DP = 24
private const val CARD_SPACING_DP = 16
private const val TITLE_SP = 20
private const val BODY_SP = 14
private const val BUTTON_HEIGHT_DP = 48
private const val BUTTON_CORNER_DP = 12
private const val BUTTON_TEXT_SP = 16
