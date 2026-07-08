package com.example.an_biliticketsbuy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.example.an_biliticketsbuy.R
import com.example.an_biliticketsbuy.comm.ActionBus
import com.example.an_biliticketsbuy.ntp.TimeOffsetHolder
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class FloatingWindowService : Service() {

    companion object {
        private const val TAG = "FloatWindowService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "float_window_channel"

        const val ACTION_START = "com.example.an_biliticketsbuy.START_FLOAT"
        const val ACTION_STOP = "com.example.an_biliticketsbuy.STOP_FLOAT"
        const val EXTRA_TARGET_TIME = "target_time"
        const val EXTRA_CLICK_INTERVAL = "click_interval"
        const val EXTRA_MAX_RETRY = "max_retry"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var tvCountdown: TextView
    private lateinit var tvNtpTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnStart: TextView
    private lateinit var btnStop: TextView

    private var targetTimeMillis: Long = 0
    private var clickInterval: Long = 100
    private var maxRetry: Int = 50

    private var countdownJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("悬浮窗运行中"))

        try {
            createFloatingWindow()
        } catch (e: Exception) {
            Log.e(TAG, "创建悬浮窗失败: ${e.message}", e)
        }

        val filter = IntentFilter().apply {
            addAction(ActionBus.ACTION_START_GRAB)
            addAction(ActionBus.ACTION_STOP_GRAB)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(actionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(actionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START -> {
                    targetTimeMillis = it.getLongExtra(EXTRA_TARGET_TIME, 0)
                    clickInterval = it.getLongExtra(EXTRA_CLICK_INTERVAL, 100)
                    maxRetry = it.getIntExtra(EXTRA_MAX_RETRY, 50)
                    Log.i(TAG, "Received start: target=$targetTimeMillis")
                    showFloatingView()
                }
                ACTION_STOP -> stopSelf()
            }
        }
        return START_STICKY
    }

    // ====== 悬浮窗创建 ======

    private fun createFloatingWindow() {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            // 按屏幕高度 10% 定位，适配不同机型
            y = (resources.displayMetrics.heightPixels * 0.1).toInt()
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_panel, null)

        floatingView?.let { view ->
            tvCountdown = view.findViewById(R.id.tv_countdown)
            tvNtpTime = view.findViewById(R.id.tv_ntp_time)
            tvStatus = view.findViewById(R.id.tv_status)
            btnStart = view.findViewById(R.id.btn_start)
            btnStop = view.findViewById(R.id.btn_stop)

            btnStart.setOnClickListener { startGrabbing() }
            btnStop.setOnClickListener { stopGrabbing() }

            setupDrag(view)
        }
    }

    private fun showFloatingView() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "悬浮窗权限未授予，无法显示悬浮窗")
            stopSelf()
            return
        }
        if (floatingView?.parent == null && layoutParams != null) {
            windowManager.addView(floatingView, layoutParams)
            startCountdown()
        }
    }

    private fun hideFloatingView() {
        floatingView?.let {
            if (it.parent != null) {
                windowManager.removeView(it)
            }
        }
        countdownJob?.cancel()
    }

    // ====== 拖拽实现 ======

    private fun setupDrag(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx > 5 || dy > 5) isDragging = true
                    layoutParams!!.x = initialX + dx.toInt()
                    layoutParams!!.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val screenWidth = resources.displayMetrics.widthPixels
                    layoutParams!!.x = if (layoutParams!!.x < screenWidth / 2) 0
                        else screenWidth - floatingView!!.width
                    windowManager.updateViewLayout(floatingView, layoutParams)
                    !isDragging
                }
                else -> false
            }
        }
    }

    // ====== 倒计时 ======

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)
            val now = System.currentTimeMillis()
            val remaining = targetTimeMillis - now
            Log.i(TAG, "倒计时启动: target=$targetTimeMillis, now=$now, remaining=${remaining}ms" +
                    " (${sdf.format(Date(targetTimeMillis))} vs ${sdf.format(Date(now))})")

            if (remaining <= 0) {
                Log.e(TAG, "目标时间已过期！remaining=${remaining}ms, 请检查设置的时间是否正确")
                tvCountdown.text = "00:00:00.000"
                tvStatus.text = "目标时间已过期"
                return@launch
            }

            // 启动时同步 NTP 时间
            val ntpSync = com.example.an_biliticketsbuy.ntp.NtpTimeSync()
            ntpSync.sync(object : com.example.an_biliticketsbuy.ntp.NtpTimeSync.SyncCallback {
                override fun onSuccess(offsetMillis: Long) {
                    Log.i(TAG, "NTP同步成功, offset=${offsetMillis}ms")
                }
                override fun onFailure(error: String) {
                    Log.w(TAG, "NTP同步失败: $error, 使用本地时间")
                }
            })

            while (isActive) {
                val ntpNow = System.currentTimeMillis() + TimeOffsetHolder.getOffset()
                val remaining = targetTimeMillis - ntpNow

                if (remaining <= 0) {
                    tvCountdown.text = "00:00:00.000"
                    tvStatus.text = "时间到！正在抢票..."
                    Log.i(TAG, "倒计时归零，自动触发抢票")
                    startGrabbing()
                    break
                }

                val hours = remaining / 3_600_000
                val minutes = (remaining % 3_600_000) / 60_000
                val seconds = (remaining % 60_000) / 1_000
                val millis = remaining % 1_000

                tvCountdown.text = String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
                tvNtpTime.text = "NTP: ${sdf.format(Date(ntpNow))}"

                delay(if (remaining > 10_000) 500 else 50)
            }
        }
    }

    // ====== 抢票控制 ======

    private fun startGrabbing() {
        Log.i(TAG, "startGrabbing() 被调用")

        val a11yService = TicketAccessibilityService.getInstance()
        if (a11yService == null) {
            Log.e(TAG, "无障碍服务实例为null！请先在系统设置中开启无障碍服务")
            tvStatus.text = "请先开启无障碍服务"
            return
        }

        // 倒计时已归零，直接传当前时间让 OrderFlowController 立即开始点击
        val nowMillis = System.currentTimeMillis()
        Log.i(TAG, "无障碍服务已连接, 开始抢票: now=$nowMillis, interval=$clickInterval, retry=$maxRetry")
        a11yService.startTicketGrabbing(nowMillis, clickInterval, maxRetry)
        tvStatus.text = "抢票中..."
        btnStart.isEnabled = false
        btnStop.isEnabled = true

        sendBroadcast(Intent(ActionBus.ACTION_GRAB_STARTED))
    }

    private fun stopGrabbing() {
        TicketAccessibilityService.getInstance()?.stopTicketGrabbing()
        tvStatus.text = "已停止"
        btnStart.isEnabled = true
        btnStop.isEnabled = false
        sendBroadcast(Intent(ActionBus.ACTION_GRAB_STOPPED))
    }

    // ====== 广播接收 ======

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ActionBus.ACTION_START_GRAB -> startGrabbing()
                ActionBus.ACTION_STOP_GRAB -> stopGrabbing()
            }
        }
    }

    // ====== 前台通知 ======

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "悬浮窗服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维持悬浮窗运行"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("B站抢票助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_float_panel)
            .setOngoing(true)
            .build()

    // ====== 生命周期 ======

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingView()
        unregisterReceiver(actionReceiver)
        serviceScope.cancel()
        Log.i(TAG, "Floating window service destroyed")
    }
}
