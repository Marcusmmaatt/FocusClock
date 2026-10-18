package com.sessionclock.ui.guide

import android.widget.Toast
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sessionclock.SessionClockApp
import com.sessionclock.core.system.VendorSettingsNavigator

/**
 * 厂商自启动与后台保活指引页（PRD 4.2 保活策略 / F-02 配套）。
 *
 * 内容：
 * 1. 自动识别厂商 ROM，一键尝试拉起「自启动管理」页（失败降级手动步骤）；
 * 2. 电池优化白名单状态实时展示与跳转；
 * 3. 通知权限、最近任务加锁等手动清单；
 * 4. 底部声明兼容范围（不支持 HarmonyOS NEXT）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepAliveGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = (context.applicationContext as SessionClockApp).container
    val permissionManager = container.permissionManager

    val permissionState by permissionManager.state.collectAsStateWithLifecycle()
    val vendor = VendorSettingsNavigator.detectVendor()
    val steps = VendorSettingsNavigator.manualSteps(vendor)

    // 从系统设置页返回时刷新电池白名单 / 通知权限状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionManager.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("后台保活设置指引") },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "安卓系统默认会清理后台应用。为保证运行，请按以下步骤完成设置。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 一、自启动管理
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("① 允许自启动", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "已识别设备：${vendor.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            val opened = VendorSettingsNavigator.openAutoStartSettings(context)
                            if (!opened) {
                                Toast.makeText(
                                    context,
                                    "未找到系统自启动页，请按下方步骤手动操作",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("一键打开自启动管理页") }
                    steps.forEachIndexed { index, step ->
                        Text(
                            text = "${index + 1}. $step",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            // 二、电池优化白名单
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("② 关闭电池优化", style = MaterialTheme.typography.titleMedium)
                    StatusRow(
                        done = permissionState.isBatteryOptimizationExempt,
                        doneText = "已加入电池优化白名单",
                        todoText = "尚未加入白名单",
                    )
                    OutlinedButton(
                        onClick = { permissionManager.launchBatteryOptimizationSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("前往电池优化设置") }
                }
            }

            // 三、通知权限
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("③ 开启通知（前台服务常驻可见）", style = MaterialTheme.typography.titleMedium)
                    StatusRow(
                        done = permissionState.hasPostNotifications,
                        doneText = "通知权限已开启",
                        todoText = "通知权限未开启",
                    )
                    OutlinedButton(
                        onClick = { permissionManager.launchNotificationSettings() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("前往通知设置") }
                }
            }

            // 四、最近任务加锁
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("④ 最近任务加锁", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "在系统「最近任务」界面找到 FocusClock，下拉或长按卡片进行加锁，" +
                            "防止一键清理时被划掉。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text(
                text = "兼容范围：Android 9.0 (API 28) 及以上，含 HarmonyOS 4.x 及以下 AOSP 兼容层；" +
                    "不支持 HarmonyOS NEXT（纯血鸿蒙）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusRow(done: Boolean, doneText: String, todoText: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(start = 8.dp))
        Text(
            text = if (done) doneText else todoText,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
