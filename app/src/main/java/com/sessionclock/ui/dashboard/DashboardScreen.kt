package com.sessionclock.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sessionclock.SessionClockApp
import com.sessionclock.engine.model.GlobalStatus
import com.sessionclock.engine.model.MonitorSnapshot
import com.sessionclock.engine.model.TargetStatus
import com.sessionclock.service.MonitorForegroundService
import com.sessionclock.ui.permission.PermissionBanner
import kotlinx.coroutines.launch

/**
 * P01 监控控制台（PRD 4.1 / 原型 2.1，v1.1 ISSUE-05 减负版）。
 *
 * 区域：
 * - A 权限警示条（未授权常驻，点击分级跳转）；
 * - B 监控总开关；
 * - C 监控应用选择（点击进入 P02）+ 单次使用上限滑块（5~60 分钟，步进 5）；
 * - D 全局亮屏：独立开关（ISSUE-04）+ 单次亮屏上限滑块（10~120 分钟，步进 5），
 *   开关关闭时滑块禁用；
 * - E 应用状态（实时状态栏）。
 *
 * v1.1 已移除「打断模式单选」与「防杀后台必要设置」，二者迁入设置页。
 * 所有配置写入 DataStore 后经引擎 configFlow 热更新，无需重启服务。
 *
 * @param onOpenPicker 跳转 P02 应用选择器
 * @param onOpenSettings 跳转设置页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onOpenPicker: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val composeScope = rememberCoroutineScope()

    val container = remember(context) {
        (context.applicationContext as SessionClockApp).container
    }
    val engine = remember(container) { container.requireEngine() }

    val permissionState by container.permissionManager.state
        .collectAsStateWithLifecycle()
    val snapshot by engine.snapshot.collectAsStateWithLifecycle()
    val config = snapshot.config

    // 进入页面 / 从系统设置页返回时刷新权限（F-01 AC-3：授权返回 1 秒内警示条消隐）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                container.permissionManager.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首次进入拉起前台服务（默认开关开启）
    LaunchedEffect(Unit) {
        MonitorForegroundService.start(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FocusClock", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 区域 A：权限警示条
            PermissionBanner(
                state = permissionState,
                onClick = {
                    val missing = container.permissionManager.missingCorePermissions().firstOrNull()
                    missing?.let { container.permissionManager.launch(it) }
                },
            )

            // 区域 B：监控总开关
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("监控总开关", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "关闭后停止检测并移除常驻通知",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = config.masterEnabled,
                        onCheckedChange = { enabled ->
                            composeScope.launch {
                                container.settingsRepository.setMasterEnabled(enabled)
                            }
                            if (enabled) {
                                MonitorForegroundService.start(context)
                            } else {
                                MonitorForegroundService.stop(context)
                            }
                        },
                    )
                }
            }

            // 区域 C：监控应用
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("监控应用", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenPicker)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = config.targetLabel.ifBlank { "未选择应用" },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = config.targetPackage
                                    ?: "点击选择需要监控使用时长的应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "选择应用",
                        )
                    }
                    if (config.targetPackage != null) {
                        TextButton(
                            onClick = {
                                composeScope.launch {
                                    container.settingsRepository.setTargetApp(null, "")
                                }
                            },
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                            Text("  清除选择")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    StepSlider(
                        title = "单次使用上限",
                        valueMinutes = config.targetLimitMinutes,
                        range = 5..60,
                        stepMinutes = 5,
                        enabled = true,
                        onCommit = { minutes ->
                            composeScope.launch {
                                container.settingsRepository.setTargetLimitMinutes(minutes)
                            }
                        },
                    )
                }
            }

            // 区域 D：全局亮屏（独立开关 + 滑块）
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("全局亮屏", style = MaterialTheme.typography.titleMedium)
                        // ISSUE-04：全局亮屏独立开关
                        Switch(
                            checked = config.globalScreenEnabled,
                            onCheckedChange = { enabled ->
                                composeScope.launch {
                                    container.settingsRepository.setGlobalScreenEnabled(enabled)
                                }
                            },
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    StepSlider(
                        title = "单次亮屏上限",
                        valueMinutes = config.globalLimitMinutes,
                        range = 10..120,
                        stepMinutes = 5,
                        // 开关关闭时禁用滑块（计时与软提醒同步挂起）
                        enabled = config.globalScreenEnabled,
                        onCommit = { minutes ->
                            composeScope.launch {
                                container.settingsRepository.setGlobalLimitMinutes(minutes)
                            }
                        },
                    )
                }
            }

            // 区域 E：应用状态
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("应用状态", style = MaterialTheme.typography.titleMedium)
                    Text(text = targetStatusText(snapshot), style = MaterialTheme.typography.bodyMedium)
                    Text(text = globalStatusText(snapshot), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * 步进式时长滑块。拖动过程中只更新本地状态（避免高频写 DataStore），
 * 松手（onValueChangeFinished）时一次性持久化。
 *
 * @param enabled false 时滑块与数值整体禁用（全局亮屏开关关闭场景）
 */
@Composable
private fun StepSlider(
    title: String,
    valueMinutes: Int,
    range: IntRange,
    stepMinutes: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember(valueMinutes) { mutableFloatStateOf(valueMinutes.toFloat()) }
    val stepCount = ((range.last - range.first) / stepMinutes) - 1

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) ColorUnspecified
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${dragging.toInt()} 分钟",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onCommit(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            // Material3 Slider 的 steps = 首尾之间的离散点数
            steps = stepCount,
            enabled = enabled,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${range.first} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${range.last} 分钟",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 标题在启用态使用主题默认文字色（不强制指定）。 */
private val ColorUnspecified = androidx.compose.ui.graphics.Color.Unspecified

/** 目标应用状态 → 区域 E 文案（ISSUE-06 定稿）。 */
private fun targetStatusText(snapshot: MonitorSnapshot): String {
    val label = snapshot.config.targetLabel.ifBlank { "未选择应用" }
    return when (val status = snapshot.target) {
        is TargetStatus.Idle -> "⚪ 应用: 未启动（$label）"
        is TargetStatus.Running ->
            "🟢 应用: $label 运行中 ${formatMmSs(status.elapsedMs)}" +
                if (status.snoozeRemainingMs > 0L) "（顺延5分钟）" else ""
        TargetStatus.SoftTriggered -> "🟡 应用: $label 规定时长已达上限"
        is TargetStatus.Buffering ->
            "🟠 应用: 已切出，容差 ${formatMmSs(status.bufferRemainingMs)}"
        is TargetStatus.HardTriggering -> "🛑 应用: 拦截中…"
        is TargetStatus.Cooldown ->
            "🛑 应用: 冷静期剩余 ${formatMmSs(status.remainingMs)}"
    }
}

/** 全局亮屏计时状态 → 文案（ISSUE-06 定稿）。 */
private fun globalStatusText(snapshot: MonitorSnapshot): String =
    when (val status = snapshot.global) {
        is GlobalStatus.Idle -> "🌙 全局: 手机未解锁 / 已熄屏"
        is GlobalStatus.Running ->
            "📱 全局: 本次亮屏时间 ${formatMmSs(status.elapsedMs)}"
        GlobalStatus.SoftTriggered -> "📱 全局: 已达上限，等待确认"
    }

private fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
