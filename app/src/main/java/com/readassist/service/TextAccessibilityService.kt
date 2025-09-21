package com.readassist.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.app.Notification
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.readassist.utils.DeviceUtils
import com.readassist.utils.PreferenceManager
import com.readassist.service.managers.ScreenshotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import java.io.File
import android.database.Cursor
import android.content.ContentUris

class TextAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "TextAccessibilityService"
        const val ACTION_TEXT_DETECTED = "com.readassist.TEXT_DETECTED"
        const val ACTION_TEXT_SELECTED = "com.readassist.TEXT_SELECTED"
        const val EXTRA_DETECTED_TEXT = "detected_text"
        const val EXTRA_SOURCE_APP = "source_app"
        const val EXTRA_BOOK_NAME = "book_name"
        const val EXTRA_IS_SELECTION = "is_selection"
        const val ACTION_TAKE_SCREENSHOT_VIA_ACCESSIBILITY = "com.readassist.service.TAKE_SCREENSHOT_VIA_ACCESSIBILITY"
        const val ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY = "com.readassist.SCREENSHOT_TAKEN_VIA_ACCESSIBILITY"
        const val EXTRA_SCREENSHOT_URI = "screenshot_uri"
        
        // 支持的应用包名
        private val SUPPORTED_PACKAGES = setOf(
            "com.adobe.reader",
            "com.kingsoft.moffice_eng", 
            "com.supernote.app",
            "com.ratta.supernote.launcher",  // Supernote A5 X2 启动器
            "com.supernote.document",        // Supernote A5 X2 文档阅读器 - 关键包名！
            "com.supernote.reader",          // 可能的其他包名
            "com.ratta.reader",              // 可能的其他包名
            "com.ratta.supernote.reader",    // 可能的其他包名
            "com.ratta.supernote.document",  // 可能的其他包名
            "com.ratta.document",            // 可能的其他包名
            "com.ratta.supernote",           // 可能的其他包名
            "com.readassist",
            "com.readassist.debug"
        )

        // 复制相关关键词（中英混合，尽量覆盖常见文案/Toast/按钮）
        private val COPY_KEYWORDS = listOf(
            "已复制", "复制到剪贴板", "已复制到剪贴板", "复制成功", "复制", "拷贝", "已拷贝",
            "文本已复制", "内容已复制", "已复制文本", "复制完成", "复制完毕", "复制操作完成",
            "copied", "copied to clipboard", "text copied", "copy successful", "copy", "content copied",
            "text copied to clipboard", "copy completed", "copy finished", "copy operation completed"
        )
    }
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var lastClipboardText: String = ""
    private var lastProcessedText: String = ""
    private var currentAppPackage: String = ""
    private var currentBookName: String = ""
    private var context: Context = this
    
    private var screenshotObserver: ContentObserver? = null
    private val deviceType by lazy { DeviceUtils.getDeviceType() }
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // 本地节流：避免短时间内重复触发提示
    private var lastCopySignalTime: Long = 0L
    private var lastCopySignalPackage: String = ""
    
    // 智能复制检测：跟踪用户交互序列
    private var recentHoverEvents = mutableListOf<Long>()
    private var lastWindowContentChangeTime: Long = 0L
    private val MAX_HOVER_EVENTS = 3
    private val HOVER_WINDOW_MS = 5000L // 5秒内的hover事件
    
    // 增强检测：跟踪点击事件和菜单状态
    private var recentClickEvents = mutableListOf<Long>()
    private var lastMenuDetectionTime: Long = 0L
    private var lastDetectedMenuContent = ""
    private val MAX_CLICK_EVENTS = 5
    private val CLICK_WINDOW_MS = 3000L // 3秒内的点击事件
    
    // 剪贴板检查防抖机制 - 智能防抖，以最后一次HOVER_EXIT为准
    private val clipboardHandler = Handler(Looper.getMainLooper())
    private var clipboardCheckRunnable: Runnable? = null
    private var lastHoverExitTime = 0L
    private val HOVER_EXIT_DEBOUNCE_DELAY = 500L // 500ms延迟，以最后一次HOVER_EXIT为准
    private var lastHoverEventType = -1 // 记录最后一个hover事件类型
    
    // 公开原始属性供调试
    val currentAppPackageRaw: String
        get() = currentAppPackage
        
    val currentBookNameRaw: String 
        get() = currentBookName
    
    // 文本请求广播接收器
    private val textRequestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.readassist.REQUEST_SELECTED_TEXT" -> {
                    Log.d(TAG, "📥 收到获取选中文本请求")
                    handleSelectedTextRequest()
                }
                "com.readassist.DEBUG_CAPTURE_UI_TREE" -> {
                    Log.d(TAG, "🐞 收到调试采集UI树请求")
                    captureAndLogUiTreeSnapshot()
                }
            }
        }
    }
    
    private val screenshotActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.e(TAG, "onReceive: 收到广播 ${intent.action}")
            
            when (intent.action) {
                ACTION_TAKE_SCREENSHOT_VIA_ACCESSIBILITY -> {
                    Log.e(TAG, "收到截屏广播，开始处理")
                    if (isServiceConnected) {
                        Log.e(TAG, "服务已连接，开始执行截屏")
                        performScreenshot()
                    } else {
                        Log.e(TAG, "❌ 服务未连接，无法执行截屏")
                    }
                }
                "android.intent.action.SCREENSHOT_TAKEN",
                "com.szzy.ireader.systemui.action.SCREENSHOT_TAKEN",
                "com.zhangyue.iReader.Eink.action.SCREENSHOT_TAKEN" -> {
                    Log.e(TAG, "📸 收到系统截屏完成广播")
                    handleScreenshotTaken(intent)
                }
                "android.intent.action.SCREENSHOT_FAILED",
                "com.szzy.ireader.systemui.action.SCREENSHOT_FAILED",
                "com.zhangyue.iReader.Eink.action.SCREENSHOT_FAILED" -> {
                    Log.e(TAG, "❌ 系统截屏失败")
                    val intent = Intent("com.readassist.service.SCREENSHOT_FAILED")
                    sendBroadcast(intent)
                }
                "android.intent.action.SCREENSHOT_CANCELLED",
                "com.szzy.ireader.systemui.action.SCREENSHOT_CANCELLED",
                "com.zhangyue.iReader.Eink.action.SCREENSHOT_CANCELLED" -> {
                    Log.e(TAG, "❌ 系统截屏被取消")
                    val intent = Intent("com.readassist.service.SCREENSHOT_CANCELLED")
                    sendBroadcast(intent)
                }
                "com.zhangyue.iReader.Eink.MediaKeyCode" -> {
                    Log.e(TAG, "📸 收到掌阅设备媒体按键广播")
                    val keyCode = intent.getIntExtra("keyCode", -1)
                    if (keyCode == 120) { // 假设120是截屏按键
                        Log.e(TAG, "📸 检测到截屏按键")
                        // 等待一小段时间，让系统完成截屏
                        Handler(Looper.getMainLooper()).postDelayed({
                            checkIReaderScreenshot()
                        }, 500) // 等待0.5秒
                    }
                }
                "com.szzy.ireader.systemui.statusbar.BROADCAST_DISPLAYED_RING_MENU_WINDOW" -> {
                    Log.e(TAG, "📸 掌阅设备：显示环形菜单")
                    // 记录环形菜单窗口信息
                    val windowInfo = intent.getStringExtra("window_info")
                    Log.e(TAG, "环形菜单窗口信息: $windowInfo")
                }
                "com.szzy.ireader.systemui.statusbar.BROADCAST_DISAPPEARED_RING_MENU_WINDOW",
                "szzy.ireader.systemui.action.HIDE_PANEL_WINDOW" -> {
                    Log.e(TAG, "📸 掌阅设备：环形菜单消失")
                    // 记录环形菜单消失原因
                    val reason = intent.getStringExtra("reason")
                    Log.e(TAG, "环形菜单消失原因: $reason")
                    // 检查掌阅设备特有的截屏目录
                    checkIReaderScreenshot()
                }
                "com.szzy.ireader.systemui.ACTION_RESUME_AUTO_HIDE_STATUS_BAR" -> {
                    Log.e(TAG, "📸 收到系统UI状态栏广播")
                    // 检查掌阅设备特有的截屏目录
                    checkIReaderScreenshot()
                }
                "com.zhangyue.iReader.screenoff" -> {
                    Log.e(TAG, "📸 收到掌阅设备屏幕关闭广播")
                }
                "com.zhangyue.iReader.screenlogo.show" -> {
                    Log.e(TAG, "📸 收到掌阅设备屏幕Logo显示广播")
                }
                "android.intent.action.DREAMING_STARTED" -> {
                    Log.e(TAG, "📸 收到系统休眠开始广播")
                }
                "android.intent.action.DREAMING_STOPPED" -> {
                    Log.e(TAG, "📸 收到系统休眠结束广播")
                }
                "android.intent.action.CLOSE_SYSTEM_DIALOGS" -> {
                    Log.e(TAG, "📸 收到系统对话框关闭广播")
                    val reason = intent.getStringExtra("reason")
                    Log.e(TAG, "关闭原因: $reason")
                }
                "com.szzy.ireader.systemui.action.RING_MENU_ITEM_CLICKED" -> {
                    Log.e(TAG, "📋 环形菜单按钮被点击")
                    // 打印所有extra内容
                    intent.extras?.keySet()?.forEach { key ->
                        Log.e(TAG, "[RING_MENU_ITEM_CLICKED] Intent extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                "com.szzy.ireader.systemui.action.RING_MENU_SCREENSHOT" -> {
                    Log.e(TAG, "📸 环形菜单触发了截屏操作")
                    intent.extras?.keySet()?.forEach { key ->
                        Log.e(TAG, "[RING_MENU_SCREENSHOT] Intent extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                "com.android.systemui.action.SCREENSHOT" -> {
                    Log.e(TAG, "📸 系统UI收到截屏命令")
                    intent.extras?.keySet()?.forEach { key ->
                        Log.e(TAG, "[SYSTEMUI_SCREENSHOT] Intent extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                "com.android.systemui.action.SCREENSHOT_TAKEN" -> {
                    Log.e(TAG, "📸 系统UI截屏完成")
                    intent.extras?.keySet()?.forEach { key ->
                        Log.e(TAG, "[SCREENSHOT_TAKEN] Intent extra: $key = ${intent.extras?.get(key)}")
                    }
                }
                else -> {
                    Log.e(TAG, "未知广播类型: ${intent.action}")
                }
            }
        }
    }
    
    private var isServiceConnected = false
    private var isWaitingForScreenshot = false
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 TextAccessibilityService onCreate() 开始")
        
        preferenceManager = PreferenceManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        

        // 注册截屏动作广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_TAKE_SCREENSHOT_VIA_ACCESSIBILITY)
            addAction("android.intent.action.SCREENSHOT_TAKEN")
            addAction("android.intent.action.SCREENSHOT_FAILED")
            addAction("android.intent.action.SCREENSHOT_CANCELLED")
            // 掌阅设备特有的广播
            addAction("com.szzy.ireader.systemui.action.SCREENSHOT_TAKEN")
            addAction("com.szzy.ireader.systemui.action.SCREENSHOT_FAILED")
            addAction("com.szzy.ireader.systemui.action.SCREENSHOT_CANCELLED")
            addAction("com.zhangyue.iReader.Eink.action.SCREENSHOT_TAKEN")
            addAction("com.zhangyue.iReader.Eink.action.SCREENSHOT_FAILED")
            addAction("com.zhangyue.iReader.Eink.action.SCREENSHOT_CANCELLED")
            addAction("com.zhangyue.iReader.Eink.MediaKeyCode")
            // 环形菜单相关广播
            addAction("com.szzy.ireader.systemui.statusbar.BROADCAST_DISPLAYED_RING_MENU_WINDOW")
            addAction("com.szzy.ireader.systemui.statusbar.BROADCAST_DISAPPEARED_RING_MENU_WINDOW")
            addAction("szzy.ireader.systemui.action.HIDE_PANEL_WINDOW")
            // 系统UI相关广播
            addAction("com.szzy.ireader.systemui.ACTION_RESUME_AUTO_HIDE_STATUS_BAR")
            addAction("com.zhangyue.iReader.screenoff")
            addAction("com.zhangyue.iReader.screenlogo.show")
            addAction("android.intent.action.DREAMING_STARTED")
            addAction("android.intent.action.DREAMING_STOPPED")
            addAction("android.intent.action.CLOSE_SYSTEM_DIALOGS")
            addAction("com.szzy.ireader.systemui.action.RING_MENU_ITEM_CLICKED")
            addAction("com.szzy.ireader.systemui.action.RING_MENU_SCREENSHOT")
            addAction("com.android.systemui.action.SCREENSHOT")
            addAction("com.android.systemui.action.SCREENSHOT_TAKEN")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenshotActionReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenshotActionReceiver, filter)
        }
        
        // 注册文本请求广播接收器
        val requestFilter = IntentFilter("com.readassist.REQUEST_SELECTED_TEXT")
        LocalBroadcastManager.getInstance(this).registerReceiver(textRequestReceiver, requestFilter)
        // 额外注册调试广播（使用普通广播以便service能收到）
        registerReceiver(textRequestReceiver, IntentFilter("com.readassist.DEBUG_CAPTURE_UI_TREE"))
        
        Log.i(TAG, "✅ TextAccessibilityService onCreate() 完成")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "🔗 Accessibility service connected - 服务已连接")
        
        // 更新偏好设置中的辅助功能状态
        preferenceManager.setAccessibilityEnabled(true)
        
        // 启动悬浮窗服务
        startFloatingWindowService()
        
        // 注册MediaStore截图监听器（无论设备类型都注册）
        initScreenshotObserver()
        
        // 为iReader设备注册截图观察者（如有特殊逻辑可保留）
        if (DeviceUtils.isIReaderDevice()) {
            registerScreenshotObserver()
        }
        
        Log.i(TAG, "✅ onServiceConnected() 完成，开始监听事件")
        isServiceConnected = true
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TextAccessibilityService destroyed")
        
        // 更新偏好设置
        preferenceManager.setAccessibilityEnabled(false)
        
        // 注销截屏动作广播接收器
        unregisterReceiver(screenshotActionReceiver)
        
        // 注销广播接收器
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(textRequestReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering text request receiver", e)
        }
        try {
            unregisterReceiver(textRequestReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering debug receiver", e)
        }
        
        // 注销截图观察者
        unregisterScreenshotObserver()
        
        // 停止悬浮窗服务
        stopFloatingWindowService()
        isServiceConnected = false
        
        serviceScope.cancel()
    }

    /**
     * 采集当前窗口节点树快照（调试用），打印包含的文本/描述/id，便于针对特定App（如Supernote）做复制按钮特征匹配
     */
    private fun captureAndLogUiTreeSnapshot() {
        val sb = StringBuilder()
        sb.append("🐞 UI树采集开始\n")
        
        // 采集主活动窗口
        val root = rootInActiveWindow
        if (root != null) {
            try {
                sb.append("📱 主活动窗口:\n")
                dumpNode(root, sb, 0, maxDepth = 6, maxNodes = 300)
            } catch (e: Exception) {
                Log.e(TAG, "采集主活动窗口UI树异常: ${e.message}", e)
            } finally {
                root.recycle()
            }
        } else {
            sb.append("❌ 无主活动窗口\n")
        }
        
        // 采集所有窗口层（包括弹窗、悬浮窗等）
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = windows
                if (windows != null && windows.isNotEmpty()) {
                    sb.append("\n🔍 所有窗口层 (共${windows.size}个):\n")
                    for (i in windows.indices) {
                        val window = windows[i]
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            try {
                                val windowType = when (window.type) {
                                    AccessibilityWindowInfo.TYPE_APPLICATION -> "应用窗口"
                                    AccessibilityWindowInfo.TYPE_SYSTEM -> "系统窗口"
                                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法窗口"
                                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍覆盖层"
                                    else -> "其他窗口(${window.type})"
                                }
                                sb.append("\n📱 窗口${i+1} ($windowType):\n")
                                dumpNode(windowRoot, sb, 0, maxDepth = 6, maxNodes = 200)
                            } catch (e: Exception) {
                                Log.e(TAG, "采集窗口${i+1} UI树异常: ${e.message}", e)
                                sb.append("❌ 窗口${i+1}采集异常: ${e.message}\n")
                            } finally {
                                windowRoot.recycle()
                            }
                        } else {
                            sb.append("❌ 窗口${i+1}无根节点\n")
                        }
                    }
                } else {
                    sb.append("❌ 无其他窗口层\n")
                }
            } else {
                sb.append("❌ Android版本过低，无法获取所有窗口层\n")
            }
        } catch (e: Exception) {
            Log.e(TAG, "采集所有窗口层异常: ${e.message}", e)
            sb.append("❌ 采集所有窗口层异常: ${e.message}\n")
        }
        
        sb.append("\n🐞 UI树采集结束")
        Log.d(TAG, sb.toString())
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int, maxDepth: Int, maxNodes: Int, counter: IntArray = intArrayOf(0)) {
        if (depth > maxDepth) return
        if (counter[0]++ > maxNodes) return
        val indent = "  ".repeat(depth)
        val cls = node.className?.toString() ?: ""
        val txt = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        sb.append("$indent- cls=$cls id=$id text=${txt.take(40)} desc=${desc.take(40)}\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                dumpNode(child, sb, depth + 1, maxDepth, maxNodes, counter)
            } finally {
                child.recycle()
            }
        }
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        
        val eventPackageName = event.packageName?.toString() ?: return
        
        // 记录所有事件类型，帮助调试
        Log.e(TAG, "📱 收到事件: type=${event.eventType}, package=$eventPackageName")
        
        // 监听窗口变化事件，可能包含截屏完成通知
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.e(TAG, "检测到窗口变化事件: ${event.eventType}")
            
            // 窗口变化可能是截屏完成后的通知
            // 延迟一小段时间后检查是否有新截图
            Handler(Looper.getMainLooper()).postDelayed({
                checkIReaderScreenshot()
            }, 500)
        }
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                Log.e(TAG, "📱 收到通知状态变化事件")
                val notification = event.parcelableData as? Notification
                if (notification != null) {
                    val extras = notification.extras
                    val title = extras?.getString(Notification.EXTRA_TITLE)
                    val text = extras?.getString(Notification.EXTRA_TEXT)
                    Log.e(TAG, "📱 通知详情: title=$title, text=$text")
                    
                    // 检查是否是截屏相关的通知
                    if (title?.contains("Screenshot", ignoreCase = true) == true ||
                        text?.contains("Screenshot", ignoreCase = true) == true) {
                        Log.e(TAG, "📸 检测到截屏相关通知")
                        if (text?.contains("saved", ignoreCase = true) == true) {
                            Log.e(TAG, "📸 截屏已保存")
                            // 获取截屏目录
                            val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                            val screenshotsDir = File(screenshotDir, "Screenshots")
                            if (screenshotsDir.exists()) {
                                Log.e(TAG, "📸 截屏目录存在: ${screenshotsDir.absolutePath}")
                                // 获取最新的截屏文件
                                val latestFile = screenshotsDir.listFiles()
                                    ?.filter { it.name.endsWith(".png") }
                                    ?.maxByOrNull { it.lastModified() }
                                
                                if (latestFile != null) {
                                    val uri = Uri.fromFile(latestFile)
                                    Log.e(TAG, "📸 找到截屏文件: $uri")
                                    // 通知截屏完成
                                    val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
                                    intent.putExtra("screenshot_uri", uri)
                                    sendBroadcast(intent)
                                    Log.e(TAG, "📸 已发送截屏完成广播")
                                } else {
                                    Log.e(TAG, "❌ 未找到截屏文件")
                                }
                            } else {
                                Log.e(TAG, "❌ 截屏目录不存在: ${screenshotsDir.absolutePath}")
                            }
                        } else if (text?.contains("failed", ignoreCase = true) == true) {
                            Log.e(TAG, "❌ 截屏失败")
                        }
                    }

                    // 复制信号检测（通知/Toast 文案）
                    val eventTextCombined = (event.text?.joinToString(" ") { it.toString() } ?: "")
                    val candidate = listOfNotNull(title, text, eventTextCombined)
                        .joinToString(" ") { it }
                    val srcPkg = event.packageName?.toString() ?: ""
                    if (containsCopyKeyword(candidate)) {
                        Log.d(TAG, "📋 检测到复制相关通知/Toast，来源: $srcPkg，文案: ${candidate.take(80)}")
                        maybeTriggerClipboardPrompt("notification_toast", srcPkg)
                    }
                } else {
                    Log.e(TAG, "❌ 通知对象为空")
                    // 某些Toast不会附带Notification，仅在event.text中
                    val toastText = event.text?.joinToString(" ") { it.toString() } ?: ""
                    val srcPkg = event.packageName?.toString() ?: ""
                    if (toastText.isNotBlank() && containsCopyKeyword(toastText)) {
                        Log.d(TAG, "📋 从Toast文本检测到复制，来源: $srcPkg，文案: ${toastText.take(80)}")
                        maybeTriggerClipboardPrompt("toast_text", srcPkg)
                    }
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (event.className?.contains("supernote") == true || event.className?.contains("ratta") == true) {
                    Log.d(TAG, "🔴 Supernote窗口状态变更: $eventPackageName")
                    Log.d(TAG, "🔴 窗口标题: ${event.className}")
                    Log.d(TAG, "🔴 窗口文本: ${event.text}")
                    
                    // 强制更新应用和书籍名称
                    currentAppPackage = eventPackageName
                    
                    // 尝试提取书籍名称
                    val potentialBookName = extractBookNameFromTitle(event.className?.toString() ?: "")
                    if (potentialBookName.isNotEmpty()) {
                        currentBookName = potentialBookName
                        Log.d(TAG, "🔴 从窗口标题提取书籍名称成功: $currentBookName")
                    } else {
                        Log.d(TAG, "🔴 无法从窗口标题提取书籍名称")
                    }
                } else {
                    Log.d(TAG, "Window state changed: $eventPackageName")
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 增强窗口内容变化检测：遍历所有窗口层
                val srcPkg = event.packageName?.toString() ?: ""
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta") || SUPPORTED_PACKAGES.contains(srcPkg)) {
                    Log.d(TAG, "📱 收到窗口内容变化事件，来源: $srcPkg")
                    
                    // 记录窗口内容变化时间，用于智能复制检测
                    lastWindowContentChangeTime = System.currentTimeMillis()
                    
                    // 方法1：检查主活动窗口
                    val root = rootInActiveWindow
                    if (root != null) {
                        try {
                            if (treeContainsCopyKeyword(root)) {
                                Log.d(TAG, "📋 在主活动窗口检测到复制相关菜单，来源: $srcPkg")
                                maybeTriggerClipboardPrompt("window_menu_main", srcPkg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "扫描主活动窗口节点时发生错误: ${e.message}", e)
                        } finally {
                            root.recycle()
                        }
                    }
                    
                    // 方法2：检查所有窗口层（包括弹窗、悬浮窗等）
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            val windows = windows
                            if (windows != null) {
                                Log.d(TAG, "🔍 检查所有窗口层，共 ${windows.size} 个窗口")
                                for (i in windows.indices) {
                                    val window = windows[i]
                                    val windowRoot = window.root
                                    if (windowRoot != null) {
                                        try {
                                            val windowType = when (window.type) {
                                                AccessibilityWindowInfo.TYPE_APPLICATION -> "应用窗口"
                                                AccessibilityWindowInfo.TYPE_SYSTEM -> "系统窗口"
                                                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法窗口"
                                                AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍覆盖层"
                                                else -> "其他窗口(${window.type})"
                                            }
                                            Log.d(TAG, "🔍 检查窗口类型: $windowType")
                                            
                                            if (treeContainsCopyKeyword(windowRoot)) {
                                                Log.d(TAG, "📋 在$windowType 检测到复制相关菜单，来源: $srcPkg")
                                                maybeTriggerClipboardPrompt("window_menu_$windowType", srcPkg)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "扫描窗口层 $i 时发生错误: ${e.message}", e)
                                        } finally {
                                            windowRoot.recycle()
                                        }
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "遍历所有窗口时发生错误: ${e.message}", e)
                    }
                }
            }
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> {
                // 监听Toast/Announcement事件中的"已复制/Copied"文案
                val srcPkg = event.packageName?.toString() ?: ""
                val announcementText = event.text?.joinToString(" ") { it.toString() } ?: ""
                
                Log.d(TAG, "📢 收到公告事件，来源: $srcPkg，内容: ${announcementText.take(100)}")
                
                if (announcementText.isNotBlank() && containsCopyKeyword(announcementText)) {
                    Log.d(TAG, "📋 从公告事件检测到复制相关文案，来源: $srcPkg，内容: ${announcementText.take(80)}")
                    maybeTriggerClipboardPrompt("announcement_toast", srcPkg)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 记录点击事件，用于增强复制检测
                val currentTime = System.currentTimeMillis()
                recentClickEvents.add(currentTime)
                
                // 清理过期的点击事件记录
                recentClickEvents.removeAll { currentTime - it > CLICK_WINDOW_MS }
                
                // 保持最多MAX_CLICK_EVENTS个记录
                if (recentClickEvents.size > MAX_CLICK_EVENTS) {
                    recentClickEvents = recentClickEvents.takeLast(MAX_CLICK_EVENTS).toMutableList()
                }
                
                // 详细记录点击事件信息
                val srcPkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                val contentDescription = event.contentDescription?.toString() ?: ""
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                val action = event.action
                val eventTime = event.eventTime
                
                Log.d(TAG, "🖱️ 点击事件详情: 来源=$srcPkg, 类=$className, ID=$viewId, 描述=$contentDescription, 文本=$text, action=$action, eventTime=$eventTime")
                
                // 如果是Supernote应用，特别记录
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote点击事件: VIEW_CLICKED (记录用于智能检测)")
                    Log.d(TAG, "🔍 点击事件完整信息: ${event.toString()}")
                } else {
                    Log.d(TAG, "🖱️ 其他应用点击事件: $srcPkg")
                }
            }
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                // 记录长按点击事件
                val srcPkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                val contentDescription = event.contentDescription?.toString() ?: ""
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                
                Log.d(TAG, "🖱️ 长按点击事件: 来源=$srcPkg, 类=$className, ID=$viewId, 描述=$contentDescription, 文本=$text")
                
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote长按事件: VIEW_LONG_CLICKED")
                }
            }
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // 处理其他事件类型
            }
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> {
                // 记录hover事件，用于智能复制检测
                val currentTime = System.currentTimeMillis()
                recentHoverEvents.add(currentTime)
                lastHoverEventType = AccessibilityEvent.TYPE_VIEW_HOVER_ENTER
                
                // 清理过期的hover事件记录
                recentHoverEvents.removeAll { currentTime - it > HOVER_WINDOW_MS }
                
                // 保持最多MAX_HOVER_EVENTS个记录
                if (recentHoverEvents.size > MAX_HOVER_EVENTS) {
                    recentHoverEvents = recentHoverEvents.takeLast(MAX_HOVER_EVENTS).toMutableList()
                }
                
                // 详细记录hover事件信息
                val srcPkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                val contentDescription = event.contentDescription?.toString() ?: ""
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                
                Log.d(TAG, "🔴 HOVER_ENTER详情: 来源=$srcPkg, 类=$className, ID=$viewId, 描述=$contentDescription, 文本=$text")
                
                // 如果是Supernote应用，记录hover前的完整状态
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote HOVER_ENTER - 记录hover前状态")
                    captureHoverContext("HOVER_ENTER")
                }
                
                Log.d(TAG, "🔴 Supernote其他事件: VIEW_HOVER_ENTER (仅记录，不触发检测)")
            }
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> {
                // 记录hover事件
                val currentTime = System.currentTimeMillis()
                recentHoverEvents.add(currentTime)
                lastHoverEventType = AccessibilityEvent.TYPE_VIEW_HOVER_EXIT
                
                // 清理过期的hover事件记录
                recentHoverEvents.removeAll { currentTime - it > HOVER_WINDOW_MS }
                
                // 保持最多MAX_HOVER_EVENTS个记录
                if (recentHoverEvents.size > MAX_HOVER_EVENTS) {
                    recentHoverEvents = recentHoverEvents.takeLast(MAX_HOVER_EVENTS).toMutableList()
                }
                
                // 详细记录hover exit事件信息
                val srcPkg = event.packageName?.toString() ?: ""
                val className = event.className?.toString() ?: ""
                val contentDescription = event.contentDescription?.toString() ?: ""
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                
                Log.d(TAG, "🔴 HOVER_EXIT详情: 来源=$srcPkg, 类=$className, ID=$viewId, 描述=$contentDescription, 文本=$text")
                
                // 如果是Supernote应用，使用智能防抖机制处理剪贴板检查
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote HOVER_EXIT - 智能防抖处理剪贴板检查")
                    
                    // 记录最新的HOVER_EXIT时间
                    lastHoverExitTime = currentTime
                    
                    // 取消之前的延迟任务（如果存在）
                    clipboardCheckRunnable?.let { runnable ->
                        clipboardHandler.removeCallbacks(runnable)
                        Log.d(TAG, "🔄 取消之前的剪贴板检查任务")
                    }
                    
                    // 创建新的延迟任务
                    clipboardCheckRunnable = Runnable {
                        // 再次检查时间，确保这是最后一次HOVER_EXIT事件
                        if (System.currentTimeMillis() - lastHoverExitTime >= HOVER_EXIT_DEBOUNCE_DELAY - 50) {
                            Log.d(TAG, "🔴 执行延迟后的剪贴板检查 (最后一次HOVER_EXIT)")
                            
                            try {
                                val intent = Intent("com.readassist.CHECK_CLIPBOARD_FROM_HOVER").apply {
                                    putExtra("source", "hover_exit_debounced")
                                    putExtra("package", srcPkg)
                                    putExtra("timestamp", lastHoverExitTime)
                                    putExtra("delay_ms", HOVER_EXIT_DEBOUNCE_DELAY)
                                }
                                sendBroadcast(intent)
                                Log.d(TAG, "🔴 延迟后的剪贴板检查广播已发送")
                                
                            } catch (e: Exception) {
                                Log.e(TAG, "发送延迟剪贴板检查广播失败: ${e.message}")
                            }
                        } else {
                            Log.d(TAG, "🔄 检测到更新的HOVER_EXIT事件，跳过此次检查")
                        }
                    }
                    
                    // 延迟执行剪贴板检查
                    clipboardHandler.postDelayed(clipboardCheckRunnable!!, HOVER_EXIT_DEBOUNCE_DELAY)
                    Log.d(TAG, "🕐 已安排${HOVER_EXIT_DEBOUNCE_DELAY}ms后执行剪贴板检查")
                }
            }
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                // 记录触摸交互开始事件
                val srcPkg = event.packageName?.toString() ?: ""
                Log.d(TAG, "🔴 触摸交互开始: $srcPkg")
                
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote触摸交互开始: TOUCH_INTERACTION_START")
                }
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                // 记录触摸交互结束事件
                val srcPkg = event.packageName?.toString() ?: ""
                Log.d(TAG, "🔴 触摸交互结束: $srcPkg")
                
                if (srcPkg.contains("supernote") || srcPkg.contains("ratta")) {
                    Log.d(TAG, "🔴 Supernote触摸交互结束: TOUCH_INTERACTION_END")
                }
            }
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED,
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT,
            0x00000080,
            0x00000100,
            0x00000200,
            0x00000400,
            0x00000800,
            0x00001000,
            0x00002000,
            0x00004000,
            0x00008000,
            128,
            256,
            512,
            1024,
            2048,
            4096,
            8192,
            16384,
            32768 -> {
                if (event.packageName?.contains("supernote") == true || event.packageName?.contains("ratta") == true) {
                    Log.d(TAG, "🔴 Supernote其他事件: ${getEventTypeName(event.eventType)}")
                } else {
                    Log.d(TAG, "🔍 其他事件 (仅记录，不主动提取文本): ${getEventTypeName(event.eventType)} from $eventPackageName")
                }

                // 针对点击/长按/上下文点击等事件，尝试识别“复制”按钮/菜单
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED) {

                    val srcPkg = event.packageName?.toString() ?: ""
                    var combined = event.text?.joinToString(" ") { it.toString() } ?: ""

                    // 尝试从源节点提取更多可识别信息
                    val node = event.source
                    if (node != null) {
                        try {
                            val nodeText = node.text?.toString() ?: ""
                            val nodeDesc = node.contentDescription?.toString() ?: ""
                            val nodeId = node.viewIdResourceName ?: ""
                            combined = listOf(combined, nodeText, nodeDesc, nodeId)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                        } finally {
                            node.recycle()
                        }
                    }

                    if (combined.isNotBlank() && containsCopyKeyword(combined)) {
                        Log.d(TAG, "📋 点击事件命中复制关键词，来源: $srcPkg，信息: ${combined.take(80)}")
                        maybeTriggerClipboardPrompt("view_clicked", srcPkg)
                    }
                }
            }
            else -> {
                if (event.packageName?.contains("supernote") == true || event.packageName?.contains("ratta") == true) {
                    Log.d(TAG, "🔴 Supernote其他事件: ${getEventTypeName(event.eventType)}")
                } else {
                    Log.d(TAG, "🔍 其他事件 (仅记录，不主动提取文本): ${getEventTypeName(event.eventType)} from $eventPackageName")
                }
            }
        }
    }

    /**
     * 字符串是否包含复制关键词
     */
    private fun containsCopyKeyword(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val lower = text.lowercase()
        return COPY_KEYWORDS.any { kw ->
            // 中文不区分大小写，英文已lower
            lower.contains(kw.lowercase())
        }
    }

    /**
     * 遍历节点树，检查是否包含复制相关关键词
     */
    private fun treeContainsCopyKeyword(node: AccessibilityNodeInfo): Boolean {
        try {
            val texts = mutableListOf<String>()
            collectNodeTexts(node, texts)
            if (texts.isNotEmpty()) {
                val joined = texts.joinToString(" ")
                
                // 通用复制关键词检测
                if (containsCopyKeyword(joined)) return true
                
                // Supernote专用检测规则
                if (containsSupernoteCopySignal(joined, texts)) return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "遍历节点树异常: ${e.message}")
        }
        return false
    }
    
    /**
     * Supernote专用复制信号检测 - 简化版本
     */
    private fun containsSupernoteCopySignal(joined: String, texts: List<String>): Boolean {
        // 检测明确的复制相关文案
        val supernoteCopySignals = listOf(
            "复制", "拷贝", "copy", "copied",
            "已复制", "复制成功", "复制完成",
            "文本已复制", "内容已复制", "复制到剪贴板"
        )
        
        // 检查是否有明确的复制信号
        for (signal in supernoteCopySignals) {
            if (joined.contains(signal, ignoreCase = true)) {
                Log.d(TAG, "🔴 检测到Supernote复制信号: $signal")
                return true
            }
        }
        
        // 注意：selectText检测已移除，因为UI关闭工具栏时selectText会消失
        // 注意：窗口变化检测已移除，不再需要作为触发条件
        // 现在只依赖HOVER_EXIT事件直接触发剪贴板检查
        
        return false
    }
    
    /**
     * 捕获hover上下文信息
     */
    private fun captureHoverContext(context: String) {
        try {
            Log.d(TAG, "🔍 开始捕获hover上下文: $context")
            
            // 记录当前时间戳
            val timestamp = System.currentTimeMillis()
            Log.d(TAG, "🔍 时间戳: $timestamp")
            
            // 记录最近的点击事件
            Log.d(TAG, "🔍 最近点击事件数量: ${recentClickEvents.size}")
            recentClickEvents.forEachIndexed { index, time ->
                val timeDiff = timestamp - time
                Log.d(TAG, "🔍 点击事件[$index]: ${time}, 时间差: ${timeDiff}ms")
            }
            
            // 记录最近的hover事件
            Log.d(TAG, "🔍 最近hover事件数量: ${recentHoverEvents.size}")
            recentHoverEvents.forEachIndexed { index, time ->
                val timeDiff = timestamp - time
                Log.d(TAG, "🔍 Hover事件[$index]: ${time}, 时间差: ${timeDiff}ms")
            }
            
            // 记录窗口内容变化时间
            val windowTimeDiff = timestamp - lastWindowContentChangeTime
            Log.d(TAG, "🔍 最近窗口变化时间: ${lastWindowContentChangeTime}, 时间差: ${windowTimeDiff}ms")
            
            // 记录菜单检测时间
            val menuTimeDiff = timestamp - lastMenuDetectionTime
            Log.d(TAG, "🔍 最近菜单检测时间: ${lastMenuDetectionTime}, 时间差: ${menuTimeDiff}ms")
            
            // 捕获当前窗口状态
            val root = rootInActiveWindow
            if (root != null) {
                try {
                    Log.d(TAG, "🔍 当前主窗口状态:")
                    Log.d(TAG, "🔍 窗口包名: ${root.packageName}")
                    Log.d(TAG, "🔍 窗口类名: ${root.className}")
                    Log.d(TAG, "🔍 窗口文本: ${root.text}")
                    Log.d(TAG, "🔍 窗口描述: ${root.contentDescription}")
                    Log.d(TAG, "🔍 窗口ID: ${root.viewIdResourceName}")
                    Log.d(TAG, "🔍 子节点数量: ${root.childCount}")
                    
                    // 记录前几个子节点的信息
                    for (i in 0 until minOf(5, root.childCount)) {
                        val child = root.getChild(i)
                        if (child != null) {
                            try {
                                Log.d(TAG, "🔍 子节点[$i]: 类=${child.className}, 文本=${child.text}, 描述=${child.contentDescription}, ID=${child.viewIdResourceName}")
                            } finally {
                                child.recycle()
                            }
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            
            // 捕获所有窗口层状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = windows
                if (windows != null) {
                    Log.d(TAG, "🔍 所有窗口层状态 (共${windows.size}个):")
                    for (i in windows.indices) {
                        val window = windows[i]
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            try {
                                val windowType = when (window.type) {
                                    AccessibilityWindowInfo.TYPE_APPLICATION -> "应用窗口"
                                    AccessibilityWindowInfo.TYPE_SYSTEM -> "系统窗口"
                                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法窗口"
                                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍覆盖层"
                                    else -> "其他窗口(${window.type})"
                                }
                                Log.d(TAG, "🔍 窗口层[$i] ($windowType): 包名=${windowRoot.packageName}, 类名=${windowRoot.className}")
                                Log.d(TAG, "🔍 窗口层[$i] 子节点数量: ${windowRoot.childCount}")
                            } finally {
                                windowRoot.recycle()
                            }
                        }
                    }
                }
            }
            
            Log.d(TAG, "🔍 hover上下文捕获完成: $context")
        } catch (e: Exception) {
            Log.e(TAG, "捕获hover上下文时发生错误: ${e.message}", e)
        }
    }
    
    /**
     * 分析当前菜单内容，查找复制相关信号
     */
    private fun analyzeCurrentMenuContent() {
        try {
            Log.d(TAG, "🔍 开始分析当前菜单内容...")
            
            // 检查主活动窗口
            val root = rootInActiveWindow
            if (root != null) {
                val menuTexts = mutableListOf<String>()
                collectMenuTexts(root, menuTexts)
                
                if (menuTexts.isNotEmpty()) {
                    Log.d(TAG, "🔍 主窗口菜单内容: ${menuTexts.joinToString(", ")}")
                } else {
                    Log.d(TAG, "🔍 主窗口无菜单内容")
                }
                root.recycle()
            }
            
            // 检查所有窗口层
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val windows = windows
                if (windows != null) {
                    Log.d(TAG, "🔍 分析所有窗口层的菜单内容，共 ${windows.size} 个窗口")
                    for (i in windows.indices) {
                        val window = windows[i]
                        val windowRoot = window.root
                        if (windowRoot != null) {
                            try {
                                val windowType = when (window.type) {
                                    AccessibilityWindowInfo.TYPE_APPLICATION -> "应用窗口"
                                    AccessibilityWindowInfo.TYPE_SYSTEM -> "系统窗口"
                                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "输入法窗口"
                                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "无障碍覆盖层"
                                    else -> "其他窗口(${window.type})"
                                }
                                
                                val menuTexts = mutableListOf<String>()
                                collectMenuTexts(windowRoot, menuTexts)
                                
                                if (menuTexts.isNotEmpty()) {
                                    Log.d(TAG, "🔍 $windowType 菜单内容: ${menuTexts.joinToString(", ")}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "分析窗口层 $i 菜单时发生错误: ${e.message}", e)
                            } finally {
                                windowRoot.recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "分析菜单内容时发生错误: ${e.message}", e)
        }
    }
    
    /**
     * 收集菜单相关的文本内容
     */
    private fun collectMenuTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        try {
            // 收集节点文本
            node.text?.toString()?.let { text ->
                if (text.isNotBlank()) {
                    out.add(text)
                }
            }
            
            // 收集内容描述
            node.contentDescription?.toString()?.let { desc ->
                if (desc.isNotBlank()) {
                    out.add(desc)
                }
            }
            
            // 收集资源ID（可能包含menu、button等关键词）
            node.viewIdResourceName?.let { id ->
                if (id.contains("menu", ignoreCase = true) || 
                    id.contains("button", ignoreCase = true) ||
                    id.contains("action", ignoreCase = true)) {
                    out.add("ID:$id")
                }
            }
            
            // 递归收集子节点
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    try {
                        collectMenuTexts(child, out)
                    } finally {
                        child.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "收集菜单文本时发生错误: ${e.message}", e)
        }
    }
    
    /**
     * 分析菜单内容，查找复制相关信号
     */
    private fun analyzeMenuContentForCopySignals(joined: String, texts: List<String>): Boolean {
        // 记录菜单检测时间
        lastMenuDetectionTime = System.currentTimeMillis()
        lastDetectedMenuContent = joined
        
        // 查找可能的复制菜单项
        val copyMenuSignals = listOf(
            "复制", "拷贝", "copy", "copied",
            "复制到剪贴板", "复制文本", "复制内容",
            "menu", "menuItem", "action", "button"
        )
        
        // 检查是否有复制相关的菜单项
        for (signal in copyMenuSignals) {
            if (joined.contains(signal, ignoreCase = true)) {
                Log.d(TAG, "🔴 在菜单内容中检测到复制信号: $signal")
                Log.d(TAG, "🔍 菜单内容摘要: ${joined.take(200)}")
                return true
            }
        }
        
        // 如果没有明确的复制信号，记录菜单内容供调试
        Log.d(TAG, "🔍 菜单内容未发现复制信号，内容: ${joined.take(100)}")
        Log.d(TAG, "🔍 菜单文本列表: ${texts.take(10)}")
        
        return false
    }

    private fun collectNodeTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.let { out.add(it) }
        node.contentDescription?.toString()?.let { out.add(it) }
        node.viewIdResourceName?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodeTexts(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    /**
     * 触发剪贴板提示（不读取剪贴板，仅广播提示由前台桥接读取）
     */
    private fun maybeTriggerClipboardPrompt(reason: String, sourcePackage: String) {
        // 源包名校验
        val src = if (sourcePackage.isNotBlank()) sourcePackage else currentAppPackage
        if (src.isBlank()) return

        // 仅处理白名单应用，且忽略自身
        if (!SUPPORTED_PACKAGES.contains(src)) {
            Log.d(TAG, "📋 源应用不在支持列表中，忽略: $src, reason=$reason")
            return
        }
        if (src == "com.readassist" || src == "com.readassist.debug") {
            Log.d(TAG, "📋 忽略来自本应用的复制信号")
            return
        }

        // 简单节流：同一应用2秒内只触发一次
        val now = System.currentTimeMillis()
        if (src == lastCopySignalPackage && now - lastCopySignalTime < 2000) {
            Log.d(TAG, "⏱️ 复制信号节流触发，忽略本次，src=$src, reason=$reason")
            return
        }
        lastCopySignalPackage = src
        lastCopySignalTime = now

        // 发送与原有流程一致的广播
        try {
            val intent = Intent("com.readassist.CLIPBOARD_CHANGED").apply {
                putExtra("source_app", src)
                putExtra("book_name", currentBookName)
                putExtra("timestamp", now)
                putExtra("reason", reason)
            }
            sendBroadcast(intent)
            Log.d(TAG, "📋 已广播复制信号（不读取内容），src=$src, reason=$reason")
        } catch (e: Exception) {
            Log.e(TAG, "发送复制信号广播失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取事件类型名称（用于调试）
     */
    private fun getEventTypeName(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SELECTED -> "VIEW_SELECTED"
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "VIEW_TEXT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> "VIEW_TEXT_SELECTION_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_HOVER_ENTER -> "VIEW_HOVER_ENTER"
            AccessibilityEvent.TYPE_VIEW_HOVER_EXIT -> "VIEW_HOVER_EXIT"
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> "TOUCH_EXPLORATION_GESTURE_START"
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> "TOUCH_EXPLORATION_GESTURE_END"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> "GESTURE_DETECTION_START"
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> "GESTURE_DETECTION_END"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_INTERACTION_START"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_INTERACTION_END"
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> "VIEW_CONTEXT_CLICKED"
            AccessibilityEvent.TYPE_ASSIST_READING_CONTEXT -> "ASSIST_READING_CONTEXT"
            0x00000080 -> "TYPE_VIEW_SCROLLED"
            0x00000100 -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
            0x00000200 -> "TYPE_ANNOUNCEMENT"
            0x00000400 -> "TYPE_VIEW_ACCESSIBILITY_FOCUSED"
            0x00000800 -> "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"
            0x00001000 -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
            0x00002000 -> "TYPE_WINDOWS_CHANGED"
            0x00004000 -> "TYPE_VIEW_CONTEXT_CLICKED"
            0x00008000 -> "TYPE_ASSIST_READING_CONTEXT"
            128 -> "TYPE_VIEW_SCROLLED"
            256 -> "TYPE_ANNOUNCEMENT"
            512 -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
            1024 -> "TYPE_VIEW_ACCESSIBILITY_FOCUSED"
            2048 -> "TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED"
            4096 -> "TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY"
            8192 -> "TYPE_WINDOWS_CHANGED"
            16384 -> "TYPE_VIEW_CONTEXT_CLICKED"
            32768 -> "TYPE_ASSIST_READING_CONTEXT"
            else -> "UNKNOWN($eventType)"
        }
    }
    
    /**
     * 更新当前应用信息
     */
    private fun updateCurrentAppInfo(eventPackageName: String, event: AccessibilityEvent) {
        val oldAppPackage = currentAppPackage
        val oldBookName = currentBookName
        
        Log.d(TAG, "🔄 更新应用信息 - 旧应用: '$oldAppPackage', 旧书籍: '$oldBookName'")
        Log.d(TAG, "🔄 更新应用信息 - 传入包名: '$eventPackageName', 事件类型: ${getEventTypeName(event.eventType)}")
        
        // 记录事件的详细信息
        val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
        val eventClassName = event.className?.toString() ?: ""
        Log.d(TAG, "🔄 事件详情 - 类名: '$eventClassName', 文本: '${eventText.take(100)}'")
        
        // 更新应用包名
        currentAppPackage = eventPackageName
        
        // 尝试从窗口标题提取书名
        val windowTitle = event.className?.toString() ?: ""
        if (windowTitle.isNotEmpty()) {
            val newBookName = extractBookNameFromTitle(windowTitle)
            if (newBookName.isNotEmpty()) {
                currentBookName = newBookName
                Log.d(TAG, "📚 从窗口标题提取书籍名: '$newBookName', 原始标题: '$windowTitle'")
            }
        }
        
        // 如果需要，也可以从事件文本中提取书名
        if (currentBookName.isEmpty() && eventText.isNotEmpty()) {
            val possibleBookName = extractBookNameFromTitle(eventText)
            if (possibleBookName.isNotEmpty()) {
                currentBookName = possibleBookName
                Log.d(TAG, "📚 从事件文本提取书籍名: '$possibleBookName', 原始文本: '${eventText.take(100)}'")
            }
        }
        
        Log.d(TAG, "📱 应用信息已更新 - 当前应用: '$currentAppPackage', 当前书籍: '$currentBookName'")
    }
    
    
    
    /**
     * 通知检测到文本
     */
    private fun notifyTextDetected(text: String) {
        if (currentAppPackage == "com.readassist" || currentAppPackage == "com.readassist.debug") {
            Log.d(TAG, "🚫 阻止广播来自ReadAssist应用自身的检测文本 (剪贴板): ${text.take(50)}...")
            return
        }
        Log.d(TAG, "Text detected (from Clipboard), broadcasting: ${text.take(50)}...")
        val intent = Intent(ACTION_TEXT_DETECTED).apply {
            putExtra(EXTRA_DETECTED_TEXT, text)
            putExtra(EXTRA_SOURCE_APP, currentAppPackage) // 这里的 currentAppPackage 会是剪贴板内容来源的应用
            putExtra(EXTRA_BOOK_NAME, currentBookName)
            putExtra(EXTRA_IS_SELECTION, false)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    /**
     * 通知文本已选择
     */
    private fun notifyTextSelected(text: String, isRequest: Boolean = false, 
                                 appPackage: String = currentAppPackage, 
                                 bookName: String = currentBookName) {
        // 应用自我检查
        if ((appPackage == "com.readassist" || appPackage == "com.readassist.debug") && !isRequest) {
            Log.d(TAG, "🚫 阻止广播来自ReadAssist应用自身的选中文本: ${text.take(50)}...")
            return
        }
        
        // 详细记录
        Log.d(TAG, "📢 通知文本已选择 - 来源应用: '$appPackage', 书籍: '$bookName', 请求标志: $isRequest")
        Log.d(TAG, "📢 通知内容: '${text.take(100)}...'")
        
        val intent = Intent(ACTION_TEXT_SELECTED).apply {
            putExtra(EXTRA_DETECTED_TEXT, text)
            putExtra(EXTRA_SOURCE_APP, appPackage)
            putExtra(EXTRA_BOOK_NAME, bookName)
            putExtra(EXTRA_IS_SELECTION, true)
        }
        
        // 使用普通广播而非本地广播
        sendBroadcast(intent)
        Log.d(TAG, "已发送文本选择广播")
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindowService() {
        try {
            Log.d(TAG, "🔴 尝试启动FloatingWindowServiceNew")
            val intent = Intent(this, FloatingWindowServiceNew::class.java)
            startService(intent)
            Log.d(TAG, "🔴 FloatingWindowServiceNew启动请求已发送")
            
            // 延迟检查服务是否真的启动成功
            Handler(Looper.getMainLooper()).postDelayed({
                checkFloatingWindowServiceStatus()
            }, 2000)
            
        } catch (e: Exception) {
            Log.e(TAG, "🔴 启动FloatingWindowServiceNew失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查悬浮窗服务状态
     */
    private fun checkFloatingWindowServiceStatus() {
        try {
            val sharedPrefs = getSharedPreferences("service_prefs", Context.MODE_PRIVATE)
            val isRunning = sharedPrefs.getBoolean("is_floating_service_running", false)
            
            Log.d(TAG, "🔴 检查FloatingWindowServiceNew状态: isRunning=$isRunning")
            
            if (!isRunning) {
                Log.e(TAG, "🔴 FloatingWindowServiceNew未正常运行，尝试重新启动")
                // 尝试重新启动
                Handler(Looper.getMainLooper()).postDelayed({
                    startFloatingWindowService()
                }, 1000)
            } else {
                Log.d(TAG, "🔴 FloatingWindowServiceNew运行正常")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔴 检查FloatingWindowServiceNew状态时发生异常: ${e.message}", e)
        }
    }
    
    /**
     * 停止悬浮窗服务
     */
    private fun stopFloatingWindowService() {
        try {
            val intent = Intent(this, FloatingWindowServiceNew::class.java)
            stopService(intent)
            Log.d(TAG, "FloatingWindowServiceNew stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingWindowServiceNew", e)
        }
    }
    
    /**
     * 手动获取当前页面文本（供外部调用）
     */
    fun getCurrentPageText(): String {
        Log.d(TAG, "getCurrentPageText (已禁用屏幕提取，将返回空)")
        return "" // 不再从屏幕提取
    }
    
    /**
     * 手动检查文本选择（供调试使用）
     */
    fun manualCheckTextSelection() {
        Log.d(TAG, "🔧 手动检查文本选择...")
        checkForTextSelection()
    }
    
    /**
     * 处理通用事件（尝试从任何事件中提取文本）
     */
    private fun handleGenericEvent(event: AccessibilityEvent) {
        Log.d(TAG, "其他事件 (仅记录): ${getEventTypeName(event.eventType)} from ${event.packageName}")
        // 不再主动提取和广播
    }
    
    /**
     * 处理文本变化事件
     */
    private fun handleTextChanged(event: AccessibilityEvent) {
        // 只记录，不主动提取和广播
        Log.d(TAG, "Text changed event (仅记录): ${event.text}")
    }
    
    /**
     * 处理文本选择变化事件
     */
    private fun handleTextSelectionChanged(event: AccessibilityEvent) {
        Log.d(TAG, "Text selection changed event (仅记录): ${event.text}")
        // 不再主动提取和广播
    }
    
    /**
     * 处理视图选择事件（备用方法）
     */
    private fun handleViewSelected(event: AccessibilityEvent) {
        Log.d(TAG, "View selected event (仅记录): ${event.text}")
        // 不再主动提取和广播
    }
    
    /**
     * 处理长按事件
     */
    private fun handleLongClick(event: AccessibilityEvent) {
        Log.d(TAG, "Long click event (仅记录): ${event.packageName}")
        // 不再主动提取和广播
    }
    
    /**
     * 处理焦点事件
     */
    private fun handleViewFocused(event: AccessibilityEvent) {
        // 只记录，不做特殊处理
        if (event.text?.isNotEmpty() == true) {
            Log.d(TAG, "焦点事件 (仅记录): ${event.text}")
        }
    }
    
    /**
     * 检查当前是否有文本选择
     */
    private fun checkForTextSelection() {
        Log.d(TAG, "🔍 检查当前文本选择状态...")
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            val selectedText = findSelectedTextInNode(rootNode)
            if (!selectedText.isNullOrBlank() && isValidText(selectedText)) {
                Log.d(TAG, "✅ 检查到选中文本: ${selectedText.take(50)}...")
                
                // Avoid duplicate text notifications
                if (selectedText != lastProcessedText) {
                    lastProcessedText = selectedText
                    // Update the call to use the new parameters
                    notifyTextSelected(selectedText, false, currentAppPackage, currentBookName)
                }
            } else {
                Log.d(TAG, "❌ 未检查到有效的选中文本")
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查文本选择时出错", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 从选择事件中提取选中的文本
     */
    private fun extractSelectedText(event: AccessibilityEvent): String? {
        Log.d(TAG, "🔍 开始提取选中文本...")
        
        // 方法1: 从事件中直接获取文本
        val eventText = event.text?.joinToString(" ") { it.toString() }
        Log.d(TAG, "方法1 - 事件文本: $eventText")
        if (!eventText.isNullOrBlank() && isValidText(eventText)) {
            Log.d(TAG, "✅ 方法1成功: $eventText")
            return eventText.trim()
        }
        
        // 方法2: 从源节点获取选中文本
        val sourceNode = event.source
        Log.d(TAG, "方法2 - 源节点: ${sourceNode != null}")
        if (sourceNode != null) {
            try {
                // 尝试获取选中的文本范围
                val nodeText = sourceNode.text?.toString()
                Log.d(TAG, "方法2 - 节点文本: $nodeText")
                Log.d(TAG, "方法2 - 选择范围: ${event.fromIndex} to ${event.toIndex}")
                
                if (!nodeText.isNullOrBlank()) {
                    // 如果有选择范围信息，提取选中部分
                    val fromIndex = event.fromIndex
                    val toIndex = event.toIndex
                    
                    if (fromIndex >= 0 && toIndex > fromIndex && toIndex <= nodeText.length) {
                        val selectedText = nodeText.substring(fromIndex, toIndex)
                        Log.d(TAG, "方法2 - 范围选择文本: $selectedText")
                        if (isValidText(selectedText)) {
                            Log.d(TAG, "✅ 方法2范围成功: $selectedText")
                            return selectedText.trim()
                        }
                    }
                    
                    // 如果没有有效的选择范围，返回整个节点文本
                    if (isValidText(nodeText)) {
                        Log.d(TAG, "✅ 方法2全文成功: $nodeText")
                        return nodeText.trim()
                    }
                }
            } finally {
                sourceNode.recycle()
            }
        }
        
        // 方法3: 尝试从根节点查找选中文本
        Log.d(TAG, "方法3 - 尝试从根节点查找...")
        val result = extractTextFromCurrentSelection()
        Log.d(TAG, "方法3 - 结果: $result")
        return result
    }
    
    /**
     * 从当前选择中提取文本
     */
    private fun extractTextFromCurrentSelection(): String? {
        val rootNode = rootInActiveWindow ?: return null
        
        try {
            return findSelectedTextInNode(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting selected text from root", e)
            return null
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 在节点树中查找选中的文本
     */
    private fun findSelectedTextInNode(node: AccessibilityNodeInfo): String? {
        // 检查当前节点是否被选中或包含选中文本
        if (node.isSelected) {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && isValidText(nodeText)) {
                return nodeText.trim()
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val selectedText = findSelectedTextInNode(childNode)
                    if (selectedText != null) {
                        return selectedText
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return null
    }
    
    /**
     * 从窗口事件中提取书名
     */
    private fun extractBookNameFromWindow(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        val text = event.text?.firstOrNull()?.toString() ?: ""
        
        currentBookName = extractBookNameFromTitle(text.ifEmpty { className })
    }
    
    /**
     * 从标题中提取书名
     */
    private fun extractBookNameFromTitle(title: String): String {
        Log.d(TAG, "📚 尝试从标题提取书籍名 - 原始标题: '$title', 当前应用: '$currentAppPackage'")
        
        // 如果标题是Android类名或布局名称，直接返回空
        if (title.startsWith("android.") || 
            title.contains("Layout") || 
            title.contains("View") ||
            title.contains("$")) {
            Log.d(TAG, "🚫 检测到Android组件名称，不提取书籍名: '$title'")
            return ""
        }
        
        // 特殊应用的标题处理
        val isSupernoteApp = currentAppPackage.contains("supernote") || currentAppPackage.contains("ratta")
        
        if (isSupernoteApp) {
            // Supernote的标题处理 - 增强版
            Log.d(TAG, "🔴 处理Supernote标题: '$title'")
            
            // 可能的Supernote标题模式列表
            val possibleBookName = when {
                // 情况1: 直接包含文件名
                title.contains(".pdf", ignoreCase = true) -> {
                    val nameWithExt = title.substringAfterLast("/").substringAfterLast("\\")
                    nameWithExt.replace(".pdf", "", ignoreCase = true).trim()
                }
                
                // 情况2: "文档名 - Supernote"格式
                title.contains(" - Supernote", ignoreCase = true) -> {
                    title.split(" - Supernote", ignoreCase = true)[0].trim()
                }
                
                // 情况3: 标题中包含"阅读器"并且是Supernote应用
                title.contains("阅读器", ignoreCase = true) && isSupernoteApp -> {
                    // 尝试找出实际的书名 - 通常在"阅读器"之前
                    val parts = title.split("阅读器")
                    if (parts.isNotEmpty() && parts[0].isNotEmpty()) {
                        parts[0].trim()
                    } else if (parts.size > 1 && parts[1].isNotEmpty()) {
                        parts[1].trim()
                    } else {
                        "Supernote文档"
                    }
                }
                
                // 情况4: 类名中包含文档或阅读相关词汇
                title.contains("Document", ignoreCase = true) || 
                title.contains("Reader", ignoreCase = true) -> {
                    if (title.contains(".")) {
                        // 如果是类名，使用简单名称
                        title.substringAfterLast(".").replace("Activity", "").replace("Fragment", "").trim()
                    } else {
                        title.trim()
                    }
                }
                
                // 情况5: 其他可能的格式，保持原样
                else -> title.trim()
            }
            
            val finalName = if (possibleBookName.isEmpty() || possibleBookName.length < 2) {
                "Supernote文档"
            } else {
                possibleBookName
            }
            
            Log.d(TAG, "🔴 Supernote标题处理结果: '$finalName'")
            return finalName
        }
        
        // 其他应用的标题处理，保持原来的逻辑
        if (currentAppPackage == "com.adobe.reader") {
            // Adobe阅读器的标题通常是：文件名 - Adobe Acrobat Reader
            val extractedName = title.replace(" - Adobe Acrobat Reader", "")
                        .replace(".pdf", "")
                        .trim()
            Log.d(TAG, "📚 Adobe Reader特殊处理 - 提取结果: '$extractedName'")
            return extractedName
        } else if (currentAppPackage == "com.kingsoft.moffice_eng") {
            // WPS Office的标题通常是：文件名 - WPS Office
            val extractedName = title.replace(" - WPS Office", "")
                        .replace(".docx", "")
                        .replace(".doc", "")
                        .replace(".ppt", "")
                        .replace(".pptx", "")
                        .replace(".xls", "")
                        .replace(".xlsx", "")
                        .trim()
            Log.d(TAG, "📚 WPS Office特殊处理 - 提取结果: '$extractedName'")
            return extractedName
        } else if (currentAppPackage == "com.supernote.document" || 
                   currentAppPackage == "com.ratta.supernote.launcher") {
            // Supernote的标题处理
            val extractedName = title.replace(" - Supernote", "")
                        .replace(" - SuperNote", "")
                        .replace("SuperNote Launcher", "")
                        .replace("Document", "")
                        .replace("阅读器", "")
                        .trim()
            Log.d(TAG, "📚 Supernote特殊处理 - 提取结果: '$extractedName'")
            return extractedName
        }
        
        // 移除常见的应用后缀
        val cleanTitle = title
            .replace(" - Adobe Acrobat Reader", "")
            .replace(" - WPS Office", "")
            .replace(" - Supernote", "")
            .replace(" - SuperNote", "")
            .replace("SuperNote Launcher", "")
            .replace("com.ratta.supernote.launcher", "")
            .replace("com.supernote.document", "")
            .replace(".pdf", "")
            .replace(".epub", "")
            .replace(".txt", "")
            .replace(".doc", "")
            .replace(".docx", "")
            .trim()
        
        // 如果清理后的标题为空或看起来像是一个类名，则不使用它
        if (cleanTitle.isEmpty() || 
            cleanTitle.contains(".") || 
            cleanTitle == "android" ||
            cleanTitle.length < 2) {
            Log.d(TAG, "🚫 标题清理后无效: '$cleanTitle'，原始: '$title'")
            return ""
        }
        
        // 如果标题过长，截断它
        val finalTitle = if (cleanTitle.length > 50) {
            cleanTitle.take(50) + "..."
        } else {
            cleanTitle
        }
        
        Log.d(TAG, "📚 通用处理提取书籍名称: '$finalTitle'，原始: '$title'")
        return finalTitle
    }
    
    /**
     * 判断是否是元数据事件（从事件层面过滤）
     */
    private fun isMetadataEvent(event: AccessibilityEvent): Boolean {
        val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
        val className = event.className?.toString() ?: ""
        
        // 检查是否是页码显示事件（通常来自状态栏或页面指示器）
        if (eventText.matches("^\\d+\\s*/\\s*\\d+.*".toRegex())) {
            Log.d(TAG, "🚫 检测到页码事件: $eventText")
            return true
        }
        
        // 检查是否是书籍信息显示事件（通常来自标题栏或信息栏）
        if (eventText.contains("Homo deus", ignoreCase = true) && 
            eventText.contains("Yuval Noah Harari", ignoreCase = true)) {
            Log.d(TAG, "🚫 检测到书籍信息事件: $eventText")
            return true
        }
        
        // 检查是否是文件管理器或导航相关的事件
        if (className.contains("NavigationBar") || 
            className.contains("StatusBar") ||
            className.contains("TitleBar") ||
            className.contains("ActionBar") ||
            className.contains("Toolbar")) {
            Log.d(TAG, "🚫 检测到导航/状态栏事件: $className")
            return true
        }
        
        // 检查是否包含文件路径或扩展名
        if (eventText.contains(".pdf", ignoreCase = true) ||
            eventText.contains(".epub", ignoreCase = true) ||
            eventText.contains("Anna's Archive", ignoreCase = true)) {
            Log.d(TAG, "🚫 检测到文件信息事件: $eventText")
            return true
        }
        
        return false
    }
    
    /**
     * 判断是否是书籍元数据（改进版本）
     */
    private fun isBookMetadata(text: String): Boolean {
        val pagePattern = """\d+\s*/\s*\d+""".toRegex()
        if (pagePattern.containsMatchIn(text)) return true

        val publicationPattern = """--.*?\\[.*?\\].*?--.*?,.*?,.*?\d{4}.*?--""".toRegex()
        if (publicationPattern.containsMatchIn(text)) return true

        if (text.contains("ISBN") || text.matches(""".*[a-f0-9]{32}.*""".toRegex())) return true
        if (text.contains("Anna's Archive", ignoreCase = true)) return true

        val metadataKeywords = listOf(
            "Homo deus", "Yuval Noah Harari", "Toronto, Ontario", "McClelland & Stewart",
            "brief history", "9780771038686", "7cc779d9f1068ac2c00aaf4d44be9c8e"
        )
        if (metadataKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }) return true
        if (text.matches("""^\d+\s*/\s*\d+.*""".toRegex())) return true
        if (text.contains("""\d{4}\s*--""".toRegex())) return true
        if (text.contains("""[a-f0-9]{32}""".toRegex())) return true
        if (text.contains("""\\[.*\\].*--.*--""".toRegex())) return true // Escaped brackets for regex
        if (text.split("--").size - 1 >= 3) return true
        if (text.contains("_") && text.contains("brief history", ignoreCase = true)) return true
        if (text.matches("""^\d+\s*/\s*\d+[A-Za-z].*""".toRegex())) return true
        if (text.contains("""\\[.*,.*\\]""".toRegex())) return true // Escaped brackets for regex
        val locationKeywords = listOf("Toronto", "Ontario", "McClelland", "Stewart")
        if (locationKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }) return true
        return false
    }
    
    /**
     * 判断是否是正文内容
     */
    private fun isContentText(text: String): Boolean {
        val contentIndicators = listOf(
            """\.""", """，""", """,""", """。""", """the\s+""", """and\s+""", """in\s+""", """of\s+""", """to\s+""",
            """that\s+""", """is\s+""", """was\s+""", """were\s+""", """have\s+""", """had\s+""",
            """will\s+""", """would\s+""", """can\s+""", """could\s+"""
        )
        val hasContentFeatures = contentIndicators.any { pattern ->
            text.contains(pattern.toRegex(RegexOption.IGNORE_CASE))
        }
        val hasGoodLength = text.length in 50..2000
        val hasMultipleWords = text.split("""\s+""".toRegex()).size > 10
        return hasContentFeatures && hasGoodLength && hasMultipleWords
    }

    // isValidText method - essential for clipboard content validation
    private fun isValidText(text: String): Boolean {
        if (text.length < 3) return false

        val commonUiTexts = listOf(
            "OK", "Cancel", "Yes", "No", "Edit", "Share", "Copy", "Paste",
            "Send", "Settings", "Menu", "Back", "Next", "Done",
            "ReadAssist", "智能阅读助手"
        )
        if (commonUiTexts.any { uiText -> text.equals(uiText, ignoreCase = true) }) {
            Log.d(TAG, "⚠️ 剪贴板文本被UI文本规则过滤: $text")
            return false
        }

        val invalidPatterns = listOf(
            """^https?://.*""", // URL
            """^\d+$""", // 纯数字
            """^[\s\p{Punct}]+$""" // 只有标点符号和空格
        )
        if (invalidPatterns.any { Regex(it).matches(text) }) {
            Log.d(TAG, "⚠️ 剪贴板文本被基础模式过滤: $text")
            return false
        }
        return true
    }
    
    // handleSelectedTextRequest method - called by textRequestReceiver
    private fun handleSelectedTextRequest() {
        Log.d(TAG, "🔍 处理选中文本请求 (依赖剪贴板)...")
        Log.d(TAG, "🔍 当前环境信息 - 应用包名: '$currentAppPackage', 书籍名称: '$currentBookName'")

        // 检查当前是否在 Supernote 应用中
        val isSupernoteApp = currentAppPackage.contains("supernote") || currentAppPackage.contains("ratta")
        if (isSupernoteApp) {
            Log.d(TAG, "🔴 在Supernote应用中处理文本请求: $currentAppPackage")
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (!text.isNullOrBlank() && isValidText(text)) {
                    Log.d(TAG, "✅ 从剪贴板获取到有效文本供请求: ${text.take(50)}...")
                    lastProcessedText = text // Update last processed text
                    
                    // 确保通知时使用正确的应用包名和书籍名称
                    var appPackage = if (currentAppPackage.isEmpty()) "com.readassist" else currentAppPackage
                    var bookName = if (currentBookName.isEmpty()) {
                        when {
                            appPackage.contains("supernote") || appPackage.contains("ratta") -> "Supernote文档"
                            appPackage == "com.adobe.reader" -> "PDF文档"
                            appPackage == "com.kingsoft.moffice_eng" -> "Office文档"
                            else -> "阅读笔记"
                        }
                    } else {
                        currentBookName
                    }
                    
                    // 如果处于默认状态但怀疑是Supernote应用，尝试纠正
                    if (appPackage == "com.readassist" && isSupernoteApp) {
                        Log.d(TAG, "🔴 检测到Supernote应用但应用包名为默认值，尝试纠正")
                        appPackage = "com.supernote.document"
                        if (bookName == "阅读笔记") {
                            bookName = "Supernote文档"
                        }
                    }
                    
                    Log.d(TAG, "📤 广播选中文本 - 应用: '$appPackage', 书籍: '$bookName'")
                    
                    // 广播选中文本，确保使用有效的应用和书籍信息
                    notifyTextSelected(text, true, appPackage, bookName)
                    return
                } else {
                    Log.d(TAG, "📋 剪贴板文本无效或为空 (for request): '${text?.take(50)}'")
                }
            } else {
                 Log.d(TAG, "📋 剪贴板无项目 (for request)")
            }
        } else {
            Log.d(TAG, "📋 剪贴板无主要剪辑 (for request)")
        }
        Log.d(TAG, "❌ 剪贴板无有效文本可供请求。")
        // Optionally, notify error or send empty: notifyTextSelectionError()
    }

    /**
     * 广播选中文本，通知浮动窗口服务
     */
    private fun broadcastSelectedText(text: String, isSelection: Boolean = true) {
        // 不要过于频繁地广播相同的文本
        if (text == lastProcessedText && text.length < 100) {
            Log.d(TAG, "跳过重复文本广播")
            return
        }
        
        lastProcessedText = text
        
        // 确保有效的书籍名称
        if (currentBookName.isEmpty() || 
            currentBookName.startsWith("android.") ||
            currentBookName.contains("Layout") ||
            currentBookName.contains("View") ||
            currentBookName.contains(".")) {
            
            // 尝试从上下文中提取一个合理的书籍名称
            val appName = when (currentAppPackage) {
                "com.supernote.document" -> "Supernote文档"
                "com.ratta.supernote.launcher" -> "Supernote阅读"
                "com.adobe.reader" -> "Adobe PDF阅读器"
                "com.kingsoft.moffice_eng" -> "WPS Office"
                "com.readassist" -> "ReadAssist"
                else -> currentAppPackage.substringAfterLast(".")
            }
            
            // 根据文本内容尝试提取书籍标题（取前几个字作为大致的书名）
            val possibleTitle = if (text.length > 30) {
                text.take(30).trim() + "..."
            } else if (text.isNotEmpty()) {
                text.trim()
            } else {
                appName
            }
            
            // 更新当前书籍名称
            currentBookName = possibleTitle
            Log.d(TAG, "📚 从选中文本更新书籍名称: $currentBookName")
        }
        
        val intent = Intent(if (isSelection) ACTION_TEXT_SELECTED else ACTION_TEXT_DETECTED).apply {
            putExtra(EXTRA_DETECTED_TEXT, text)
            putExtra(EXTRA_SOURCE_APP, currentAppPackage)
            putExtra(EXTRA_BOOK_NAME, currentBookName)
            putExtra(EXTRA_IS_SELECTION, isSelection)
            
            // 查找并添加文本选择的位置信息（如果有）
            val selectionBounds = getTextSelectionBounds()
            if (selectionBounds != null) {
                putExtra("SELECTION_X", selectionBounds.left)
                putExtra("SELECTION_Y", selectionBounds.top)
                putExtra("SELECTION_WIDTH", selectionBounds.width())
                putExtra("SELECTION_HEIGHT", selectionBounds.height())
                Log.d(TAG, "📍 添加选择位置到广播: $selectionBounds")
            }
        }
        
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📢 广播" + (if (isSelection) "选中" else "检测到的") + "文本: ${text.take(100)}...")
    }

    /**
     * 获取文本选择的边界位置
     */
    private fun getTextSelectionBounds(): android.graphics.Rect? {
        // 从根节点尝试查找选中的文本节点并获取其位置
        try {
            val rootNode = rootInActiveWindow ?: return null
            
            // 尝试查找被选中的节点
            val selectedNode = findSelectedNode(rootNode)
            if (selectedNode != null) {
                val rect = android.graphics.Rect()
                selectedNode.getBoundsInScreen(rect)
                selectedNode.recycle()
                
                if (rect.width() > 0 && rect.height() > 0) {
                    Log.d(TAG, "📍 找到选中文本位置: $rect")
                    return rect
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取文本选择位置时出错", e)
        }
        
        return null
    }
    
    /**
     * 查找被选中的节点
     */
    private fun findSelectedNode(rootNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 检查当前节点是否被选中
        if (rootNode.isSelected) {
            return rootNode
        }
        
        // 递归查找子节点
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i) ?: continue
            
            try {
                val selectedNode = findSelectedNode(child)
                if (selectedNode != null) {
                    return selectedNode
                }
            } finally {
                child.recycle()
            }
        }
        
        return null
    }

    /**
     * 获取当前应用包名
     */
    fun getCurrentAppPackage(): String {
        // 如果当前包名为空或无效，返回本应用包名
        return if (currentAppPackage.isEmpty() || currentAppPackage == "unknown") {
            "com.readassist"
        } else {
            currentAppPackage
        }
    }
    
    /**
     * 获取当前书籍名称
     */
    fun getCurrentBookName(): String {
        // 如果当前书籍名称为空或无效，返回默认名称
        return if (currentBookName.isEmpty() || 
                  currentBookName.startsWith("android.") ||
                  currentBookName.contains("Layout") ||
                  currentBookName.contains("View") ||
                  currentBookName.contains(".")) {
            "阅读笔记"
        } else {
            currentBookName
        }
    }
    
    /**
     * 获取最近的意图数据（用于调试）
     */
    fun getRecentIntentData(): String {
        try {
            // 简化实现，避免packageName冲突
            val sb = StringBuilder()
            
            // 1. 获取当前活动窗口
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                sb.append("窗口标题: ").append(rootNode.className ?: "未知").append("\n")
                
                // 2. 收集所有文本
                val texts = ArrayList<String>()
                findAllTexts(rootNode, texts)
                
                // 3. 尝试识别文件路径
                val pdfFilePaths = texts.filter { it -> 
                    it?.contains("/storage/") == true && 
                    (it?.contains(".pdf", true) == true || 
                     it?.contains(".mark", true) == true || 
                     it?.contains(".epub", true) == true)
                }
                
                if (pdfFilePaths.isNotEmpty()) {
                    sb.append("文件路径: ").append(pdfFilePaths.first()).append("\n")
                } else {
                    sb.append("未找到文件路径\n")
                }
                
                // 4. 记录当前应用包名
                sb.append("当前应用: ").append(currentAppPackage).append("\n")
                sb.append("当前书籍: ").append(currentBookName).append("\n")
            } else {
                sb.append("无法获取窗口信息")
            }
            
            return sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "获取意图数据时出错", e)
            return "获取意图数据出错: ${e.message}"
        }
    }
    
    /**
     * 辅助方法：递归查找所有文本
     */
    private fun findAllTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        
        try {
            // 添加节点文本
            val text = node.text
            if (text != null && text.isNotEmpty()) {
                texts.add(text.toString())
            }
            
            // 添加节点描述
            val desc = node.contentDescription
            if (desc != null && desc.isNotEmpty()) {
                texts.add(desc.toString())
            }
            
            // 递归子节点
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    findAllTexts(child, texts)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找文本时出错", e)
        }
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        screenshotObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                uri?.let { handleScreenshotUri(it) }
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            screenshotObserver!!
        )
        Log.d(TAG, "Screenshot observer registered.")
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            contentResolver.unregisterContentObserver(it)
            screenshotObserver = null
            Log.d(TAG, "Screenshot observer unregistered.")
        }
    }

    private var lastScreenshotTime = 0L
    private fun handleScreenshotUri(uri: Uri) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastScreenshotTime < 2000) {
            Log.d(TAG, "Screenshot received too quickly, skipping.")
            return
        }
        lastScreenshotTime = currentTime

        Log.d(TAG, "New screenshot detected: $uri")

        if (uri.scheme != "content" || uri.authority?.startsWith("media") != true) {
            Log.w(TAG, "URI scheme or authority does not look like a media URI, skipping: $uri")
            return
        }

        mainHandler.post {
            contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME))
                    val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH))

                    Log.d(TAG, "Screenshot details - DisplayName: $displayName, Path: $relativePath")

                    if (displayName?.contains("screenshot", ignoreCase = true) == true || relativePath?.contains("screenshot", ignoreCase = true) == true) {
                        val intent = Intent(ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY).apply {
                            putExtra(EXTRA_SCREENSHOT_URI, uri.toString())
                            setPackage(packageName)
                        }
                        context.sendBroadcast(intent)
                        Log.d(TAG, "Broadcast sent for screenshot taken via accessibility: $uri")
                    } else {
                        Log.d(TAG, "Image is not a screenshot, skipping.")
                    }
                }
            }
        }
    }

    private fun performScreenshot() {
        Log.e(TAG, "开始执行辅助功能截屏")
        
        // 设置等待截图状态
        isWaitingForScreenshot = true
        
        // 方法1：尝试使用模拟按键KEYCODE_SYSRQ (120)触发截屏
        try {
            val runtime = Runtime.getRuntime()
            runtime.exec("input keyevent 120")
            Log.e(TAG, "✅ 已模拟系统截屏键(KEYCODE_SYSRQ=120)")
            
            // 给截屏操作15秒超时
            Handler(Looper.getMainLooper()).postDelayed({
                if (isWaitingForScreenshot) {
                    Log.e(TAG, "⚠️ 截图监听超时，尝试备用检查方法")
                    isWaitingForScreenshot = false
                    
                    // 备用：检查文件系统
                    checkIReaderScreenshot()
                    checkStandardScreenshotDirectory()
                }
            }, 15000)
            
            return
        } catch (e: Exception) {
            Log.e(TAG, "❌ 模拟系统截屏键失败: ${e.message}")
            isWaitingForScreenshot = false
        }
        
        // 方法2：使用辅助功能API (Android 9+推荐方法)
        Log.e(TAG, "使用辅助功能API GLOBAL_ACTION_TAKE_SCREENSHOT")
        isWaitingForScreenshot = true
        val result = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        if (result) {
            Log.e(TAG, "✅ 系统截屏操作已成功触发")
            
            // 给截屏操作15秒超时
            Handler(Looper.getMainLooper()).postDelayed({
                if (isWaitingForScreenshot) {
                    Log.e(TAG, "⚠️ 截图监听超时，尝试备用检查方法")
                    isWaitingForScreenshot = false
                    
                    // 备用：检查文件系统
                    checkIReaderScreenshot()
                    checkStandardScreenshotDirectory()
                }
            }, 15000)
        } else {
            Log.e(TAG, "❌ 系统截屏操作失败，尝试备用方法")
            isWaitingForScreenshot = false
            
            // 尝试备用方法
            try {
                val intent = Intent()
                intent.setClassName(
                    "com.android.systemui",
                    "com.android.systemui.screenshot.TakeScreenshotService"
                )
                // 添加Android标准截屏服务所需的参数
                intent.putExtra("source", "accessibility_service")
                intent.putExtra("mode", 1) // 1=全屏截图
                
                startService(intent)
                Log.e(TAG, "✅ 已调用系统截屏服务")
                
                isWaitingForScreenshot = true
                // 给截屏操作15秒超时
                Handler(Looper.getMainLooper()).postDelayed({
                    if (isWaitingForScreenshot) {
                        Log.e(TAG, "⚠️ 截图监听超时，尝试备用检查方法")
                        isWaitingForScreenshot = false
                        
                        // 备用：检查文件系统
                        checkIReaderScreenshot()
                        checkStandardScreenshotDirectory()
                    }
                }, 15000)
            } catch (e: Exception) {
                Log.e(TAG, "❌ 所有截屏方法均失败: ${e.message}")
                isWaitingForScreenshot = false
            }
        }
    }

    private fun checkStandardScreenshotDirectory() {
        Log.e(TAG, "开始检查标准截屏目录")
        val standardDir = File("/sdcard/Pictures/Screenshots")
        if (standardDir.exists()) {
            Log.e(TAG, "✅ 标准截屏目录存在")
            val files = standardDir.listFiles()
            Log.e(TAG, "目录中的文件数量: ${files?.size ?: 0}")
            
            // 记录当前时间
            val currentTime = System.currentTimeMillis()
            Log.e(TAG, "当前时间戳: $currentTime")
            
            files?.forEach { file ->
                val timeDiff = currentTime - file.lastModified()
                Log.e(TAG, "文件: ${file.name}, 大小: ${file.length()}, 修改时间: ${file.lastModified()}, 时间差: ${timeDiff}ms")
            }
            
            // 只查找最近5秒内创建的文件
            val latestFile = files
                ?.filter { it.name.endsWith(".png") && (currentTime - it.lastModified()) < 5000 }
                ?.maxByOrNull { it.lastModified() }
            
            if (latestFile != null) {
                val fileUri = Uri.fromFile(latestFile)
                Log.e(TAG, "📸 从标准截屏目录找到新的截屏文件: $fileUri")
                val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
                intent.putExtra("screenshot_uri", fileUri)
                sendBroadcast(intent)
            } else {
                Log.e(TAG, "❌ 未在标准截屏目录找到新的截屏文件")
            }
        } else {
            Log.e(TAG, "❌ 标准截屏目录不存在")
        }
    }

    private fun handleScreenshotTaken(intent: Intent) {
        // 尝试从不同位置获取 URI
        val uri = intent.getParcelableExtra<Uri>("android.intent.extra.SCREENSHOT_URI")
        
        if (uri != null) {
            Log.e(TAG, "📸 系统截屏已保存: $uri")
            // 通知截屏完成
            val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
            intent.putExtra("screenshot_uri", uri)
            sendBroadcast(intent)
        } else {
            Log.e(TAG, "❌ 系统截屏URI为空，尝试从文件系统获取")
            checkLatestScreenshot()
        }
    }

    private fun checkIReaderScreenshot() {
        Log.e(TAG, "开始检查掌阅设备特有目录")
        val iReaderDir = File("/storage/emulated/0/iReader/saveImage/tmp")
        if (iReaderDir.exists()) {
            Log.e(TAG, "✅ 掌阅设备特有目录存在")
            val files = iReaderDir.listFiles()
            Log.e(TAG, "目录中的文件数量: ${files?.size ?: 0}")
            
            // 记录当前时间
            val currentTime = System.currentTimeMillis()
            Log.e(TAG, "当前时间戳: $currentTime")
            
            files?.forEach { file ->
                val timeDiff = currentTime - file.lastModified()
                Log.e(TAG, "文件: ${file.name}, 大小: ${file.length()}, 修改时间: ${file.lastModified()}, 时间差: ${timeDiff}ms")
            }
            
            // 只查找最近5秒内创建的文件
            val latestFile = files
                ?.filter { it.name.endsWith(".png") && (currentTime - it.lastModified()) < 5000 }
                ?.maxByOrNull { it.lastModified() }
            
            if (latestFile != null) {
                val fileUri = Uri.fromFile(latestFile)
                Log.e(TAG, "📸 从掌阅设备特有目录找到新的截屏文件: $fileUri")
                val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
                intent.putExtra("screenshot_uri", fileUri)
                sendBroadcast(intent)
            } else {
                Log.e(TAG, "❌ 未在掌阅设备特有目录找到新的截屏文件")
            }
        } else {
            Log.e(TAG, "❌ 掌阅设备特有目录不存在")
        }
    }

    private fun checkLatestScreenshot() {
        Log.e(TAG, "开始检查标准截屏目录")
        val screenshotDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val screenshotsDir = File(screenshotDir, "Screenshots")
        if (screenshotsDir.exists()) {
            Log.e(TAG, "✅ 标准截屏目录存在")
            val files = screenshotsDir.listFiles()
            Log.e(TAG, "目录中的文件数量: ${files?.size ?: 0}")
            files?.forEach { file ->
                Log.e(TAG, "文件: ${file.name}, 大小: ${file.length()}, 修改时间: ${file.lastModified()}")
            }
            
            val latestFile = files
                ?.filter { it.name.endsWith(".png") }
                ?.maxByOrNull { it.lastModified() }
            
            if (latestFile != null) {
                val fileUri = Uri.fromFile(latestFile)
                Log.e(TAG, "📸 从标准目录找到截屏文件: $fileUri")
                val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
                intent.putExtra("screenshot_uri", fileUri)
                sendBroadcast(intent)
            } else {
                Log.e(TAG, "❌ 未在标准目录找到截屏文件")
            }
        } else {
            Log.e(TAG, "❌ 标准截屏目录不存在")
        }
    }

    private fun initScreenshotObserver() {
        // 创建并注册媒体观察器
        if (screenshotObserver == null) {
            val observer = MediaContentObserver(Handler(mainLooper))
            screenshotObserver = observer
            contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            Log.e(TAG, "已注册媒体内容观察器，可以监听新增的图片")
        }
    }

    // 媒体内容观察器，用于监听新图片插入
    inner class MediaContentObserver(handler: Handler) : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            uri ?: return
            
            // 只在等待截图期间处理
            if (!isWaitingForScreenshot) return
            
            Log.e(TAG, "检测到媒体内容变化: $uri")
            
            try {
                // 检查这个URI是否是图片
                val isImage = uri.toString().startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())
                if (!isImage) return
                
                // 获取图片信息
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATA
                )
                
                contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                        val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                        
                        val id = cursor.getLong(idColumn)
                        val name = cursor.getString(nameColumn)
                        val dateAdded = cursor.getLong(dateColumn)
                        val filePath = cursor.getString(dataColumn)
                        
                        val currentTime = System.currentTimeMillis() / 1000
                        val timeDiff = currentTime - dateAdded
                        
                        Log.e(TAG, "新图片: id=$id, name=$name, dateAdded=$dateAdded, 时间差=${timeDiff}s, 路径=$filePath")
                        
                        // 判断是否是截图：名称包含screenshot或者在最近5秒添加的
                        val isScreenshot = (name.contains("screenshot", ignoreCase = true) || 
                                          name.contains("截图", ignoreCase = true) ||
                                          timeDiff < 5)
                        
                        if (isScreenshot) {
                            Log.e(TAG, "✅ 检测到新截图: $name")
                            
                            // 发送广播通知
                            val intent = Intent("com.readassist.service.SCREENSHOT_COMPLETED")
                            intent.putExtra("screenshot_uri", uri)
                            sendBroadcast(intent)
                            
                            // 重置等待状态
                            isWaitingForScreenshot = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理媒体变化异常: ${e.message}")
            }
        }
    }
} 