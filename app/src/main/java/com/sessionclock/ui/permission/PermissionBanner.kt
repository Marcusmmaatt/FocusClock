package com.sessionclock.ui.permission

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sessionclock.core.permission.PermissionState

/**
 * P01 区域 A：权限缺失琥珀色警示条（F-01）。
 *
 * - 两项核心权限均授予时不显示（返回 App 后 onResume 刷新，1 秒内自动消隐）；
 * - 点击后由调用方决定跳转顺序（优先使用情况访问权限，其次悬浮窗）。
 */
@Composable
fun PermissionBanner(
    state: PermissionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.coreGranted) return

    Surface(
        color = Color(0xFFFFB300),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "!", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "核心权限未开启",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "缺少“使用情况访问”或“悬浮窗”权限，点击立即前往开启",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(text = ">", style = MaterialTheme.typography.titleMedium)
        }
    }
}
