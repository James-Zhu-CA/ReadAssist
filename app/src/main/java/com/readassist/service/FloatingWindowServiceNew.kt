/**
 * 重构后的悬浮窗服务类，替代原来的FloatingWindowService
 * 
 * 重构目的：
 * 1. 使用模块化设计，将巨大的服务类拆分为多个专注于特定功能的管理器类
 * 2. 提高代码可维护性，每个管理器类只负责一项特定功能
 * 3. 符合单一职责原则，降低代码复杂度
 * 4. 便于功能扩展和测试
 * 
 * 优势：
 * 1. 代码结构更加清晰，易于理解和维护
 * 2. 各个功能模块之间的依赖关系更加明确
 * 3. 更容易添加新功能或修改现有功能
 * 4. 代码复用性更高，减少了冗余代码
 */
package com.readassist.service

import android.app.ActivityManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.readassist.ReadAssistApplication
import com.readassist.database.ChatEntity
import com.readassist.network.ApiResult
import com.readassist.service.managers.AiCommunicationManager
import com.readassist.service.managers.AiConfigurationManager
import com.readassist.service.managers.ChatWindowManager
import com.readassist.service.managers.FloatingButtonManager
import com.readassist.service.managers.ScreenshotManager
import com.readassist.service.managers.SessionManager
import com.readassist.service.managers.TextSelectionManager
import com.readassist.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import com.readassist.utils.DeviceUtils
import android.widget.Toast
import com.readassist.utils.PreferenceManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import java.util.Date
import java.util.Locale

/**
 * 重构后的悬浮窗服务
 */
class FloatingWindowServiceNew : Service(), 
    ScreenshotManager.ScreenshotCallbacks, 
    TextSelectionManager.TextSelectionCallbacks,
    ChatWindowManager.ChatWindowCallbacks,
    FloatingButtonManager.FloatingButtonCallbacks,
    ChatWindowManager.OnScreenshotMonitoringStateChangedListener {
    
    companion object {
        private const val TAG = "FloatingWindowServiceNew"
    }
    
    // 组件管理器
    private lateinit var floatingButtonManager: FloatingButtonManager
    private lateinit var chatWindowManager: ChatWindowManager
    private lateinit var screenshotManager: ScreenshotManager
    private lateinit var textSelectionManager: TextSelectionManager
    private lateinit var sessionManager: SessionManager
    private lateinit var aiConfigurationManager: AiConfigurationManager
    private lateinit var aiCommunicationManager: AiCommunicationManager
    
    // 应用实例和协程作用域
    private lateinit var app: ReadAssistApplication
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 广播接收器
    private val textDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TextAccessibilityService.ACTION_TEXT_DETECTED -> {
                    val text = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
                    val appPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
                    val bookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""
                    
                    Log.d(TAG, "📬 接收到ACTION_TEXT_DETECTED广播: app='$appPackage', book='$bookName'")
                    Log.d(TAG, "📬 文本内容: '${text.take(100)}...'")
                    
                    textSelectionManager.handleTextDetected(text, appPackage, bookName)
                }
                TextAccessibilityService.ACTION_TEXT_SELECTED -> {
                    val text = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
                    val appPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
                    val bookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""
                    
                    // 获取文本选择位置信息
                    val selectionX = intent.getIntExtra("SELECTION_X", -1)
                    val selectionY = intent.getIntExtra("SELECTION_Y", -1)
                    val selectionWidth = intent.getIntExtra("SELECTION_WIDTH", -1)
                    val selectionHeight = intent.getIntExtra("SELECTION_HEIGHT", -1)
                    
                    Log.d(TAG, "📬 接收到ACTION_TEXT_SELECTED广播: app='$appPackage', book='$bookName'")
                    Log.d(TAG, "📬 文本内容: '${text.take(100)}...'")
                    Log.d(TAG, "📬 选择位置: x=$selectionX, y=$selectionY, w=$selectionWidth, h=$selectionHeight")
                    
                    textSelectionManager.handleTextSelected(
                        text, appPackage, bookName, 
                        selectionX, selectionY, selectionWidth, selectionHeight
                    )
                }
                "com.readassist.TEXT_SELECTION_ACTIVE" -> {
                    textSelectionManager.handleTextSelectionActive()
                }
                "com.readassist.TEXT_SELECTION_INACTIVE" -> {
                    textSelectionManager.handleTextSelectionInactive()
                }
                "com.readassist.RECHECK_SCREENSHOT_PERMISSION" -> {
                    screenshotManager.recheckScreenshotPermission()
                }
                "com.readassist.SCREENSHOT_PERMISSION_GRANTED" -> {
                    screenshotManager.handlePermissionGranted()
                }
                "com.readassist.SCREENSHOT_PERMISSION_DENIED" -> {
                    screenshotManager.handlePermissionDenied()
                }
                "com.readassist.SCREENSHOT_PERMISSION_ERROR" -> {
                    val errorMessage = intent.getStringExtra("ERROR_MESSAGE") ?: "未知错误"
                    screenshotManager.handlePermissionError(errorMessage)
                }
            }
        }
    }
    
    private val screenshotTakenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TextAccessibilityService.ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY) {
                val uriString = intent.getStringExtra(TextAccessibilityService.EXTRA_SCREENSHOT_URI)
                if (uriString != null) {
                    val uri = Uri.parse(uriString)
                    Log.d(TAG, "Screenshot taken via accessibility: $uri")
                    serviceScope.launch {
                        processScreenshotFromUri(uri)
                    }
                }
            }
        }
    }
    
    private var pendingScreenshot: Uri? = null
    private var pendingScreenshotBitmap: Bitmap? = null
    
    private lateinit var preferenceManager: PreferenceManager
    
    // 新增：记录上一次截屏的文件路径
    private var lastScreenshotFile: File? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.e("FloatingWindowServiceNew", "onCreate called")
        Log.d(TAG, "FloatingWindowService created")
        
        // 初始化核心组件
        app = application as ReadAssistApplication
        // 初始化preferenceManager
        preferenceManager = PreferenceManager(applicationContext)
        // 初始化各个管理器
        initializeManagers()
        
        // 设置自己作为勾选状态监听器
        chatWindowManager.setOnScreenshotMonitoringStateChangedListener(this)
        
        // 注册广播接收器
        registerReceivers()
        
        // 创建通知渠道和前台服务通知
        setupForegroundService()
        
        // 检查截屏权限状态，如果处于中间状态则重置
        serviceScope.launch {
            delay(500) // 从2000ms减少到500ms
            Log.d(TAG, "执行启动后权限检查...")
            
            if (app.preferenceManager.isScreenshotPermissionGranted()) {
                // 如果权限已授予，但截屏功能不可用，则尝试重置
                if (!screenshotManager.isScreenshotServiceReady()) {
                    Log.w(TAG, "检测到权限状态异常，执行重置")
                    screenshotManager.forceResetPermission()
                }
            }
        }
        
        // 标记服务已启动
        getSharedPreferences("service_prefs", MODE_PRIVATE)
            .edit().putBoolean("is_floating_service_running", true).apply()
    }
    
    /**
     * 初始化管理器
     */
    private fun initializeManagers() {
        Log.e(TAG, "initializeManagers called")
        
        // 初始化会话管理器
        sessionManager = SessionManager(
            chatRepository = app.chatRepository
        )
        
        // 初始化文本选择管理器
        textSelectionManager = TextSelectionManager()
        textSelectionManager.setCallbacks(this)
        
        // 初始化截屏管理器
        screenshotManager = ScreenshotManager(
            context = this,
            preferenceManager = preferenceManager,
            coroutineScope = serviceScope,
            callbacks = this
        )
        
        // 初始化聊天窗口管理器
        chatWindowManager = ChatWindowManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            preferenceManager = preferenceManager,
            coroutineScope = serviceScope,
            callbacks = this
        )
        chatWindowManager.setTextSelectionManager(textSelectionManager)
        
        // 初始化AI配置管理器
        aiConfigurationManager = AiConfigurationManager(
            context = this,
            preferenceManager = preferenceManager
        )
        
        // 初始化AI通信管理器
        aiCommunicationManager = AiCommunicationManager(
            chatRepository = app.chatRepository,
            geminiRepository = app.geminiRepository,
            preferenceManager = preferenceManager
        )
        
        // 初始化悬浮按钮管理器
        floatingButtonManager = FloatingButtonManager(
            context = this,
            windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            preferenceManager = preferenceManager,
            callbacks = this
        )
        floatingButtonManager.createButton()
        screenshotManager.initialize()
    }
    
    /**
     * 注册广播接收器
     */
    private fun registerReceivers() {
        Log.e("FloatingWindowServiceNew", "registerReceivers called")
        val textFilter = IntentFilter().apply {
            addAction(TextAccessibilityService.ACTION_TEXT_DETECTED)
            addAction(TextAccessibilityService.ACTION_TEXT_SELECTED)
            addAction("com.readassist.TEXT_SELECTION_ACTIVE")
            addAction("com.readassist.TEXT_SELECTION_INACTIVE")
            addAction("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
            addAction("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_DENIED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_ERROR")
        }
        registerReceiver(textDetectedReceiver, textFilter)

        val screenshotFilter = IntentFilter(TextAccessibilityService.ACTION_SCREENSHOT_TAKEN_VIA_ACCESSIBILITY)
        registerReceiver(screenshotTakenReceiver, screenshotFilter)
    }
    
    /**
     * 设置前台服务
     */
    private fun setupForegroundService() {
        val channelId = "read_assist_foreground_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "ReadAssist前台服务",
                android.app.NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
        
        val notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("ReadAssist正在运行")
            .setContentText("点击管理应用")
            .setSmallIcon(com.readassist.R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
            
        // 启动前台服务，防止系统杀死
        startForeground(1001, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e(TAG, "onStartCommand called")
        Log.d(TAG, "FloatingWindowService started")
        
        // 检查悬浮窗权限
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "No overlay permission, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // 检查截屏监控设置
        val autoPopup = preferenceManager.getBoolean("screenshot_auto_popup", true)
        if (chatWindowManager != null) {
            chatWindowManager.setScreenshotMonitoringEnabled(autoPopup)
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    /**
     * 当服务被销毁时调用
     */
    override fun onDestroy() {
        Log.e(TAG, "onDestroy called")
        try {
            // 注销广播接收器
            unregisterReceiver(textDetectedReceiver)
            unregisterReceiver(screenshotTakenReceiver)
            Log.e(TAG, "已注销所有广播接收器")
        } catch (e: Exception) {
            Log.e(TAG, "注销广播接收器失败", e)
        }
        
        // 销毁管理器
        screenshotManager?.cleanup()
        chatWindowManager?.hideChatWindow()
        floatingButtonManager?.removeButton()
        
        super.onDestroy()
        
        // 标记服务已停止
        getSharedPreferences("service_prefs", MODE_PRIVATE)
            .edit().putBoolean("is_floating_service_running", false).apply()
    }
    
    /**
     * 处理悬浮按钮点击
     */
    private fun handleFloatingButtonClick() {
        Log.e(TAG, "[日志追踪] handleFloatingButtonClick 被调用")
        Log.d(TAG, "🔘 悬浮按钮被点击")
        
        // 立即显示点击反馈
        floatingButtonManager.showScreenshotAnalysisState()
        
        // 检查AI配置是否完成
        if (!aiConfigurationManager.isConfigurationValid()) {
            Log.d(TAG, "❌ AI配置未完成，显示配置提示")
            showConfigurationRequiredDialog()
            
            // 恢复按钮状态
            floatingButtonManager.restoreDefaultState()
            return
        }
        
        // 新增：记录当前待处理截图状态
        Log.e(TAG, "当前待处理截图状态: ${pendingScreenshotBitmap != null}, 是否已回收: ${pendingScreenshotBitmap?.isRecycled}")
        
        // 如果没有待处理截图，尝试获取最近的截图
        if (pendingScreenshotBitmap == null || pendingScreenshotBitmap?.isRecycled == true) {
            Log.e(TAG, "尝试在后台主动获取最近的截图")
            getRecentScreenshot()
        }
        
        // 记录详细的上下文信息（调试）
        Log.d(TAG, "🔍 详细调试 - 点击悬浮按钮时的环境信息:")
        try {
            // 1. 尝试从TextAccessibilityService直接获取信息
            val accessibilityService = app.getSystemService("accessibilityService") as? TextAccessibilityService
            if (accessibilityService != null) {
                val realAppPackage = accessibilityService.getCurrentAppPackage()
                val realBookName = accessibilityService.getCurrentBookName()
                val rawAppPackage = accessibilityService.currentAppPackageRaw
                val rawBookName = accessibilityService.currentBookNameRaw
                
                Log.d(TAG, "🔍 TextAccessibilityService(直接) - 当前应用: '$rawAppPackage', 处理后: '$realAppPackage'")
                Log.d(TAG, "🔍 TextAccessibilityService(直接) - 当前书籍: '$rawBookName', 处理后: '$realBookName'")
                
                // 新增：获取当前屏幕上的所有窗口信息
                Log.d(TAG, "🔍 === UI信息收集开始 ===")
                
                // 获取当前活动窗口
                val currentWindow = accessibilityService.windows?.firstOrNull { it.isActive }
                Log.d(TAG, "🔍 当前活动窗口: ${currentWindow?.id}, 类型: ${currentWindow?.type}, 标题: ${currentWindow?.title}")
                
                // 获取所有窗口标题
                val allWindowTitles = accessibilityService.windows?.mapNotNull { it.title }?.joinToString(" | ") ?: "无窗口标题"
                Log.d(TAG, "🔍 所有窗口标题: $allWindowTitles")
                
                // 获取当前活动窗口的根节点
                val rootNode = accessibilityService.rootInActiveWindow
                Log.d(TAG, "🔍 根节点: ${rootNode?.className}, 包名: ${rootNode?.packageName}, 文本: ${rootNode?.text}, 内容描述: ${rootNode?.contentDescription}")
                
                // 提取所有可见文本
                val allVisibleText = mutableListOf<String>()
                rootNode?.let { collectAllText(it, allVisibleText) }
                Log.d(TAG, "🔍 所有可见文本 (最多显示30个): ${allVisibleText.take(30).joinToString(" | ")}")
                
                // 尝试查找与PDF或文档相关的信息
                val potentialBookNames = allVisibleText.filter { text ->
                    text.contains(".pdf", ignoreCase = true) || 
                    text.contains("book", ignoreCase = true) || 
                    text.contains("document", ignoreCase = true) ||
                    text.length > 20 // 长文本可能是标题
                }
                Log.d(TAG, "🔍 潜在书籍名称: ${potentialBookNames.joinToString(" | ")}")
                
                // 尝试获取当前焦点元素信息
                val focusedNode = findFocusedNode(rootNode)
                focusedNode?.let {
                    Log.d(TAG, "🔍 当前焦点元素: ${it.className}, 文本: ${it.text}, 内容描述: ${it.contentDescription}")
                }
                
                // 尝试查找标题栏或工具栏中的信息
                val toolbarTexts = findToolbarTexts(rootNode)
                Log.d(TAG, "🔍 工具栏/标题栏文本: ${toolbarTexts.joinToString(" | ")}")
                
                // 获取最近打开的文件信息（如果有）
                val recentFilesInfo = app.preferenceManager.getString("recent_files_info", "")
                Log.d(TAG, "🔍 最近文件信息缓存: $recentFilesInfo")
                
                // 检查意图数据
                val intentData = accessibilityService.getRecentIntentData()
                Log.d(TAG, "🔍 最近意图数据: $intentData")
                
                // 检查缓存中的任何相关信息
                val cachedAppPackage = app.preferenceManager.getString("last_successful_app_package", "")
                val cachedBookName = app.preferenceManager.getString("last_successful_book_name", "")
                val lastText = app.preferenceManager.getString("last_selected_text", "")
                Log.d(TAG, "🔍 缓存的应用信息: 包名=$cachedAppPackage, 书籍=$cachedBookName")
                Log.d(TAG, "🔍 最后选中的文本 (前50字符): ${lastText.take(50)}")
                
                Log.d(TAG, "🔍 === UI信息收集完成 ===")
            } else {
                Log.d(TAG, "🔍 无法直接访问TextAccessibilityService实例")
                
                // 无法通过AccessibilityService获取UI信息，使用其他方法
                Log.d(TAG, "🔍 === 尝试使用替代方法收集UI信息 ===")
                
                // 方法1: 使用ActivityManager获取当前前台应用信息
                try {
                    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val tasks = activityManager.getRunningTasks(1)
                    if (tasks.isNotEmpty()) {
                        val topActivity = tasks[0].topActivity
                        Log.d(TAG, "🔍 当前前台应用: ${topActivity?.packageName}, 活动: ${topActivity?.className}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "🔍 无法获取前台应用信息: ${e.message}")
                }
                
                // 方法2: 使用UsageStatsManager获取最近使用的应用
                try {
                    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                    val time = System.currentTimeMillis()
                    val stats = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60 * 60, time)
                    
                    if (stats.isNotEmpty()) {
                        val recentStats = stats.maxByOrNull { it.lastTimeUsed }
                        Log.d(TAG, "🔍 最近使用的应用: ${recentStats?.packageName}, 最后使用时间: ${recentStats?.lastTimeUsed}")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "🔍 无法获取应用使用统计: ${e.message}")
                }
                
                // 方法3: 检查当前会话信息和缓存
                val sessionId = sessionManager.getCurrentSessionId()
                val appPackage = sessionManager.getCurrentAppPackage()
                val bookName = sessionManager.getCurrentBookName()
                Log.d(TAG, "🔍 当前会话信息: 会话ID=$sessionId, 应用=$appPackage, 书籍=$bookName")
                
                // 检查缓存中的任何相关信息
                val cachedAppPackage = app.preferenceManager.getString("last_successful_app_package", "")
                val cachedBookName = app.preferenceManager.getString("last_successful_book_name", "")
                val lastText = app.preferenceManager.getString("last_selected_text", "")
                Log.d(TAG, "🔍 缓存的应用信息: 包名=$cachedAppPackage, 书籍=$cachedBookName")
                Log.d(TAG, "🔍 最后选中的文本 (前50字符): ${lastText.take(50)}")
                
                Log.d(TAG, "🔍 === 替代UI信息收集完成 ===")
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔍 获取AccessibilityService信息失败", e)
        }
        
        // 2. 通过TextSelectionManager获取信息
        val managerAppPackage = textSelectionManager.getCurrentAppPackage()
        val managerBookName = textSelectionManager.getCurrentBookName()
        Log.d(TAG, "🔍 TextSelectionManager - 当前应用: '$managerAppPackage', 书籍: '$managerBookName'")
        
        // 3. 检查偏好设置中的值
        val prefAppPackage = app.preferenceManager.getString("current_app_package", "未设置")
        val prefBookName = app.preferenceManager.getString("current_book_name", "未设置")
        Log.d(TAG, "🔍 PreferenceManager - 上次保存的应用: '$prefAppPackage', 书籍: '$prefBookName'")
        
        // 先获取最新的选中文本和应用信息
        Log.d(TAG, "📝 请求选中文本...")
        textSelectionManager.requestSelectedTextFromAccessibilityService()
        
        // 主动获取当前应用包名和书籍名称，并记录到日志中
        val appPackageName = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()
        
        Log.d(TAG, "📱 点击悬浮按钮时获取到的应用信息: 包名=$appPackageName, 书籍=$bookName")
        
        // 立即更新会话管理器中的应用和书籍信息
        sessionManager.setCurrentApp(appPackageName)
        sessionManager.setCurrentBook(bookName)
        
        // 保存应用和书籍信息到偏好设置，以便聊天窗口打开时使用
        app.preferenceManager.setString("current_app_package", appPackageName)
        app.preferenceManager.setString("current_book_name", bookName)
        
        // 根据设备类型执行不同操作
        if (DeviceUtils.isIReaderDevice()) {
            // 掌阅设备：直接显示聊天窗口，不主动截屏
            Log.d(TAG, "掌阅设备：点击悬浮按钮，直接显示聊天窗口")
            chatWindowManager.showChatWindow()
            // 恢复按钮的默认状态，因为我们没有在等待截屏
            floatingButtonManager.restoreDefaultState()
        } else {
            // 非掌阅设备：保持原有逻辑，先截屏再显示窗口
                            Log.e(TAG, "[统一流程] 即将执行 performScreenshotFirst()，使用统一弹窗机制")
                performScreenshotFirst()
        }
    }
    
    /**
     * 辅助方法：收集AccessibilityNodeInfo中的所有文本
     */
    private fun collectAllText(node: android.view.accessibility.AccessibilityNodeInfo?, result: MutableList<String>) {
        if (node == null) return
        
        // 添加节点自己的文本
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { result.add(it) }
        
        // 遍历所有子节点
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                collectAllText(child, result)
                child?.recycle()  // 重要：回收不再使用的AccessibilityNodeInfo对象
            } catch (e: Exception) {
                Log.e(TAG, "收集文本时出错", e)
            }
        }
    }
    
    /**
     * 辅助方法：查找具有焦点的节点
     */
    private fun findFocusedNode(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null) return null
        
        if (node.isFocused) return node
        
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                val focusedNode = findFocusedNode(child)
                if (focusedNode != null) {
                    return focusedNode
                }
                child?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "查找焦点节点时出错", e)
            }
        }
        
        return null
    }
    
    /**
     * 辅助方法：查找工具栏或标题栏中的文本
     */
    private fun findToolbarTexts(node: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
        if (node == null) return emptyList()
        
        val results = mutableListOf<String>()
        
        // 查找类名包含Toolbar, ActionBar, TitleBar等的节点
        val className = node.className?.toString() ?: ""
        if (className.contains("Toolbar", ignoreCase = true) || 
            className.contains("ActionBar", ignoreCase = true) || 
            className.contains("TitleBar", ignoreCase = true) || 
            className.contains("Header", ignoreCase = true)) {
            
            // 收集这个节点下的所有文本
            collectAllText(node, results)
            return results
        }
        
        // 递归查找子节点
        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i)
                results.addAll(findToolbarTexts(child))
                child?.recycle()
            } catch (e: Exception) {
                Log.e(TAG, "查找工具栏文本时出错", e)
            }
        }
        
        return results
    }
    
    /**
     * 执行截屏分析 - 统一流程版本
     * 所有设备都只执行截屏，不立即弹窗，让目录监控统一处理弹窗逻辑
     */
    private fun performScreenshotFirst() {
        Log.e(TAG, "[统一流程] performScreenshotFirst 开始执行 - 只截屏不弹窗")
        
        // 显示截屏分析状态，给用户反馈
        floatingButtonManager.showScreenshotAnalysisState()
        
        // 使用协程避免阻塞UI线程
        serviceScope.launch {
            try {
                // 预先检查权限状态
                if (!screenshotManager.isScreenshotServiceReady()) {
                    Log.e(TAG, "[统一流程] 截屏服务未就绪，重新检查权限")
                    screenshotManager.recheckScreenshotPermission()
                }
                
                // 执行截屏 - 关键：不再立即弹窗
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "[统一流程] 开始执行截屏，等待目录监控触发弹窗")
                    screenshotManager.performScreenshot()
                    
                    // 统一流程：不立即处理截屏结果，让目录监控统一触发
                    // 添加一个超时保护机制，防止截屏失败时用户无反馈
                    serviceScope.launch {
                        delay(5000) // 5秒超时
                        
                        // 检查是否还在截屏状态且没有弹窗
                        if (!chatWindowManager.isVisible()) {
                            Log.e(TAG, "[统一流程] 截屏超时，可能失败，强制恢复UI并弹窗")
                            withContext(Dispatchers.Main) {
                                floatingButtonManager.restoreDefaultState()
                                // 显示一个提示弹窗告知用户
                                chatWindowManager.showChatWindow()
                                chatWindowManager.addSystemMessage("截屏可能失败，请重试或检查权限设置")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[统一流程] 截屏处理异常", e)
                withContext(Dispatchers.Main) {
                    floatingButtonManager.restoreDefaultState()
                    chatWindowManager.showChatWindow()
                    chatWindowManager.addSystemMessage("截屏失败：${e.message}")
                }
            }
        }
    }
    
    /**
     * 显示配置必需对话框
     */
    private fun showConfigurationRequiredDialog() {
        aiConfigurationManager.showConfigurationRequiredDialog(
            onOpenMainApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            },
            onQuickConfig = {
                chatWindowManager.showChatWindow()
                showQuickConfigurationDialog()
            }
        )
    }
    
    /**
     * 显示快速配置对话框
     */
    private fun showQuickConfigurationDialog() {
        aiConfigurationManager.showPlatformSelectionDialog { platform ->
            showApiKeyInputDialog(platform)
        }
    }
    
    /**
     * 显示API Key输入对话框
     */
    private fun showApiKeyInputDialog(platform: com.readassist.model.AiPlatform) {
        aiConfigurationManager.showApiKeyInputDialog(platform) { success ->
            if (success) {
                // 更新AI配置UI
                chatWindowManager.updateAiConfigurationStatus()
            }
        }
    }
    
    /**
     * 发送用户消息
     */
    private fun sendUserMessage(message: String) {
        Log.d(TAG, "=== sendUserMessage 开始 ===")
        Log.e(TAG, "原始输入框内容: $message")
        // 读取勾选项状态
        val sendScreenshot = chatWindowManager.isSendScreenshotChecked()
        val sendClipboard = chatWindowManager.isSendClipboardChecked()
        Log.e(TAG, "勾选项状态 - 发送截图: $sendScreenshot, 发送剪贴板: $sendClipboard")
        
        // 读取剪贴板内容
        val clipboardContent = if (sendClipboard) getTodayClipboardContent() else null
        Log.e(TAG, "剪贴板内容: $clipboardContent")
        
        // 检查是否有有效内容
        val hasUserInput = !message.isNullOrBlank()
        val hasClipboardContent = !clipboardContent.isNullOrBlank()
        val hasScreenshot = sendScreenshot && pendingScreenshotBitmap != null && !pendingScreenshotBitmap!!.isRecycled
        
        // 如果都没有内容，显示错误并返回
        if (!hasUserInput && !hasClipboardContent && !hasScreenshot) {
            Log.e(TAG, "❌ 没有任何内容可发送 (无输入、无剪贴板、无截图)")
            return
        }
        
        // 组合最终要发送的文本内容
        val sb = StringBuilder()
        if (!message.isNullOrBlank()) sb.append(message)
        if (!clipboardContent.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[剪贴板内容] ").append(clipboardContent)
        }
        
        // 如果没有用户输入但有截图，添加默认文本
        if (sb.isEmpty() && hasScreenshot) {
            sb.append("请分析这张截屏图片：")
        }
        
        val finalText = sb.toString()
        Log.e(TAG, "最终要发送的文本内容: " + finalText.replace("\n", "\\n"))
        
        // 最终文本为空时不发送
        if (finalText.isBlank()) {
            Log.e(TAG, "❌ 最终文本为空，不发送消息")
            return
        }
        
        // 只有勾选了"发送截屏图片"且有图片时才带上图片，否则只发文本
        val currentPendingBitmap = if (sendScreenshot) pendingScreenshotBitmap else null
        Log.e(TAG, "待处理截图状态: ${currentPendingBitmap != null}, 是否已回收: ${currentPendingBitmap?.isRecycled}")
        if (currentPendingBitmap != null && !currentPendingBitmap.isRecycled) {
            Log.d(TAG, "✅ 勾选了发送截图，且有待处理截图，尺寸: ${currentPendingBitmap.width}x${currentPendingBitmap.height}")
            pendingScreenshotBitmap = null
            sendImageMessageToAI(finalText, currentPendingBitmap)
        } else {
            if (currentPendingBitmap != null) {
                Log.w(TAG, "⚠️ 截图已回收，作为纯文本消息发送")
                pendingScreenshotBitmap = null
            }
            Log.d(TAG, "🔤 未勾选发送截图或无截图，仅发送文本")
            sendTextMessageToAI(finalText)
        }
        Log.d(TAG, "=== sendUserMessage 结束 ===")
    }
    
    /**
     * 发送文本消息到AI
     */
    private fun sendTextMessageToAI(message: String) {
        Log.d(TAG, "发送纯文本消息: $message")
        serviceScope.launch {
            try {
                // 获取当前会话ID
                val sessionId = sessionManager.getCurrentSessionId()
                if (sessionId.isEmpty()) {
                    Log.e(TAG, "会话ID为空，无法发送消息")
                    return@launch
                }

                // 获取当前应用包名和书籍名称
                val appPackage = textSelectionManager.getCurrentAppPackage()
                val bookName = textSelectionManager.getCurrentBookName()

                // 检查 API Key
                val apiKey = preferenceManager.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "API Key 未设置")
                    return@launch
                }
                Log.d(TAG, "API Key 已设置，长度: ${apiKey.length}")

                // 添加用户消息到聊天窗口
                chatWindowManager.addUserMessage(message)
                
                // 添加加载动画
                chatWindowManager.addLoadingMessage("AI思考中...")

                // 发送消息到AI并获取响应
                val result = aiCommunicationManager.sendTextMessage(
                    sessionId = sessionId,
                    message = message,
                    appPackage = appPackage,
                    bookName = bookName
                )
                
                // 移除加载动画
                chatWindowManager.removeLastMessage()

                when (result) {
                    is ApiResult.Success -> {
                        // 添加AI响应到聊天窗口
                        chatWindowManager.addAiMessage(result.data)
                        
                        // 保存消息到数据库
                        val chatEntity = ChatEntity(
                            sessionId = sessionId,
                            bookName = bookName,
                            appPackage = appPackage,
                            userMessage = message,
                            aiResponse = result.data,
                            promptTemplate = "",
                            timestamp = System.currentTimeMillis()
                        )
                        app.chatRepository.saveChatEntity(chatEntity)
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "发送消息失败", result.exception)
                    }
                    is ApiResult.NetworkError -> {
                        Log.e(TAG, "发送消息网络错误：${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送消息失败", e)
            }
        }
    }
    
    /**
     * 发送图片消息到AI
     */
    private fun sendImageMessageToAI(message: String, bitmap: Bitmap) {
        Log.e(TAG, "sendImageMessageToAI 被调用，bitmap: $bitmap, isRecycled: ${bitmap.isRecycled}")
        Log.d(TAG, "=== sendImageMessageToAI 开始 ===")
        Log.d(TAG, "📤 发送图片消息，尺寸: ${bitmap.width}x${bitmap.height}")
        
        // 创建一个标记以跟踪是否已回收图片
        val bitmapRef = AtomicReference(bitmap)
        
        serviceScope.launch {
            try {
                // 检查bitmap是否已回收
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ 图片已被回收，无法发送")
                    return@launch
                }
                
                Log.d(TAG, "✅ 图片检查通过，继续处理")
                
                // 确保会话ID已初始化
                val sessionId = ensureSessionInitialized()
                
                // 获取应用和书籍信息
                val appPackage = sessionManager.getSanitizedAppPackage()
                val bookName = sessionManager.getSanitizedBookName()
                
                // 添加用户消息到UI
                chatWindowManager.addUserMessage("$message 📸")
                
                // 添加加载消息
                chatWindowManager.addLoadingMessage("🤖 正在分析图片和您的问题...")
                
                Log.d(TAG, "🚀 调用AI服务分析图片...")
                
                // 发送图片消息到AI
                val result = aiCommunicationManager.sendImageMessage(
                    sessionId = sessionId,
                    message = message,
                    bitmap = bitmapRef.get(),  // 使用引用，确保最新状态
                    appPackage = appPackage,
                    bookName = bookName
                )
                
                Log.d(TAG, "✅ AI服务返回结果")
                
                // 移除加载消息
                chatWindowManager.removeLastMessage()
                
                // 处理结果
                when (result) {
                    is com.readassist.network.ApiResult.Success -> {
                        if (result.data.isBlank()) {
                            chatWindowManager.addAiMessage("🤖 AI分析结果为空，可能是图片内容无法识别", true)
                        } else {
                            chatWindowManager.addAiMessage(result.data)
                        }
                    }
                    is com.readassist.network.ApiResult.Error -> {
                        val errorMessage = result.exception.message ?: "未知错误"
                        chatWindowManager.addAiMessage("🚫 图片分析失败：$errorMessage", true)
                    }
                    is com.readassist.network.ApiResult.NetworkError -> {
                        chatWindowManager.addAiMessage("🌐 网络错误：${result.message}", true)
                    }
                }
                
                // 图片处理完毕，回收图片
                val currentBitmap = bitmapRef.getAndSet(null)
                if (currentBitmap != null && !currentBitmap.isRecycled) {
                    Log.d(TAG, "♻️ 图片处理完毕，回收图片")
                    currentBitmap.recycle()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "发送图片消息异常", e)
                chatWindowManager.removeLastMessage()
                chatWindowManager.addAiMessage("❌ 发送失败：${e.message}", true)
                
                // 出错也需要回收图片
                val currentBitmap = bitmapRef.getAndSet(null)
                if (currentBitmap != null && !currentBitmap.isRecycled) {
                    Log.d(TAG, "♻️ 发生异常，回收图片")
                    currentBitmap.recycle()
                }
            }
            
            Log.d(TAG, "=== sendImageMessageToAI 结束 ===")
        }
    }
    
    /**
     * 确保会话ID已初始化
     */
    private suspend fun ensureSessionInitialized(): String {
        // 首先检查是否需要更新会话
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()
        
        // 更新会话信息
        withContext(Dispatchers.IO) {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }
        
        // 确保会话ID已初始化并返回
        return sessionManager.ensureSessionIdInitialized()
    }
    
    // === TextSelectionManager.TextSelectionCallbacks 实现 ===
    
    override fun onTextDetected(text: String, appPackage: String, bookName: String) {
        // 更新会话信息
        serviceScope.launch {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }
    }
    
    override fun onValidTextSelected(text: String, appPackage: String, bookName: String, 
                                   bounds: Rect?, position: Pair<Int, Int>?) {
        // 保存选择位置信息到截屏管理器
        screenshotManager.setTextSelectionBounds(bounds)
        screenshotManager.setTextSelectionPosition(position)
        
        // 如果聊天窗口已显示，将选中文本导入到输入框
        if (chatWindowManager.isVisible()) {
            chatWindowManager.importTextToInputField(text)
        }
        
        // 更新会话信息
        serviceScope.launch {
            sessionManager.updateSessionIfNeeded(appPackage, bookName)
        }
    }
    
    override fun onInvalidTextSelected(text: String) {
        // 无效文本不做处理
    }
    
    override fun onTextSelectionActive() {
        // 移动悬浮按钮到选择区域附近
        floatingButtonManager.moveToSelectionArea(screenshotManager.getTextSelectionPosition())
        
        // 更新按钮外观
        floatingButtonManager.updateAppearanceForSelection(true)
    }
    
    override fun onTextSelectionInactive() {
        // 如果按钮在边缘，恢复到原始位置
        if (floatingButtonManager.isAtEdge()) {
            floatingButtonManager.restoreToEdge()
        }
        
        // 恢复按钮外观
        floatingButtonManager.updateAppearanceForSelection(false)
    }
    
    /**
     * 从辅助功能服务请求文本时的回调
     */
    override fun onRequestTextFromAccessibilityService() {
        Log.d(TAG, "从辅助功能服务请求文本")
        
        // 发送广播请求获取选中文本
        val intent = Intent("com.readassist.REQUEST_SELECTED_TEXT")
        sendBroadcast(intent)
        
        // 也从文本选择管理器中获取当前应用和书籍信息
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()
        
        // 更新会话管理器中的应用和书籍信息
        sessionManager.setCurrentApp(appPackage)
        sessionManager.setCurrentBook(bookName)
        
        Log.d(TAG, "📱 从文本选择管理器获取应用信息: 包名=$appPackage, 书籍=$bookName")
    }
    
    // === ScreenshotManager.ScreenshotCallbacks 实现 ===
    
    override fun onScreenshotStarted() {
        // 截屏时不隐藏悬浮按钮，保持可见状态
        // floatingButtonManager.setButtonVisibility(false)
    }
    
    override fun onScreenshotSuccess(bitmap: Bitmap) {
        Log.e(TAG, "📸 [统一流程] 截屏成功，仅保存图片不弹窗。尺寸: ${bitmap.width}x${bitmap.height}")
        
        // 回收旧的图片（如果有），并保存新的图片
        pendingScreenshotBitmap?.recycle()
        pendingScreenshotBitmap = bitmap

        // 检查是否应该自动弹窗
        val autoPopup = preferenceManager.getBoolean("screenshot_auto_popup", true)
        if (!autoPopup) {
            Log.d(TAG, "🚫 用户已关闭截屏后自动弹窗功能，仅保存截图。")
            Toast.makeText(this, "截屏已保存，点击悬浮窗手动分析", Toast.LENGTH_SHORT).show()
            floatingButtonManager.restoreDefaultState()
            return
        }

        // 统一流程：恢复UI状态，但不立即弹窗
        // 弹窗将由FileObserver检测到文件后统一触发
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        
        Log.d(TAG, "✅ [统一流程] 截屏图片已保存，等待FileObserver触发弹窗")
        
        // 注意：这里故意不调用 chatWindowManager.showChatWindow()
        // 让FileObserver的processScreenshot统一处理弹窗逻辑
    }
    
    override fun onScreenshotComplete(uri: Uri) {
        Log.e(TAG, "📸 onScreenshotComplete in FloatingWindowServiceNew called with URI: $uri")
        // 确保在这里调用 processScreenshot
        processScreenshot(uri)
    }
    
    override fun onScreenshotTaken(uri: Uri) {
        Log.e(TAG, "📸 收到截屏: $uri")
        // 这里可以添加额外的处理逻辑
    }
    
    private fun processScreenshot(uri: Uri) {
        Log.e(TAG, "📸 开始处理截屏: $uri")
        
        // 使用协程进行异步处理，避免阻塞主线程
        serviceScope.launch(Dispatchers.IO) {
            try {
                // 智能重试机制：尝试解码图片，确保文件完整性
                val bitmap = decodeScreenshotWithRetry(uri, maxRetries = 3)
                
                if (bitmap == null) {
                    Log.e(TAG, "❌ 重试后仍无法解码截屏图片: $uri")
                    withContext(Dispatchers.Main) {
                        onScreenshotFailed("无法解码截屏图片")
                    }
                    return@launch
                }
                
                Log.e(TAG, "✅ 成功解码截屏图片: ${bitmap.width}x${bitmap.height}")

                withContext(Dispatchers.Main) {
                    // 在处理新截屏前，删除上一次的截屏文件
                    lastScreenshotFile?.let { file ->
                        if (file.exists()) {
                            val deleted = file.delete()
                            Log.e(TAG, "🗑️ 删除上一次截屏文件: ${file.absolutePath}, 结果: $deleted")
                        }
                    }

                    // 记录本次截屏文件
                    if (uri.scheme == "file") {
                        lastScreenshotFile = File(uri.path!!)
                    } else {
                        lastScreenshotFile = null // content uri 不处理
                    }

                    // 统一流程：通过FileObserver触发的弹窗逻辑
                    Log.e(TAG, "📢 [统一流程] FileObserver触发弹窗，开始完整处理")
                    
                    // 保存或更新截屏图片
                    pendingScreenshotBitmap?.recycle()
                    pendingScreenshotBitmap = bitmap
                    
                    // 检查是否应该自动弹窗
                    val autoPopup = preferenceManager.getBoolean("screenshot_auto_popup", true)
                    if (!autoPopup) {
                        Log.d(TAG, "🚫 用户已关闭截屏后自动弹窗功能")
                        Toast.makeText(this@FloatingWindowServiceNew, "截屏已保存，点击悬浮窗手动分析", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }

                    // 显示聊天窗口
                    chatWindowManager.showChatWindow()
                    
                    // 设置输入框提示
                    val selectedText = textSelectionManager.getLastDetectedText()
                    val hasSelectedText = selectedText.isNotEmpty() && selectedText.length > 10
                    
                    val promptText = if (hasSelectedText) {
                        "选中文本：$selectedText\n\n请分析这张截屏图片："
                    } else {
                        "请分析这张截屏图片："
                    }
                    
                    // 导入提示文本到输入框
                    chatWindowManager.importTextToInputField(promptText)
                    
                    // 自动勾选"发送截图"选项
                    chatWindowManager.setSendScreenshotChecked(true)
                    
                    // 检查剪贴板内容并更新UI
                    updateClipboardUI()
                    
                    Log.d(TAG, "✅ [统一流程] FileObserver弹窗处理完成")
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理截屏失败", e)
                withContext(Dispatchers.Main) {
                    onScreenshotFailed("截屏处理失败：${e.message}")
                }
            }
        }
    }
    
    /**
     * 智能重试解码截屏图片
     * 这是确保文件完整性的关键方法
     */
    private suspend fun decodeScreenshotWithRetry(uri: Uri, maxRetries: Int = 3): Bitmap? {
        repeat(maxRetries) { attempt ->
            try {
                Log.d(TAG, "尝试解码截屏图片 (第${attempt + 1}次): $uri")
                
                val bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                    // 检查输入流是否有效
                    if (inputStream.available() == 0) {
                        Log.w(TAG, "输入流为空，可能文件还在写入中")
                        return@use null
                    }
                    
                    // 尝试解码
                    BitmapFactory.decodeStream(inputStream)
                }
                
                if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                    Log.d(TAG, "✅ 第${attempt + 1}次尝试成功解码: ${bitmap.width}x${bitmap.height}")
                    return bitmap
                } else {
                    Log.w(TAG, "第${attempt + 1}次尝试解码失败，图片可能损坏或未完全写入")
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "第${attempt + 1}次尝试解码异常: ${e.message}")
            }
            
            // 如果不是最后一次尝试，等待一小段时间再重试
            if (attempt < maxRetries - 1) {
                Log.d(TAG, "等待100ms后重试...")
                delay(100) // 比500ms延迟更短且更有针对性
            }
        }
        
        Log.e(TAG, "❌ 经过${maxRetries}次尝试仍无法解码图片")
        return null
    }
    
    override fun onScreenshotFailed(error: String) {
        Log.e(TAG, "❌ 截屏失败: $error")
        // 处理截屏失败
        Toast.makeText(this, "截屏失败: $error", Toast.LENGTH_SHORT).show()
    }

    override fun onScreenshotCancelled() {
        Log.e(TAG, "📸 截屏已取消")
        // 恢复界面显示
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
    }

    override fun onScreenshotMessage(message: String) {
        Log.e(TAG, "📸 截屏消息: $message")
        chatWindowManager.addSystemMessage(message)
    }
    
    override fun onPermissionRequesting() {
        Log.e(TAG, "🔐 正在请求截屏权限...")
        // 显示加载消息
        chatWindowManager.showChatWindow()
        chatWindowManager.addLoadingMessage("🔐 正在请求截屏权限...")
    }

    override fun onPermissionGranted() {
        Log.e(TAG, "✅ 截屏权限已授予")
        // 隐藏加载消息
        chatWindowManager.removeLastMessage()
        
        // 恢复悬浮按钮
        floatingButtonManager.setButtonVisibility(true)
        
        // 继续执行截屏分析
        performScreenshotFirst()
    }

    override fun onPermissionDenied() {
        Log.e(TAG, "❌ 截屏权限被拒绝")
        // 隐藏加载消息
        chatWindowManager.removeLastMessage()
        
        // 恢复UI
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
    }
    
    // === ChatWindowManager.ChatWindowCallbacks 实现 ===
    
    /**
     * 聊天窗口显示回调
     */
    override fun onChatWindowShown() {
        Log.d(TAG, "聊天窗口已显示")
        // 注册勾选项监听
        chatWindowManager.setOnCheckStateChangedListener(object : ChatWindowManager.OnCheckStateChangedListener {
            override fun onCheckStateChanged() {
                updateInputHintByCheckState()
            }
        })
        serviceScope.launch {
            // 从偏好设置读取之前保存的应用和书籍信息
            val savedAppPackage = app.preferenceManager.getString("current_app_package", "com.readassist")
            val savedBookName = app.preferenceManager.getString("current_book_name", "阅读笔记")
            
            // 只有在保存的值为默认值时，才尝试从文本选择管理器获取最新值
            val currentAppPackage = if (savedAppPackage == "com.readassist") {
                textSelectionManager.getCurrentAppPackage()
            } else {
                savedAppPackage
            }
            
            val currentBookName = if (savedBookName == "阅读笔记") {
                textSelectionManager.getCurrentBookName()
            } else {
                savedBookName
            }
            
            Log.d(TAG, "📱 聊天窗口显示时的应用信息: 包名=$currentAppPackage, 书籍=$currentBookName")
            
            // 保存应用和书籍信息到偏好设置
            app.preferenceManager.setString("current_app_package", currentAppPackage)
            app.preferenceManager.setString("current_book_name", currentBookName)
            
            // 更新会话管理器中的应用和书籍信息
            sessionManager.setCurrentApp(currentAppPackage)
            sessionManager.setCurrentBook(currentBookName)
            
            // 强制更新会话状态（重要修改）
            val sessionChanged = sessionManager.updateSessionIfNeeded(currentAppPackage, currentBookName)
            
            // 无论会话是否变更，都要确保会话ID已初始化（重要修改）
            val sessionId = sessionManager.ensureSessionIdInitialized()
            Log.d(TAG, "✅ 聊天窗口显示时使用的会话ID: $sessionId")
            
            if (sessionChanged) {
                Log.d(TAG, "会话已变更，加载新会话消息")
                loadChatHistory()
            } else {
                Log.d(TAG, "会话未变更，继续使用当前会话")
            }
            
            // 更新聊天窗口标题
            chatWindowManager.updateWindowTitle()

            // === 新增：刷新勾选项内容 ===
            val screenshotTime = getLatestScreenshotTimeString()
            chatWindowManager.updateScreenshotInfo(screenshotTime)
            updateClipboardUI()
            
            // 新增：初始状态下，确保输入框内容与勾选状态一致
            // 如果有待处理的截图且未自动勾选，则勾选
            val hasPendingScreenshot = pendingScreenshotBitmap != null && !pendingScreenshotBitmap!!.isRecycled
            if (hasPendingScreenshot && !chatWindowManager.isSendScreenshotChecked()) {
                chatWindowManager.setSendScreenshotChecked(true)
            }
            
            // 新增：根据勾选项动态设置输入框hint
            updateInputHintByCheckState()
        }
    }
    
    /**
     * 加载聊天历史记录
     */
    private suspend fun loadChatHistory() {
        try {
            Log.d(TAG, "开始加载聊天历史记录")
            
            // 获取当前会话ID
            val sessionId = sessionManager.getCurrentSessionId()
            if (sessionId.isEmpty()) {
                Log.d(TAG, "会话ID为空，无法加载历史记录")
                return
            }
            
            // 从数据库加载消息
            val messagesFlow = app.chatRepository.getChatMessages(sessionId)
            val messageList = withContext(Dispatchers.IO) {
                messagesFlow.first()
            }
            
            Log.d(TAG, "加载了 ${messageList.size} 条历史消息")
            
            // 转换为聊天项
            val chatItems = messageList.map { entity ->
                listOf(
                    // 用户消息
                    ChatItem(
                        userMessage = entity.userMessage,
                        aiMessage = "",
                        isUserMessage = true,
                        isLoading = false,
                        isError = false
                    ),
                    // AI 回复
                    ChatItem(
                        userMessage = "",
                        aiMessage = entity.aiResponse,
                        isUserMessage = false,
                        isLoading = false,
                        isError = false
                    )
                )
            }.flatten()
            
            // 更新聊天窗口
            withContext(Dispatchers.Main) {
                if (chatItems.isEmpty()) {
                    // 如果没有历史记录，添加欢迎消息
                    chatWindowManager.clearChatHistory()
                } else {
                    // 更新聊天历史
                    chatWindowManager.updateChatHistory(chatItems)
                }
            }
            
            Log.d(TAG, "聊天历史记录加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "加载聊天历史失败", e)
        }
    }
    
    override fun onChatWindowHidden() {
        // 如果按钮不在边缘，移动到边缘
        if (!floatingButtonManager.isAtEdge() && !floatingButtonManager.isMoved()) {
            floatingButtonManager.restoreToEdge()
        }
    }
    
    override fun onMessageSend(message: String) {
        Log.e(TAG, "!!! ✅ FloatingWindowServiceNew.onMessageSend CALLED with message: $message")
        Log.e(TAG, "输入框内容: $message")
        // 统一调用 sendUserMessage，由它来判断是发送图片还是文本
        sendUserMessage(message)
    }
    
    override fun onNewChatButtonClick() {
        // 请求新会话
        sessionManager.requestNewSession()
        
        // 获取当前应用包名和书籍名称
        val appPackage = textSelectionManager.getCurrentAppPackage()
        val bookName = textSelectionManager.getCurrentBookName()
        
        // 立即更新会话管理器中的应用和书籍信息
        sessionManager.setCurrentApp(appPackage)
        sessionManager.setCurrentBook(bookName)
        
        // 生成新的会话ID并记录
        serviceScope.launch {
            val newSessionId = sessionManager.ensureSessionIdInitialized()
            Log.d(TAG, "🆕 创建了新会话ID: $newSessionId, 应用=$appPackage, 书籍=$bookName")
            
            // 记录分解情况以便调试
            app.chatRepository.logSessionIdParts(newSessionId)
        }
        
        // 清空聊天历史
        chatWindowManager.clearChatHistory()
    }
    
    override fun onConfigStatusClick(platform: com.readassist.model.AiPlatform?) {
        if (platform != null) {
            showApiKeyInputDialog(platform)
        } else {
            showQuickConfigurationDialog()
        }
    }
    
    override fun onShowApiKeyDialog(platform: com.readassist.model.AiPlatform) {
        showApiKeyInputDialog(platform)
    }
    
    /**
     * 实现FloatingButtonCallbacks接口的方法
     */
    override fun onFloatingButtonClick() {
        handleFloatingButtonClick()
    }

    private suspend fun processScreenshotFromUri(uri: Uri) {
        try {
            val bitmap = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
                } else {
                    @Suppress("DEPRECATION")
                    android.provider.MediaStore.Images.Media.getBitmap(contentResolver, uri)
                }
            }
            onScreenshotSuccess(bitmap)

            // Clean up the screenshot file after processing
            withContext(Dispatchers.IO) {
                try {
                    val rowsDeleted = contentResolver.delete(uri, null, null)
                    if (rowsDeleted > 0) {
                        Log.d(TAG, "Screenshot file deleted successfully: $uri")
                    } else {
                        Log.d(TAG, "Screenshot file not found for deletion: $uri")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "Failed to delete screenshot due to security exception: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete screenshot: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process screenshot from URI: $uri", e)
            onScreenshotFailed("Failed to load screenshot from URI.")
        }
    }

    /**
     * 获取所有可能的截屏目录
     */
    private fun getScreenshotDirectories(): List<String> {
        val dirs = mutableListOf(
            "/storage/emulated/0/Pictures/Screenshots",
            "/storage/emulated/0/DCIM/Screenshots",
            "/storage/emulated/0/iReader/saveImage/tmp"
        )
        
        // 添加Supernote设备的应用私有目录
        getExternalFilesDir(null)?.let { appDir ->
            dirs.add(appDir.absolutePath)
            Log.d(TAG, "🔴 添加Supernote截屏目录: ${appDir.absolutePath}")
        }
        
        return dirs
    }

    // 获取最近一张截屏图片的时间字符串（如无返回null）
    private fun getLatestScreenshotTimeString(): String? {
        val dirs = getScreenshotDirectories()
        var latestFile: File? = null
        
        for (dirPath in dirs) {
            val dir = File(dirPath)
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f -> 
                    f.isFile && f.canRead() && 
                    (f.name.endsWith(".png") || f.name.endsWith(".jpg")) &&
                    (f.name.contains("screenshot") || f.name.contains("Screenshot"))
                }
                files?.forEach { file ->
                    if (latestFile == null || file.lastModified() > latestFile!!.lastModified()) {
                        latestFile = file
                        Log.d(TAG, "🔴 找到截屏文件: ${file.absolutePath}, 修改时间: ${Date(file.lastModified())}")
                    }
                }
            }
        }
        
        return latestFile?.let {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            sdf.format(java.util.Date(it.lastModified()))
        }
    }

    // 获取当天剪贴板内容（如无返回null）
    private fun getTodayClipboardContent(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            // 判断是否为今天的内容（简单判断：内容非空且不为默认提示）
            if (text.isNotBlank()) {
                // 返回完整内容
                return text
            }
        }
        return null
    }

    // 获取用于UI显示的剪贴板内容（截断显示）
    private fun getClipboardContentForDisplay(): String? {
        val fullContent = getTodayClipboardContent()
        return if (fullContent != null && fullContent.length > 30) {
            fullContent.take(30) + "..."
        } else {
            fullContent
        }
    }

    // 更新剪贴板UI显示
    private fun updateClipboardUI() {
        val displayContent = getClipboardContentForDisplay()
        chatWindowManager.updateClipboardInfo(displayContent)
    }

    private fun updateInputHintByCheckState() {
        val sendScreenshot = chatWindowManager.isSendScreenshotChecked()
        val sendClipboard = chatWindowManager.isSendClipboardChecked()
        val hint = when {
            sendScreenshot && sendClipboard -> "请分析发给你的图片和文字内容。"
            sendScreenshot -> "请分析这张截屏图片："
            sendClipboard -> "请分析这段文字："
            else -> "请输入您的问题或内容"
        }
        
        // 使用新方法同时更新输入框内容和提示文本
        chatWindowManager.updateInputTextByCheckState(hint)
    }

    /**
     * 尝试获取最近的截图文件并加载为Bitmap
     */
    private fun getRecentScreenshot() {
        try {
            // 获取最近的截图文件
            val dirs = getScreenshotDirectories()
            var latestFile: File? = null
            
            for (dirPath in dirs) {
                val dir = File(dirPath)
                if (dir.exists() && dir.isDirectory) {
                    val files = dir.listFiles { f -> 
                        f.isFile && f.canRead() && 
                        (f.name.endsWith(".png") || f.name.endsWith(".jpg")) && 
                        (f.name.contains("screenshot") || f.name.contains("Screenshot")) &&
                        System.currentTimeMillis() - f.lastModified() < 24*60*60*1000 // 24小时内的文件
                    }
                    files?.forEach { file ->
                        if (latestFile == null || file.lastModified() > latestFile!!.lastModified()) {
                            latestFile = file
                        }
                    }
                }
            }
            
            if (latestFile != null && latestFile!!.exists()) {
                Log.e(TAG, "找到最近的截图文件: ${latestFile!!.absolutePath}, 修改时间: ${Date(latestFile!!.lastModified())}")
                try {
                    // 加载图片
                    val bitmap = BitmapFactory.decodeFile(latestFile!!.absolutePath)
                    if (bitmap != null) {
                        Log.e(TAG, "成功加载最近的截图，尺寸: ${bitmap.width}x${bitmap.height}")
                        pendingScreenshotBitmap?.recycle()
                        pendingScreenshotBitmap = bitmap
                        // 新增：自动弹出聊天窗口
                        onScreenshotSuccess(bitmap)
                    } else {
                        Log.e(TAG, "加载最近的截图失败，无法解码图片")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载最近的截图异常", e)
                }
            } else {
                Log.e(TAG, "未找到最近的截图文件")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取最近的截图异常", e)
        }
    }

    /**
     * 开始新对话
     */
    private fun startNewChat() {
        sessionManager.requestNewSession()
        chatWindowManager.clearChatHistory()
    }

    /**
     * 显示AI API Key对话框
     */
    private fun showApiKeyDialog(platform: com.readassist.model.AiPlatform) {
        chatWindowManager.showApiKeyInputDialog(platform)
    }

    /**
     * 获取勾选状态
     */
    private fun isSendScreenshotChecked(): Boolean = chatWindowManager.isSendScreenshotChecked()
    private fun isSendClipboardChecked(): Boolean = chatWindowManager.isSendClipboardChecked()

    /**
     * 实现OnScreenshotMonitoringStateChangedListener接口的方法，
     * 确保聊天窗口勾选状态与App设置同步
     */
    override fun onScreenshotMonitoringStateChanged(enabled: Boolean) {
        Log.e(TAG, "截屏监控状态变更: $enabled")
        // 保存设置到偏好
        preferenceManager.setBoolean("screenshot_auto_popup", enabled)
    }
} 