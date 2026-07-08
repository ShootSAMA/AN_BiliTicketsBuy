package com.example.an_biliticketsbuy.ui.screens

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.an_biliticketsbuy.service.FloatingWindowService
import com.example.an_biliticketsbuy.service.TicketAccessibilityService
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "HomeScreen"

@Composable
fun HomeScreen(
    onNavigateToPermissions: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current

    val calendar = remember { Calendar.getInstance() }
    var targetDateDisplay by remember { mutableStateOf("选择日期") }
    var targetTimeDisplay by remember { mutableStateOf("选择时间") }
    var targetTimeMillis by remember { mutableStateOf(0L) }
    var clickInterval by remember { mutableStateOf("100") }
    var maxRetry by remember { mutableStateOf("50") }

    var accessibilityEnabled by remember {
        mutableStateOf(TicketAccessibilityService.isServiceEnabled())
    }
    var overlayGranted by remember {
        mutableStateOf(Settings.canDrawOverlays(context))
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* granted or not, proceed */ }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = TicketAccessibilityService.isServiceEnabled()
                overlayGranted = Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "B站抢票助手",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
        )

        Text(
            text = "悬浮窗 + 屏幕点击方案",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // 权限状态
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("权限状态", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                PermissionRow("悬浮窗权限", overlayGranted) {
                    context.startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ))
                }
                Spacer(modifier = Modifier.height(4.dp))
                PermissionRow("无障碍服务", accessibilityEnabled) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            }
        }

        // 开售时间 — DatePicker + TimePicker
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("开售时间", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 日期选择器
                    OutlinedButton(
                        onClick = {
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    calendar.set(Calendar.YEAR, year)
                                    calendar.set(Calendar.MONTH, month)
                                    calendar.set(Calendar.DAY_OF_MONTH, day)
                                    targetDateDisplay = "$year-${(month + 1).toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
                                    targetTimeMillis = calendar.timeInMillis
                                },
                                calendar.get(Calendar.YEAR),
                                calendar.get(Calendar.MONTH),
                                calendar.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(targetDateDisplay, maxLines = 1)
                    }

                    // 时间选择器
                    OutlinedButton(
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute ->
                                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                                    calendar.set(Calendar.MINUTE, minute)
                                    calendar.set(Calendar.SECOND, 0)
                                    calendar.set(Calendar.MILLISECOND, 0)
                                    targetTimeDisplay = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}:00"
                                    targetTimeMillis = calendar.timeInMillis
                                },
                                calendar.get(Calendar.HOUR_OF_DAY),
                                calendar.get(Calendar.MINUTE),
                                true
                            ).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(targetTimeDisplay)
                    }
                }

                if (targetTimeMillis > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    Text(
                        text = "目标时间戳: $targetTimeMillis → ${sdf.format(Date(targetTimeMillis))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 抢票参数
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("抢票参数", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = clickInterval,
                        onValueChange = { clickInterval = it },
                        label = { Text("点击间隔(ms)") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxRetry,
                        onValueChange = { maxRetry = it },
                        label = { Text("最大重试") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 启动按钮
        Button(
            onClick = {
                if (!overlayGranted) {
                    Toast.makeText(context, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ))
                    return@Button
                }
                if (!accessibilityEnabled) {
                    Toast.makeText(context, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    return@Button
                }
                if (targetTimeMillis <= 0) {
                    Toast.makeText(context, "请先选择开售时间", Toast.LENGTH_LONG).show()
                    return@Button
                }

                val now = System.currentTimeMillis()
                Log.i(TAG, "启动: target=$targetTimeMillis, now=$now, remaining=${targetTimeMillis - now}ms")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                val interval = clickInterval.toLongOrNull() ?: 100L
                val retry = maxRetry.toIntOrNull() ?: 50

                val intent = Intent(context, FloatingWindowService::class.java).apply {
                    action = FloatingWindowService.ACTION_START
                    putExtra(FloatingWindowService.EXTRA_TARGET_TIME, targetTimeMillis)
                    putExtra(FloatingWindowService.EXTRA_CLICK_INTERVAL, interval)
                    putExtra(FloatingWindowService.EXTRA_MAX_RETRY, retry)
                }
                try {
                    context.startForegroundService(intent)
                    Toast.makeText(context, "悬浮窗已启动，可切回B站App", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("启动悬浮窗", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedButton(
            onClick = onNavigateToPermissions,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("权限引导")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(onClick = onNavigateToSettings) {
            Icon(Icons.Default.Settings, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("设置")
        }
    }
}

@Composable
private fun PermissionRow(
    name: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (granted) "已开启" else "未开启",
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            )
        }
        if (!granted) {
            Button(onClick = onGrant) {
                Text("去开启")
            }
        }
    }
}
