package com.yuanbao.mcjlauncher.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yuanbao.mcjlauncher.LauncherApp
import com.yuanbao.mcjlauncher.MainActivity
import com.yuanbao.mcjlauncher.R
import com.yuanbao.mcjlauncher.ui.LauncherTheme
import com.yuanbao.mcjlauncher.ui.OverlayPanel

/**
 * 游戏内悬浮窗：点击展开 / 收起。
 * 日志、统计、运行状态全部订阅 LauncherContainer 的共享 StateFlow（由引擎日志总线统一驱动），
 * 停止 / 强制停止 / stdin 命令都是真实动作。
 */
class FloatingWindowService : Service() {
    private var expanded by mutableStateOf(false)
    private val viewTreeOwner = OverlayViewTreeOwner()
    private var composeView: ComposeView? = null
    private lateinit var app: LauncherApp

    override fun onCreate() {
        super.onCreate()
        app = application as LauncherApp
        expanded = app.container.settings.value.autoExpandOverlay
        viewTreeOwner.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        addOverlay()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> {
                toggle()
            }

            ACTION_STOP -> {
                stopSelf()
            }

            ACTION_FORCE_STOP -> {
                app.container.kill()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        composeView?.let { view ->
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view) }
        }
        composeView = null
        viewTreeOwner.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun toggle() {
        expanded = !expanded
        refreshLayout()
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val view =
            ComposeView(this).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(viewTreeOwner)
                setViewTreeViewModelStoreOwner(viewTreeOwner)
                setViewTreeSavedStateRegistryOwner(viewTreeOwner)
                setContent {
                    LauncherTheme {
                        val logs by app.container.logs.collectAsState()
                        val stats by app.container.stats.collectAsState()
                        val running by app.container.running.collectAsState()
                        OverlayPanel(
                            container = app.container,
                            expanded = expanded,
                            running = running,
                            logs = logs,
                            stats = stats,
                            onToggle = { toggle() },
                            onStop = {
                                app.container.stop()
                                stopSelf()
                            },
                            onForceStop = { app.container.kill() },
                            onOpenLauncher = {
                                startActivity(
                                    Intent(this@FloatingWindowService, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                                expanded = false
                            },
                        )
                    }
                }
            }

        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        val params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    // 横屏下竖向空间紧张，按短边比例定位，避免在矮屏上把面板顶到屏幕外。
                    val dm = resources.displayMetrics
                    x = (12 * dm.density).toInt()
                    y = (dm.heightPixels * 0.08f).toInt()
                }
        view.alpha = app.container.settings.value.overlayAlpha / 100f
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params) }
        composeView = view
    }

    private fun refreshLayout() {
        val view = composeView ?: return
        runCatching {
            (getSystemService(WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(view, view.layoutParams)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "游戏悬浮控制台",
                    NotificationManager.IMPORTANCE_LOW,
                )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification() =
        NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("MC Java 启动器")
            .setContentText("游戏悬浮控制台 · 点击展开")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build()

    /** WindowManager 里的 ComposeView 需要完整的 Lifecycle / ViewModel / SavedState 宿主。 */
    private class OverlayViewTreeOwner :
        LifecycleOwner,
        ViewModelStoreOwner,
        SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val controller = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

        fun onCreate() {
            controller.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    companion object {
        private const val CHANNEL_ID = "mcjl_overlay"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE = "com.yuanbao.mcjlauncher.OVERLAY_TOGGLE"
        const val ACTION_STOP = "com.yuanbao.mcjlauncher.OVERLAY_STOP"
        const val ACTION_FORCE_STOP = "com.yuanbao.mcjlauncher.OVERLAY_FORCE_STOP"

        fun start(context: Context) {
            val intent = Intent(context, FloatingWindowService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun toggle(context: Context) {
            context.startService(
                Intent(context, FloatingWindowService::class.java).setAction(ACTION_TOGGLE),
            )
        }
    }
}
