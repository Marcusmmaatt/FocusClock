package com.sessionclock.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sessionclock.SessionClockApp
import com.sessionclock.data.model.InterruptMode
import com.sessionclock.data.model.SessionConfig
import kotlinx.coroutines.launch

/**
 * # 设置页（ISSUE-05 新增）
 *
 * 从控制台 TopAppBar 右侧设置图标进入，包含两个模块：
 * 1. 【打断模式】单选：「仅提醒」与「强制退出（推荐）」，选择即时写 DataStore，
 *    引擎经 configFlow 热更新，无需重启服务；
 * 2. 【系统保活】入口：跳转 KeepAliveGuideScreen。
 *
 * @param onBack 返回控制台
 * @param onOpenKeepAliveGuide 跳转系统保活指引页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeepAliveGuide: () -> Unit,
) {
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()
    val container = remember(context) {
        (context.applicationContext as SessionClockApp).container
    }
    val config by container.settingsRepository.configFlow.collectAsStateWithLifecycle(
        initialValue = SessionConfig(),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // ============================== 模块一：打断模式 ==============================
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("打断模式", style = MaterialTheme.typography.titleMedium)

                    ModeRadioRow(
                        selected = config.interruptMode == InterruptMode.SOFT_REMINDER,
                        title = "仅提醒",
                        description = "使用时长达到设定上限后会弹出提醒，确认后顺延5分钟并再次提醒",
                        onSelect = {
                            composeScope.launch {
                                container.settingsRepository
                                    .setInterruptMode(InterruptMode.SOFT_REMINDER)
                            }
                        },
                    )
                    ModeRadioRow(
                        selected = config.interruptMode == InterruptMode.HARD_BLOCK,
                        title = "强制退出（推荐）",
                        description = "直接退出应用并锁定5分钟",
                        onSelect = {
                            composeScope.launch {
                                container.settingsRepository
                                    .setInterruptMode(InterruptMode.HARD_BLOCK)
                            }
                        },
                    )
                }
            }

            // ============================== 模块二：系统保活 ==============================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenKeepAliveGuide),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("防杀后台必要设置", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "自启动、电池白名单、通知权限一键检查",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "打开保活指引",
                    )
                }
            }
        }
    }
}

/** 打断模式单选项：单选圆点 + 标题 + 描述。 */
@Composable
private fun ModeRadioRow(
    selected: Boolean,
    title: String,
    description: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
