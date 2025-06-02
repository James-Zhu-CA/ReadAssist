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
import android.graphics.Rect
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.readassist.ReadAssistApplication
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
import java.util.concurrent.atomic.AtomicReference

/**
 * 重构后的悬浮窗服务
 */
class FloatingWindowServiceNew : Service(), 
    ScreenshotManager.ScreenshotCallbacks, 
    TextSelectionManager.TextSelectionCallbacks,
    ChatWindowManager.ChatWindowCallbacks,
    FloatingButtonManager.FloatingButtonCallbacks {
    
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
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingWindowService created")
        
        // 初始化核心组件
        app = application as ReadAssistApplication
        
        // 初始化各个管理器
        initializeManagers()
        
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
    }
    
    /**
     * 初始化管理器
     */
    private fun initializeManagers() {
        // 初始化会话管理器
        sessionManager = SessionManager(app.chatRepository)
        
        // 初始化AI通信管理器
        aiCommunicationManager = AiCommunicationManager(
            app.chatRepository,
            app.geminiRepository,
            app.preferenceManager
        )
        
        // 初始化AI配置管理器
        aiConfigurationManager = AiConfigurationManager(
            this,
            app.preferenceManager
        )
        
        // 初始化文本选择管理器
        textSelectionManager = TextSelectionManager().apply {
            setCallbacks(this@FloatingWindowServiceNew)
        }
        
        // 初始化悬浮按钮管理器
        floatingButtonManager = FloatingButtonManager(
            this,
            getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            app.preferenceManager,
            this
        )
        
        // 初始化聊天窗口管理器
        chatWindowManager = ChatWindowManager(
            this,
            getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager,
            app.preferenceManager,
            serviceScope,
            this
        )
        
        // 初始化截屏管理器
        screenshotManager = ScreenshotManager(
            this,
            app.preferenceManager,
            serviceScope,
            this
        )
        
        // 创建悬浮按钮
        floatingButtonManager.createButton()
        
        // 初始化截屏服务
        screenshotManager.initialize()
    }
    
    /**
     * 注册广播接收器
     */
    private fun registerReceivers() {
        // 注册文本检测广播接收器
        val filter = IntentFilter().apply {
            addAction(TextAccessibilityService.ACTION_TEXT_DETECTED)
            addAction(TextAccessibilityService.ACTION_TEXT_SELECTED)
            addAction("com.readassist.TEXT_SELECTION_ACTIVE")
            addAction("com.readassist.TEXT_SELECTION_INACTIVE")
            addAction("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
            addAction("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_DENIED")
            addAction("com.readassist.SCREENSHOT_PERMISSION_ERROR")
        }
        registerReceiver(textDetectedReceiver, filter)
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
        Log.d(TAG, "FloatingWindowService started")
        
        if (!com.readassist.utils.PermissionUtils.hasOverlayPermission(this)) {
            Log.e(TAG, "No overlay permission, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingWindowService destroyed")
        
        // 清理各个管理器
        floatingButtonManager.removeButton()
        chatWindowManager.hideChatWindow()
        screenshotManager.cleanup()
        
        // 取消注册广播接收器
        try {
            unregisterReceiver(textDetectedReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "取消注册广播接收器失败", e)
        }
        
        // 取消协程作用域
        serviceScope.cancel()
    }
    
    /**
     * 处理悬浮按钮点击
     */
    private fun handleFloatingButtonClick() {
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
        
        // 执行截屏，不显示聊天窗口
        Log.d(TAG, " 优先截屏模式：直接开始截屏分析")
        performScreenshotFirst()
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
     * 先执行截屏，再显示聊天窗口
     */
    private fun performScreenshotFirst() {
        Log.d(TAG, "📸 先执行截屏...")
        
        // 执行截屏前不显示聊天窗口
        floatingButtonManager.showScreenshotAnalysisState()
        
        // 使用协程避免阻塞UI线程
        serviceScope.launch {
            try {
                // 预先准备权限状态(如果需要)，尝试在后台初始化
                if (!screenshotManager.isScreenshotServiceReady()) {
                    screenshotManager.recheckScreenshotPermission()
                }
                
                // 执行截屏
                withContext(Dispatchers.Main) {
                    screenshotManager.performScreenshot()
                }
            } catch (e: Exception) {
                Log.e(TAG, "截屏处理异常", e)
                withContext(Dispatchers.Main) {
                    chatWindowManager.showChatWindow()
                    chatWindowManager.addSystemMessage("❌ 截屏处理异常: ${e.message}")
                    floatingButtonManager.restoreDefaultState()
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
        // 检查是否有待发送的截屏图片
        val pendingScreenshot = screenshotManager.getPendingScreenshot()
        
        if (pendingScreenshot != null) {
            Log.d(TAG, "✅ 找到待处理截图，尺寸: ${pendingScreenshot.width}x${pendingScreenshot.height}")
            Log.d(TAG, "📝 待发送消息: $message")
            
            try {
                // 将消息发送给AI前先检查图片是否有效
                if (pendingScreenshot.isRecycled) {
                    Log.e(TAG, "❌ 待发送的截图已被回收")
                    chatWindowManager.addSystemMessage("❌ 错误：图片已被回收，请重新截屏")
                    return
                }
                
                Log.d(TAG, "🔄 创建图片副本前，原图状态: isRecycled=${pendingScreenshot.isRecycled}")
                
                // 创建一个临时副本以确保安全
                val bitmapCopy = pendingScreenshot.copy(pendingScreenshot.config ?: Bitmap.Config.ARGB_8888, true)
                if (bitmapCopy == null) {
                    Log.e(TAG, "❌ 无法创建图片副本")
                    chatWindowManager.addSystemMessage("❌ 错误：无法处理图片，请重新截屏")
                    return
                }
                
                Log.d(TAG, "✅ 创建图片副本成功，尺寸: ${bitmapCopy.width}x${bitmapCopy.height}")
                
                // 发送图片消息，使用副本
                Log.d(TAG, "🚀 发送图片消息 (使用副本)")
                sendImageMessageToAI(message, bitmapCopy)
                
                // 清除原始截图 - 但不回收副本，因为sendImageMessageToAI是异步的
                Log.d(TAG, "♻️ 清除原始截图")
                screenshotManager.clearPendingScreenshot()
                
                // 不在这里回收副本，因为sendImageMessageToAI是异步的
                // 副本将由AiCommunicationManager负责回收
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 处理截图时出错", e)
                chatWindowManager.addSystemMessage("❌ 错误：图片处理失败，请重新截屏")
                // 确保释放资源
                Log.d(TAG, "♻️ 出错后清除原始截图")
                screenshotManager.clearPendingScreenshot()
            }
        } else {
            Log.d(TAG, "🔤 无截图，作为纯文本消息发送")
            sendTextMessageToAI(message)
        }
        Log.d(TAG, "=== sendUserMessage 结束 ===")
    }
    
    /**
     * 发送文本消息到AI
     */
    private fun sendTextMessageToAI(message: String) {
        serviceScope.launch {
            try {
                // 确保会话ID已初始化
                val sessionId = ensureSessionInitialized()
                
                // 获取应用和书籍信息
                val appPackage = sessionManager.getSanitizedAppPackage()
                val bookName = sessionManager.getSanitizedBookName()
                
                // 添加用户消息到UI
                chatWindowManager.addUserMessage(message)
                
                // 添加加载消息
                chatWindowManager.addLoadingMessage("AI思考中...")
                
                // 发送消息到AI
                val result = aiCommunicationManager.sendTextMessage(
                    sessionId = sessionId,
                    message = message,
                    appPackage = appPackage,
                    bookName = bookName
                )
                
                // 移除加载消息
                chatWindowManager.removeLastMessage()
                
                // 处理结果
                when (result) {
                    is com.readassist.network.ApiResult.Success -> {
                        chatWindowManager.addAiMessage(result.data)
                    }
                    is com.readassist.network.ApiResult.Error -> {
                        val errorMessage = result.exception.message ?: "未知错误"
                        chatWindowManager.addAiMessage("发送消息时发生错误: $errorMessage", true)
                    }
                    is com.readassist.network.ApiResult.NetworkError -> {
                        chatWindowManager.addAiMessage("网络错误: ${result.message}", true)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送消息异常", e)
                chatWindowManager.removeLastMessage()
                chatWindowManager.addAiMessage("发送消息时发生异常: ${e.message}", true)
            }
        }
    }
    
    /**
     * 发送图片消息到AI
     */
    private fun sendImageMessageToAI(message: String, bitmap: Bitmap) {
        Log.d(TAG, "=== sendImageMessageToAI 开始 ===")
        Log.d(TAG, "📤 发送图片消息，尺寸: ${bitmap.width}x${bitmap.height}")
        
        // 创建一个标记以跟踪是否已回收图片
        val bitmapRef = AtomicReference(bitmap)
        
        serviceScope.launch {
            try {
                // 检查bitmap是否已回收
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ 图片已被回收，无法发送")
                    chatWindowManager.addSystemMessage("❌ 错误：图片已被回收，请重新截屏")
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
        // 如果聊天窗口已显示且启用自动分析，立即更新分析按钮
        if (chatWindowManager.isVisible()) {
            chatWindowManager.updateAnalyzeButton(text)
        }
        
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
        // 隐藏悬浮按钮
        floatingButtonManager.setButtonVisibility(false)
    }
    
    override fun onScreenshotSuccess(bitmap: Bitmap) {
        Log.d(TAG, "📸 截屏成功")
        
        // 恢复界面显示
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        
        // 截屏成功后再显示聊天窗口
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
        
        // 显示提示消息
        chatWindowManager.addSystemMessage("📸 截屏已准备就绪，请在输入框中添加您的问题，然后点击发送")
        
        // 清除选择文本信息
        textSelectionManager.clearSelectedText()
    }
    
    override fun onScreenshotFailed(error: String) {
        Log.e(TAG, "截屏失败: $error")
        
        // 恢复界面显示
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        
        // 显示错误消息
        chatWindowManager.showChatWindow()
        chatWindowManager.addAiMessage("📸 截屏失败：$error", true)
    }
    
    override fun onScreenshotCancelled() {
        // 恢复界面显示
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
    }
    
    override fun onPermissionRequesting() {
        // 显示加载消息
        chatWindowManager.showChatWindow()
        chatWindowManager.addLoadingMessage("🔐 正在请求截屏权限...")
    }
    
    override fun onPermissionGranted() {
        // 隐藏加载消息
        chatWindowManager.removeLastMessage()
        
        // 恢复悬浮按钮
        floatingButtonManager.setButtonVisibility(true)
        
        // 继续执行截屏分析
        performScreenshotFirst()
    }
    
    override fun onPermissionDenied() {
        // 隐藏加载消息
        chatWindowManager.removeLastMessage()
        
        // 恢复UI
        floatingButtonManager.setButtonVisibility(true)
        floatingButtonManager.restoreDefaultState()
        
        // 显示拒绝消息
        chatWindowManager.showChatWindow()
        chatWindowManager.addAiMessage("❌ 截屏权限被拒绝，截屏功能无法使用", true)
    }
    
    override fun onScreenshotMessage(message: String) {
        chatWindowManager.addSystemMessage(message)
    }
    
    // === ChatWindowManager.ChatWindowCallbacks 实现 ===
    
    /**
     * 聊天窗口显示回调
     */
    override fun onChatWindowShown() {
        Log.d(TAG, "聊天窗口已显示")
        
        // 检查当前会话ID
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
                    chatWindowManager.addSystemMessage("欢迎使用ReadAssist AI阅读助手")
                } else {
                    // 更新聊天历史
                    chatWindowManager.updateChatHistory(chatItems)
                }
            }
            
            Log.d(TAG, "聊天历史记录加载完成")
        } catch (e: Exception) {
            Log.e(TAG, "加载聊天历史失败", e)
            withContext(Dispatchers.Main) {
                chatWindowManager.addSystemMessage("❌ 加载历史记录失败: ${e.message}")
            }
        }
    }
    
    override fun onChatWindowHidden() {
        // 如果按钮不在边缘，移动到边缘
        if (!floatingButtonManager.isAtEdge() && !floatingButtonManager.isMoved()) {
            floatingButtonManager.restoreToEdge()
        }
    }
    
    override fun onMessageSend(message: String) {
        sendUserMessage(message)
    }
    
    override fun onAnalyzeButtonClick() {
        val text = textSelectionManager.getLastDetectedText()
        if (text.isNotEmpty()) {
            sendTextMessageToAI(text)
            textSelectionManager.clearSelectedText()
            chatWindowManager.updateAnalyzeButton(null)
        }
    }
    
    override fun onScreenshotButtonClick() {
        screenshotManager.requestScreenshotPermission()
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
        
        // 添加欢迎消息
        chatWindowManager.addSystemMessage("✨ 新对话已开始")
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
} 