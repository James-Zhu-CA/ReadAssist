package com.readassist.service

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.*
import android.widget.*
import android.content.ClipboardManager
import android.content.ClipData
import androidx.appcompat.app.AlertDialog
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.network.ApiResult
import com.readassist.utils.PermissionUtils
import com.readassist.database.ChatSessionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.math.pow
import kotlin.math.sqrt

class FloatingWindowService : Service(), ScreenshotService.ScreenshotCallback {
    
    companion object {
        private const val TAG = "FloatingWindowService"
        private const val FLOATING_BUTTON_SIZE = 45 // dp - 从150调整为45 (30%)
        private const val CHAT_WINDOW_WIDTH_RATIO = 0.8f
        private const val CHAT_WINDOW_HEIGHT_RATIO = 0.6f
        private const val REQUEST_SCREENSHOT_PERMISSION = 2001
    }
    
    private lateinit var windowManager: WindowManager
    private lateinit var app: ReadAssistApplication
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 悬浮按钮相关
    private var floatingButton: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    
    // 聊天窗口相关
    private var chatWindow: View? = null
    private var chatWindowParams: WindowManager.LayoutParams? = null
    private var chatAdapter: ChatAdapter? = null
    private var chatListView: ListView? = null
    private var inputEditText: EditText? = null
    private var sendButton: Button? = null
    private var analyzeButton: Button? = null
    private var screenshotButton: Button? = null
    private var newChatButton: Button? = null // 新对话按钮
    
    // AI配置相关UI组件
    private var platformSpinner: Spinner? = null
    private var modelSpinner: Spinner? = null
    private var configStatusIndicator: TextView? = null
    
    // 聊天记录持久化
    private val chatHistory = mutableListOf<ChatItem>()
    
    // 截屏服务相关
    private var screenshotService: ScreenshotService? = null
    private var screenshotServiceConnection: ServiceConnection? = null
    private var isScreenshotPermissionGranted = false
    private var isRequestingPermission = false // 添加权限请求状态标志
    private var permissionDialog: android.app.AlertDialog? = null // 添加对话框引用
    private var lastPermissionRequestTime = 0L // 添加最后请求时间
    private val permissionRequestCooldown = 3000L // 3秒冷却时间
    
    // 状态变量
    private var isChatWindowVisible = false
    private var currentSessionId: String = ""
    private var lastDetectedText: String = ""
    private var currentAppPackage: String = ""
    private var currentBookName: String = ""
    private var isNewSessionRequested = false // 标记是否需要新会话
    
    // 文本选择状态
    private var isTextSelectionActive = false
    private var originalButtonX = 0
    private var originalButtonY = 0
    private var isButtonMoved = false
    
    // 悬浮按钮位置管理
    private var edgeButtonX = 0
    private var edgeButtonY = 0
    private var isButtonAtEdge = true
    
    // 文本选择位置信息（用于局部截屏）
    private var textSelectionBounds: android.graphics.Rect? = null
    private var lastSelectionPosition: Pair<Int, Int>? = null
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 广播接收器
    private val textDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TextAccessibilityService.ACTION_TEXT_DETECTED -> {
                    handleTextDetected(intent)
                }
                TextAccessibilityService.ACTION_TEXT_SELECTED -> {
                    handleTextSelected(intent)
                }
                "com.readassist.TEXT_SELECTION_ACTIVE" -> {
                    handleTextSelectionActive()
                }
                "com.readassist.TEXT_SELECTION_INACTIVE" -> {
                    handleTextSelectionInactive()
                }
            }
        }
    }
    
    // 权限状态检查广播接收器
    private val permissionRecheckReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== 权限广播接收器触发 ===")
            Log.d(TAG, "收到广播: ${intent?.action}")
            Log.d(TAG, "当前权限状态: isRequestingPermission=$isRequestingPermission, isScreenshotPermissionGranted=$isScreenshotPermissionGranted")
            
            when (intent?.action) {
                "com.readassist.RECHECK_SCREENSHOT_PERMISSION" -> {
                    Log.d(TAG, "🔄 收到权限重新检查请求")
                    recheckScreenshotPermission()
                }
                "com.readassist.SCREENSHOT_PERMISSION_GRANTED" -> {
                    Log.d(TAG, "✅ 收到截屏权限授予通知")
                    Log.d(TAG, "更新权限状态...")
                    isRequestingPermission = false
                    isScreenshotPermissionGranted = true
                    Log.d(TAG, "权限状态已更新: isRequestingPermission=$isRequestingPermission, isScreenshotPermissionGranted=$isScreenshotPermissionGranted")
                    
                    Log.d(TAG, "初始化截屏权限...")
                    initializeScreenshotPermission()
                    Log.d(TAG, "隐藏加载消息...")
                    hideLoadingMessage()
                    
                    // 权限授权成功后，继续执行用户原本想要的操作（打开聊天窗口并截屏）
                    Log.d(TAG, "权限授权成功，继续执行截屏分析...")
                    
                    // 恢复悬浮按钮
                    floatingButton?.visibility = android.view.View.VISIBLE
                    
                    // 继续执行截屏分析
                    startScreenshotAnalysis()
                    
                    Log.d(TAG, "✅ 权限授予处理完成")
                }
                "com.readassist.SCREENSHOT_PERMISSION_DENIED" -> {
                    Log.d(TAG, "❌ 收到截屏权限拒绝通知")
                    Log.d(TAG, "更新权限状态...")
                    isRequestingPermission = false
                    isScreenshotPermissionGranted = false
                    Log.d(TAG, "权限状态已更新: isRequestingPermission=$isRequestingPermission, isScreenshotPermissionGranted=$isScreenshotPermissionGranted")
                    
                    Log.d(TAG, "隐藏加载消息...")
                    hideLoadingMessage()
                    Log.d(TAG, "恢复UI...")
                    restoreUIAfterScreenshot()
                    Log.d(TAG, "显示拒绝消息...")
                    showErrorMessage("❌ 截屏权限被拒绝，截屏功能无法使用")
                    Log.d(TAG, "✅ 权限拒绝处理完成")
                }
                "com.readassist.SCREENSHOT_PERMISSION_ERROR" -> {
                    Log.d(TAG, "⚠️ 收到截屏权限错误通知")
                    Log.d(TAG, "更新权限状态...")
                    isRequestingPermission = false
                    Log.d(TAG, "权限状态已更新: isRequestingPermission=$isRequestingPermission")
                    
                    Log.d(TAG, "隐藏加载消息...")
                    hideLoadingMessage()
                    Log.d(TAG, "恢复UI...")
                    restoreUIAfterScreenshot()
                    Log.d(TAG, "显示错误消息...")
                    showErrorMessage("⚠️ 截屏权限请求失败，请稍后重试")
                    Log.d(TAG, "✅ 权限错误处理完成")
                }
            }
            Log.d(TAG, "=== 权限广播处理结束 ===")
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FloatingWindowService created")
        
        app = application as ReadAssistApplication
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // 注册广播接收器
        val filter = IntentFilter().apply {
            addAction(TextAccessibilityService.ACTION_TEXT_DETECTED)
            addAction(TextAccessibilityService.ACTION_TEXT_SELECTED)
            addAction("com.readassist.TEXT_SELECTION_ACTIVE")
            addAction("com.readassist.TEXT_SELECTION_INACTIVE")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(textDetectedReceiver, filter)
        
        // 注册权限重新检查广播接收器
        val permissionFilter = IntentFilter("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
        permissionFilter.addAction("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
        permissionFilter.addAction("com.readassist.SCREENSHOT_PERMISSION_DENIED")
        permissionFilter.addAction("com.readassist.SCREENSHOT_PERMISSION_ERROR")
        registerReceiver(permissionRecheckReceiver, permissionFilter)
        
        bindScreenshotService()
    }
    
    /**
     * 处理截屏权限启动
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "FloatingWindowService started")
        
        if (!PermissionUtils.hasOverlayPermission(this)) {
            Log.e(TAG, "No overlay permission, stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        
        createFloatingButton()
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "FloatingWindowService destroyed")
        
        // 清理对话框
        dismissPermissionDialog()
        
        // 清理视图
        removeFloatingButton()
        removeChatWindow()
        
        // 清理待发送的截屏图片
        pendingScreenshotBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        pendingScreenshotBitmap = null
        
        // 取消注册广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(textDetectedReceiver)
        try {
            unregisterReceiver(permissionRecheckReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "取消注册权限检查广播接收器失败", e)
        }
        
        // 取消协程作用域
        serviceScope.cancel()
        
        screenshotServiceConnection?.let {
            unbindService(it)
        }
    }
    
    /**
     * 创建悬浮按钮
     */
    private fun createFloatingButton() {
        if (floatingButton != null) return
        
        // 创建按钮视图
        floatingButton = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        
        // 设置透明度为50%
        floatingButton?.alpha = 0.5f
        
        // 设置按钮点击事件
        floatingButton?.setOnClickListener {
            handleFloatingButtonClick()
        }
        
        // 设置按钮拖拽功能
        setupFloatingButtonDrag()
        
        // 创建布局参数
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        floatingButtonParams = WindowManager.LayoutParams(
            dpToPx(FLOATING_BUTTON_SIZE),
            dpToPx(FLOATING_BUTTON_SIZE),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            
            val displayMetrics = resources.displayMetrics
            
            // 计算边缘位置（屏幕右侧边缘中间，一半在屏幕外）
            edgeButtonX = displayMetrics.widthPixels - dpToPx(FLOATING_BUTTON_SIZE / 2)
            edgeButtonY = displayMetrics.heightPixels / 2 - dpToPx(FLOATING_BUTTON_SIZE / 2)
            
            // 从偏好设置恢复位置
            val savedX = app.preferenceManager.getFloatingButtonX()
            val savedY = app.preferenceManager.getFloatingButtonY()
            
            if (savedX >= 0 && savedY >= 0) {
                // 有保存的位置，使用保存的位置
                x = savedX
                y = savedY
                
                // 判断保存的位置是否为边缘位置
                val isAtEdgePosition = (savedX == edgeButtonX && savedY == edgeButtonY)
                isButtonAtEdge = isAtEdgePosition
                isButtonMoved = !isAtEdgePosition
                
                Log.d(TAG, "📍 恢复保存位置: ($savedX, $savedY), 是否在边缘: $isButtonAtEdge")
            } else {
                // 没有保存位置，使用默认边缘位置
                x = edgeButtonX
                y = edgeButtonY
                isButtonAtEdge = true
                isButtonMoved = false
                
                Log.d(TAG, "📍 使用默认边缘位置: ($x, $y)")
            }
            
            // 保存原始位置（用于其他逻辑）
            originalButtonX = x
            originalButtonY = y
        }
        
        // 添加到窗口管理器
        try {
            windowManager.addView(floatingButton, floatingButtonParams)
            Log.d(TAG, "Floating button created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating button", e)
        }
    }
    
    /**
     * 设置悬浮按钮拖拽功能
     */
    private fun setupFloatingButtonDrag() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        
        floatingButton?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingButtonParams?.x ?: 0
                    initialY = floatingButtonParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                
                MotionEvent.ACTION_MOVE -> {
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    
                    floatingButtonParams?.apply {
                        x = newX
                        y = newY
                    }
                    
                    try {
                        windowManager.updateViewLayout(floatingButton, floatingButtonParams)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update floating button position", e)
                    }
                    true
                }
                
                MotionEvent.ACTION_UP -> {
                    // 计算移动距离
                    val moveDistance = Math.sqrt(
                        Math.pow((event.rawX - initialTouchX).toDouble(), 2.0) +
                        Math.pow((event.rawY - initialTouchY).toDouble(), 2.0)
                    )
                    
                    if (moveDistance < 10) {
                        // 移动距离很小，认为是点击
                        v.performClick()
                    } else {
                        // 用户进行了拖拽操作，更新按钮状态
                        Log.d(TAG, "📍 用户拖拽了按钮，更新位置状态")
                        
                        // 保存新位置到偏好设置
                        floatingButtonParams?.let { params ->
                            app.preferenceManager.setFloatingButtonPosition(params.x, params.y)
                            Log.d(TAG, "📍 保存拖拽后位置: (${params.x}, ${params.y})")
                        }
                        
                        // 更新按钮状态：不再在边缘
                        isButtonAtEdge = false
                        isButtonMoved = true
                        
                        Log.d(TAG, "📍 按钮状态已更新: isButtonAtEdge=$isButtonAtEdge, isButtonMoved=$isButtonMoved")
                    }
                    true
                }
                
                else -> false
            }
        }
    }
    
    /**
     * 处理悬浮按钮点击 - 优先截屏模式
     */
    private fun handleFloatingButtonClick() {
        Log.d(TAG, "🔘 悬浮按钮被点击")
        
        // 检查AI配置是否完成
        if (!app.preferenceManager.isCurrentConfigurationValid()) {
            Log.d(TAG, "❌ AI配置未完成，显示配置提示")
            showConfigurationRequiredDialog()
            return
        }
        
        // 检查是否已经有聊天窗口显示
        if (isChatWindowVisible) {
            Log.d(TAG, "💬 聊天窗口已显示，直接开始截屏分析")
            startScreenshotAnalysis()
            return
        }
        
        // 优先截屏模式：无论是否有选中文本，都直接触发截屏分析
        // 这样用户体验更加一致和直观
        Log.d(TAG, "📸 优先截屏模式：直接开始截屏分析")
        
        // 先尝试获取最新的选中文本（用于后续导入到输入框）
        requestSelectedTextFromAccessibilityService()
        
        // 立即开始截屏分析
        startScreenshotAnalysis()
    }
    

    
    /**
     * 开始截屏分析
     */
    private fun startScreenshotAnalysis() {
        Log.d(TAG, "📸 开始截屏分析...")
        
        // 显示截屏分析提示
        showScreenshotAnalysisIndicator()
        
        // 直接执行截屏，移除1秒延迟以提升响应速度
        performScreenshot()
    }
    
    /**
     * 显示截屏分析指示器
     */
    private fun showScreenshotAnalysisIndicator() {
        try {
            // 更新悬浮按钮外观，显示分析状态 - 保持AI图标，只改变颜色
            floatingButton?.apply {
                setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50"))) // 绿色表示分析中
                // 保持AI文字，不改为相机图标
                if (this is Button) {
                    text = "AI"
                }
                alpha = 1.0f
                scaleX = 1.1f  // 轻微放大
                scaleY = 1.1f
            }
            
            // 显示Toast提示
            Toast.makeText(this, "准备截屏分析，请稍候...", Toast.LENGTH_SHORT).show()
            
            Log.d(TAG, "📸 截屏分析指示器已显示")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示截屏分析指示器失败", e)
        }
    }
    
    /**
     * 停止截屏分析（恢复按钮状态）
     */
    private fun stopScreenshotAnalysis() {
        Log.d(TAG, "📸 截屏分析完成，恢复按钮状态")
        
        // 恢复悬浮按钮外观
        floatingButton?.apply {
            setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#2196F3"))) // 恢复蓝色
            if (this is Button) {
                text = "AI"
            }
            alpha = 0.8f
            scaleX = 1.0f
            scaleY = 1.0f
        }
    }
    
    /**
     * 切换聊天窗口显示状态
     */
    private fun toggleChatWindow() {
        if (isChatWindowVisible) {
            hideChatWindow()
        } else {
            showChatWindow()
        }
    }
    
    /**
     * 显示聊天窗口
     */
    private fun showChatWindow() {
        if (chatWindow != null) {
            hideChatWindow()
        }
        
        createChatWindow()
        isChatWindowVisible = true
        
        // 如果有待处理的文本，自动分析
        if (lastDetectedText.isNotEmpty()) {
            autoAnalyzeText()
        }
    }
    
    /**
     * 隐藏聊天窗口
     */
    private fun hideChatWindow() {
        removeChatWindow()
        isChatWindowVisible = false
    }
    
    /**
     * 创建聊天窗口
     */
    private fun createChatWindow() {
        // 创建一个包含背景遮罩的容器
        val containerView = FrameLayout(this)
        
        // 创建半透明背景遮罩，覆盖整个屏幕
        val backgroundOverlay = View(this).apply {
            setBackgroundColor(0x80000000.toInt()) // 半透明黑色
            setOnClickListener {
                // 点击背景遮罩关闭窗口
                hideChatWindow()
            }
        }
        
        // 创建聊天窗口内容
        val chatContent = LayoutInflater.from(this).inflate(R.layout.chat_window, null)
        
        // 设置聊天内容的布局参数
        val displayMetrics = resources.displayMetrics
        val chatContentParams = FrameLayout.LayoutParams(
            (displayMetrics.widthPixels * CHAT_WINDOW_WIDTH_RATIO).toInt(),
            (displayMetrics.heightPixels * CHAT_WINDOW_HEIGHT_RATIO).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        
        // 将背景遮罩和聊天内容添加到容器中
        containerView.addView(backgroundOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        containerView.addView(chatContent, chatContentParams)
        
        // 设置chatWindow为容器视图
        chatWindow = containerView
        
        // 初始化视图组件（需要从chatContent中查找）
        initializeChatViews(chatContent)
        
        // 创建窗口布局参数
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        chatWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        
        // 添加到窗口管理器
        try {
            windowManager.addView(chatWindow, chatWindowParams)
            Log.d(TAG, "Chat window created with background overlay")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create chat window", e)
        }
    }
    
    /**
     * 初始化聊天窗口视图组件
     */
    private fun initializeChatViews(contentView: View? = null) {
        val window = contentView ?: chatWindow
        window?.let { view ->
            // 初始化组件
            chatListView = view.findViewById(R.id.chatListView)
            inputEditText = view.findViewById(R.id.inputEditText)
            sendButton = view.findViewById(R.id.sendButton)
            analyzeButton = view.findViewById(R.id.analyzeButton)
            screenshotButton = view.findViewById(R.id.screenshotButton)
            newChatButton = view.findViewById(R.id.newChatButton)
            
            // 初始化AI配置UI组件
            platformSpinner = view.findViewById(R.id.platformSpinner)
            modelSpinner = view.findViewById(R.id.modelSpinner)
            configStatusIndicator = view.findViewById(R.id.configStatusIndicator)
            
            // 初始化聊天适配器
            chatAdapter = ChatAdapter(this, chatHistory)
            chatListView?.adapter = chatAdapter
            
            // 首次打开时默认新对话，不自动加载历史记录
            // 只有在明确有当前会话ID时才加载历史记录
            if (currentSessionId.isNotEmpty()) {
                Log.d(TAG, "有当前会话ID，加载历史记录: $currentSessionId")
                loadChatHistory()
            } else {
                Log.d(TAG, "首次打开，保持空白状态，等待用户操作")
                // 不自动恢复历史会话，保持空白状态
                // 用户可以通过其他方式（如按钮）来恢复历史会话
            }
            
            // 设置按钮点击事件
            sendButton?.setOnClickListener {
                sendUserMessage()
            }
            
            analyzeButton?.setOnClickListener {
                analyzeDetectedText()
            }
            
            screenshotButton?.setOnClickListener {
                requestScreenshotPermission()
            }
            
            newChatButton?.setOnClickListener {
                requestNewSession()
            }
            
            // 关闭按钮
            view.findViewById<Button>(R.id.closeButton)?.setOnClickListener {
                hideChatWindow()
            }
            
            // 最小化按钮
            view.findViewById<Button>(R.id.minimizeButton)?.setOnClickListener {
                hideChatWindow()
            }
            
            // 初始化AI配置UI
            setupAiConfigurationUI()
            
            // 如果有聊天记录，滚动到底部
            if (chatHistory.isNotEmpty()) {
                scrollToBottom()
            }
        }
    }
    
    /**
     * 从数据库加载聊天历史记录
     */
    private fun loadChatHistory() {
        Log.d(TAG, "📚📚📚 开始加载聊天历史记录")
        Log.d(TAG, "🔍 当前会话ID: $currentSessionId")
        Log.d(TAG, "🔍 当前应用: $currentAppPackage")
        Log.d(TAG, "🔍 当前书籍: $currentBookName")
        
        serviceScope.launch {
            try {
                Log.d(TAG, "📚 从数据库查询消息...")
                val messages = app.chatRepository.getChatMessages(currentSessionId)
                messages.collect { messageList ->
                    Log.d(TAG, "📚 查询到 ${messageList.size} 条消息记录")
                    messageList.forEachIndexed { index, entity ->
                        Log.d(TAG, "📚 消息 $index: 用户=${entity.userMessage.take(50)}..., AI=${entity.aiResponse.take(50)}...")
                    }
                    
                    withContext(Dispatchers.Main) {
                        try {
                            // 清空当前显示的历史记录
                            val oldSize = chatHistory.size
                            chatHistory.clear()
                            Log.d(TAG, "📚 清空了 $oldSize 条旧记录")
                            
                            // 转换数据库记录为ChatItem
                            messageList.forEach { entity ->
                                // 添加用户消息
                                chatHistory.add(ChatItem(entity.userMessage, "", true, false, false))
                                // 添加AI回复
                                chatHistory.add(ChatItem("", entity.aiResponse, false, false, false))
                            }
                            
                            Log.d(TAG, "📚 转换后聊天历史大小: ${chatHistory.size}")
                            
                            // 检查适配器状态
                            if (chatAdapter == null) {
                                Log.e(TAG, "❌ chatAdapter 为 null，无法更新UI")
                            } else {
                                Log.d(TAG, "📚 通知适配器更新...")
                                chatAdapter?.notifyDataSetChanged()
                                scrollToBottom()
                                Log.d(TAG, "📚 适配器更新完成")
                            }
                            
                            Log.d(TAG, "✅ 成功加载了${messageList.size}条历史记录，UI已更新")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ UI更新过程中出错", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 加载聊天历史失败", e)
            }
        }
    }
    
    /**
     * 处理检测到的文本
     */
    private fun handleTextDetected(intent: Intent) {
        lastDetectedText = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
        currentAppPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
        currentBookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""
        
        Log.d(TAG, "Text detected: $lastDetectedText from $currentAppPackage")
        
        // 改进会话ID生成逻辑：
        // 1. 如果是新会话请求，生成新的会话ID
        // 2. 如果当前没有会话ID（首次启动），尝试恢复最近的会话
        // 3. 如果应用或书籍发生变化，生成新的会话ID
        // 4. 否则保持当前会话ID，维持对话连续性
        
        val shouldCreateNewSession = isNewSessionRequested || 
                                   (currentSessionId.isEmpty() && !tryRestoreExistingSession()) || 
                                   hasAppOrBookChanged()
        
        if (shouldCreateNewSession) {
            if (isNewSessionRequested) {
                // 用户主动请求新会话
                currentSessionId = app.chatRepository.generateSessionId(currentAppPackage, currentBookName)
                Log.d(TAG, "用户请求新会话ID: $currentSessionId")
                
                // 清空聊天历史（UI显示）
                chatHistory.clear()
                chatAdapter?.notifyDataSetChanged()
                
                // 删除默认的系统消息，避免自动显示内容
                // addSystemMessage("✨ 开始新对话")
            } else {
                // 尝试恢复或创建会话
                serviceScope.launch {
                    try {
                        Log.d(TAG, "🔍🔍🔍 尝试查找最近的会话...")
                        Log.d(TAG, "🔍 查找条件 - 应用: $currentAppPackage, 书籍: $currentBookName")
                        
                        // 尝试查找最近的会话
                        val recentSession = findRecentSessionForApp(currentAppPackage, currentBookName)
                        if (recentSession != null) {
                            // 恢复最近的会话
                            Log.d(TAG, "✅✅✅ 找到最近会话，准备恢复")
                            Log.d(TAG, "✅ 会话ID: ${recentSession.sessionId}")
                            Log.d(TAG, "✅ 会话应用: ${recentSession.appPackage}")
                            Log.d(TAG, "✅ 会话书籍: ${recentSession.bookName}")
                            Log.d(TAG, "✅ 首次消息时间: ${recentSession.firstMessageTime}")
                            Log.d(TAG, "✅ 最后消息时间: ${recentSession.lastMessageTime}")
                            
                            currentSessionId = recentSession.sessionId
                            Log.d(TAG, "✅ 恢复最近会话: $currentSessionId")
                            
                            // 加载历史记录
                            withContext(Dispatchers.Main) {
                                Log.d(TAG, "📚 开始加载恢复会话的历史记录...")
                                loadChatHistory()
                            }
                        } else {
                            // 创建新会话
                            Log.d(TAG, "❌❌❌ 未找到匹配的会话，创建新会话")
                            currentSessionId = app.chatRepository.generateSessionId(currentAppPackage, currentBookName)
                            Log.d(TAG, "✅ 创建新会话ID: $currentSessionId")
                            
                            withContext(Dispatchers.Main) {
                                // 清空聊天历史（UI显示）
                                val oldSize = chatHistory.size
                                chatHistory.clear()
                                chatAdapter?.notifyDataSetChanged()
                                Log.d(TAG, "🆕 新会话清空了 $oldSize 条旧记录")
                                // 删除默认的系统消息，避免首次启动时显示内容
                                // addSystemMessage("📚 开始阅读 ${currentBookName.ifEmpty { "新内容" }}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复会话失败，创建新会话", e)
                        currentSessionId = app.chatRepository.generateSessionId(currentAppPackage, currentBookName)
                    }
                }
            }
            
            isNewSessionRequested = false // 重置标志
        } else {
            Log.d(TAG, "继续当前会话: $currentSessionId")
        }
        
        // 如果聊天窗口已显示且启用自动分析，立即分析
        if (isChatWindowVisible && app.preferenceManager.isAutoAnalyzeEnabled()) {
            autoAnalyzeText()
        }
    }
    
    /**
     * 检查应用或书籍是否发生变化
     */
    private fun hasAppOrBookChanged(): Boolean {
        // 从当前会话ID中提取应用和书籍信息进行比较
        if (currentSessionId.isEmpty()) return true
        
        val parts = currentSessionId.split("_")
        if (parts.size < 2) return true
        
        val sessionApp = parts[0]
        val sessionBook = parts[1]
        
        return sessionApp != currentAppPackage || sessionBook != currentBookName
    }
    
    /**
     * 尝试恢复现有会话（同步方法）
     */
    private fun tryRestoreExistingSession(): Boolean {
        return try {
            // 如果当前应用和书籍信息为空，无法恢复
            if (currentAppPackage.isEmpty()) {
                Log.d(TAG, "应用包名为空，无法恢复会话")
                return false
            }
            
            // 启动异步恢复
            serviceScope.launch {
                try {
                    val recentSession = findRecentSessionForApp(currentAppPackage, currentBookName)
                    if (recentSession != null) {
                        currentSessionId = recentSession.sessionId
                        Log.d(TAG, "成功恢复会话: $currentSessionId")
                        
                        // 如果聊天窗口已显示，加载历史记录
                        if (isChatWindowVisible) {
                            withContext(Dispatchers.Main) {
                                loadChatHistory()
                            }
                        }
                    } else {
                        Log.d(TAG, "未找到可恢复的会话")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "异步恢复会话失败", e)
                }
            }
            
            true // 表示已启动恢复流程
        } catch (e: Exception) {
            Log.e(TAG, "恢复会话失败", e)
            false
        }
    }
    
    /**
     * 查找最近的会话
     */
    private suspend fun findRecentSessionForApp(appPackage: String, bookName: String): ChatSessionEntity? {
        Log.d(TAG, "🔍🔍🔍 findRecentSessionForApp 开始")
        Log.d(TAG, "🔍 查找参数 - 应用: '$appPackage', 书籍: '$bookName'")
        
        return try {
            Log.d(TAG, "🔍 获取活跃会话列表...")
            val sessionsFlow = app.chatRepository.getActiveSessions()
            val sessionsList = sessionsFlow.first()
            
            Log.d(TAG, "🔍 找到 ${sessionsList.size} 个活跃会话")
            sessionsList.forEachIndexed { index, session ->
                Log.d(TAG, "🔍 会话 $index: ID=${session.sessionId}, 应用='${session.appPackage}', 书籍='${session.bookName}', 首次=${session.firstMessageTime}, 最后=${session.lastMessageTime}")
            }
            
            val matchedSession = sessionsList.find { session ->
                val appMatch = session.appPackage == appPackage
                val bookMatch = session.bookName == bookName
                Log.d(TAG, "🔍 匹配检查: 应用匹配=$appMatch ('${session.appPackage}' == '$appPackage'), 书籍匹配=$bookMatch ('${session.bookName}' == '$bookName')")
                appMatch && bookMatch
            }
            
            if (matchedSession != null) {
                Log.d(TAG, "✅ 找到匹配会话: ${matchedSession.sessionId}")
            } else {
                Log.d(TAG, "❌ 未找到匹配会话")
            }
            
            matchedSession
        } catch (e: Exception) {
            Log.e(TAG, "❌ 查找最近会话失败", e)
            null
        }
    }
    
    /**
     * 处理检测到的选中文本
     */
    private fun handleTextSelected(intent: Intent) {
        val selectedText = intent.getStringExtra(TextAccessibilityService.EXTRA_DETECTED_TEXT) ?: ""
        currentAppPackage = intent.getStringExtra(TextAccessibilityService.EXTRA_SOURCE_APP) ?: ""
        currentBookName = intent.getStringExtra(TextAccessibilityService.EXTRA_BOOK_NAME) ?: ""
        
        // 获取文本选择位置信息（如果有）
        val selectionX = intent.getIntExtra("SELECTION_X", -1)
        val selectionY = intent.getIntExtra("SELECTION_Y", -1)
        val selectionWidth = intent.getIntExtra("SELECTION_WIDTH", -1)
        val selectionHeight = intent.getIntExtra("SELECTION_HEIGHT", -1)
        
        if (selectionX >= 0 && selectionY >= 0 && selectionWidth > 0 && selectionHeight > 0) {
            textSelectionBounds = android.graphics.Rect(selectionX, selectionY, 
                selectionX + selectionWidth, selectionY + selectionHeight)
            lastSelectionPosition = Pair(selectionX + selectionWidth / 2, selectionY + selectionHeight / 2)
            Log.d(TAG, "📍 保存文本选择位置: $textSelectionBounds")
        }
        
        Log.d(TAG, "Text selected: ${selectedText.take(100)}... from $currentAppPackage")
        
        // 更严格的文本过滤
        val isValidSelectedText = selectedText.isNotEmpty() && 
            selectedText.length > 10 &&
            !selectedText.contains("输入问题或点击分析") &&
            !selectedText.contains("请输入") &&
            !selectedText.contains("点击") &&
            !selectedText.contains("发送") &&
            !selectedText.contains("取消") &&
            !selectedText.contains("确定") &&
            !selectedText.contains("设置") &&
            !selectedText.contains("菜单")
        
        if (isValidSelectedText) {
            // 保存有效的选中文本
            Log.d(TAG, "📝📝📝 准备保存选中文本: ${selectedText.take(100)}...")
            
            // 移除元数据检查，直接保存所有有效文本
            lastDetectedText = selectedText
            Log.d(TAG, "✅ 有效选中文本已保存: ${selectedText.take(50)}...")
            
            // 如果是截屏分析模式，说明这是通过其他方式获取的文本，直接处理
            if (isScreenshotAnalysisMode()) {
                Log.d(TAG, "📸 截屏分析模式，处理获取到的文本")
                stopScreenshotAnalysis()
                showChatWindow()
                mainHandler.postDelayed({
                    importTextToInputField(selectedText)
                }, 300)
            } else if (isChatWindowVisible) {
                // 将选中文本导入到输入框，但不自动发送
                importTextToInputField(selectedText)
            } else {
                Log.d(TAG, "📝 选中文本已保存，等待用户点击按钮")
            }
        } else {
            Log.d(TAG, "❌ 忽略无效的选中文本: $selectedText")
            Log.d(TAG, "❌ 文本长度: ${selectedText.length}")
            Log.d(TAG, "❌ 包含UI占位符: ${selectedText.contains("输入问题或点击分析")}")
        }
    }
    
    /**
     * 检查是否在截屏分析模式
     */
    private fun isScreenshotAnalysisMode(): Boolean {
        return (floatingButton as? Button)?.text == "📸"
    }
    
    /**
     * 将文本导入到输入框
     */
    private fun importTextToInputField(text: String) {
        inputEditText?.let { editText ->
            // 获取当前输入框的文本
            val currentText = editText.text?.toString() ?: ""
            
            // 如果输入框为空，直接设置文本
            if (currentText.isEmpty()) {
                editText.setText(text)
            } else {
                // 如果输入框有内容，在末尾添加选中文本
                val newText = if (currentText.endsWith(" ") || currentText.endsWith("\n")) {
                    currentText + text
                } else {
                    "$currentText\n$text"
                }
                editText.setText(newText)
            }
            
            // 将光标移到文本末尾
            editText.setSelection(editText.text?.length ?: 0)
            
            // 显示提示信息，但不自动发送
            showImportSuccessMessage(text)
            
            Log.d(TAG, "✅ 文本已导入到输入框，用户可以编辑后发送")
        }
    }
    
    /**
     * 显示导入成功提示
     */
    private fun showImportSuccessMessage(text: String) {
        val shortText = if (text.length > 30) {
            text.take(30) + "..."
        } else {
            text
        }
        
        // 添加系统消息到聊天列表
        addSystemMessage("📝 已导入选中文本：$shortText\n💡 您可以编辑后点击发送按钮")
        
        // 可选：显示Toast提示
        Toast.makeText(this, "文本已导入，可以编辑后发送", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 自动分析文本
     */
    private fun autoAnalyzeText() {
        if (lastDetectedText.isEmpty()) return
        
        // 显示分析按钮提示
        analyzeButton?.text = "分析选中文本 (${lastDetectedText.take(20)}...)"
        analyzeButton?.visibility = View.VISIBLE
    }
    
    /**
     * 分析检测到的文本
     */
    private fun analyzeDetectedText() {
        if (lastDetectedText.isEmpty()) {
            Toast.makeText(this, getString(R.string.error_no_text), Toast.LENGTH_SHORT).show()
            return
        }
        
        sendMessageToAI(lastDetectedText)
        analyzeButton?.visibility = View.GONE
        lastDetectedText = ""
    }
    
    /**
     * 发送用户消息
     */
    private fun sendUserMessage() {
        val message = inputEditText?.text?.toString()?.trim()
        if (message.isNullOrEmpty()) return
        
        inputEditText?.setText("")
        
        // 检查是否有待发送的截屏图片
        if (pendingScreenshotBitmap != null) {
            sendImageMessageToAI(message, pendingScreenshotBitmap!!)
            pendingScreenshotBitmap = null // 清除已使用的图片
        } else {
            sendMessageToAI(message)
        }
    }
    
    /**
     * 发送消息到 AI
     */
    private fun sendMessageToAI(message: String) {
        // 添加用户消息到聊天列表
        val userChatItem = ChatItem(message, "", true, false)
        chatAdapter?.addItem(userChatItem)
        
        // 添加 AI 思考中的占位符
        val loadingChatItem = ChatItem("", getString(R.string.ai_thinking), false, true)
        chatAdapter?.addItem(loadingChatItem)
        
        // 滚动到底部
        scrollToBottom()
        
        // 发送请求到 AI
        serviceScope.launch {
            try {
                val result = app.chatRepository.sendMessage(
                    sessionId = currentSessionId,
                    userMessage = message,
                    bookName = currentBookName,
                    appPackage = currentAppPackage,
                    promptTemplate = app.preferenceManager.getPromptTemplate()
                )
                
                withContext(Dispatchers.Main) {
                    // 移除加载占位符
                    chatAdapter?.removeLastItem()
                    
                    when (result) {
                        is ApiResult.Success -> {
                            val aiChatItem = ChatItem("", result.data.aiResponse, false, false)
                            chatAdapter?.addItem(aiChatItem)
                        }
                        
                        is ApiResult.Error -> {
                            val errorMessage = result.exception.message ?: getString(R.string.ai_response_error)
                            val errorChatItem = ChatItem("", errorMessage, false, false, true)
                            chatAdapter?.addItem(errorChatItem)
                        }
                        
                        is ApiResult.NetworkError -> {
                            val errorChatItem = ChatItem("", result.message, false, false, true)
                            chatAdapter?.addItem(errorChatItem)
                        }
                    }
                    
                    scrollToBottom()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    chatAdapter?.removeLastItem()
                    val errorChatItem = ChatItem("", getString(R.string.error_unknown), false, false, true)
                    chatAdapter?.addItem(errorChatItem)
                    scrollToBottom()
                }
            }
        }
    }
    
    /**
     * 发送图片消息到 AI
     */
    private fun sendImageMessageToAI(message: String, bitmap: Bitmap) {
        // 添加用户消息到聊天列表（包含图片标识）
        val userChatItem = ChatItem("$message 📸", "", true, false)
        chatAdapter?.addItem(userChatItem)
        
        // 添加 AI 思考中的占位符
        val loadingChatItem = ChatItem("", "🤖 正在分析图片和您的问题...", false, true)
        chatAdapter?.addItem(loadingChatItem)
        
        // 滚动到底部
        scrollToBottom()
        
        // 发送图片和消息到 AI
        serviceScope.launch {
            try {
                Log.d(TAG, "🚀 开始发送图片消息到AI...")
                Log.d(TAG, "📝 用户消息: $message")
                Log.d(TAG, "📐 图片尺寸: ${bitmap.width}x${bitmap.height}")
                
                // 检查API Key
                val apiKey = (application as ReadAssistApplication).preferenceManager.getApiKey()
                if (apiKey.isNullOrBlank()) {
                    Log.e(TAG, "❌ API Key未设置")
                    withContext(Dispatchers.Main) {
                        chatAdapter?.removeLastItem()
                        showErrorMessage("🔑 请先在设置中配置Gemini API Key")
                    }
                    return@launch
                }
                
                // 发送图片到Gemini分析
                val result = app.geminiRepository.sendImage(
                    bitmap = bitmap,
                    prompt = message,
                    context = getRecentChatContext()
                )
                
                Log.d(TAG, "📥 AI API调用完成，结果类型: ${result::class.simpleName}")
                
                withContext(Dispatchers.Main) {
                    // 移除加载占位符
                    chatAdapter?.removeLastItem()
                    
                    when (result) {
                        is ApiResult.Success -> {
                            Log.d(TAG, "✅ AI分析成功")
                            Log.d(TAG, "📝 响应内容长度: ${result.data.length}")
                            
                            if (result.data.isBlank()) {
                                Log.w(TAG, "⚠️ AI返回空白内容")
                                showErrorMessage("🤖 AI分析结果为空，可能是图片内容无法识别")
                            } else {
                                // 显示AI分析结果
                                val aiChatItem = ChatItem("", result.data, false, false)
                                chatAdapter?.addItem(aiChatItem)
                                
                                // 注意：不再调用saveChatMessage，避免重复发送API请求
                                // 图片消息的保存由聊天记录系统自动处理
                                
                                Log.d(TAG, "🎉 图片分析完成并显示")
                            }
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "❌ AI分析失败: ${result.exception.message}")
                            showErrorMessage("🚫 图片分析失败：${result.exception.message}")
                        }
                        is ApiResult.NetworkError -> {
                            Log.e(TAG, "🌐 网络错误: ${result.message}")
                            showErrorMessage("🌐 网络错误：${result.message}")
                        }
                    }
                    
                    scrollToBottom()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 发送图片消息失败", e)
                withContext(Dispatchers.Main) {
                    chatAdapter?.removeLastItem()
                    showErrorMessage("❌ 发送失败：${e.message}")
                    scrollToBottom()
                }
            }
        }
    }
    
    /**
     * 滚动到聊天列表底部
     */
    private fun scrollToBottom() {
        chatListView?.post {
            chatAdapter?.let { adapter ->
                if (adapter.count > 0) {
                    chatListView?.setSelection(adapter.count - 1)
                }
            }
        }
    }
    
    /**
     * 移除悬浮按钮
     */
    private fun removeFloatingButton() {
        floatingButton?.let { button ->
            try {
                windowManager.removeView(button)
                floatingButton = null
                floatingButtonParams = null
                Log.d(TAG, "Floating button removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove floating button", e)
            }
        }
    }
    
    /**
     * 移除聊天窗口
     */
    private fun removeChatWindow() {
        chatWindow?.let { window ->
            try {
                windowManager.removeView(window)
                chatWindow = null
                chatWindowParams = null
                Log.d(TAG, "Chat window removed")
                
                // 如果按钮不在边缘，移动到边缘
                if (!isButtonAtEdge) {
                    restoreButtonToOriginalPosition()
                } else {
                    // 按钮已在边缘，无需移动
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove chat window", e)
            }
        }
    }
    
    /**
     * dp 转 px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
    
    /**
     * 请求截屏权限
     */
    private fun requestScreenshotPermission() {
        if (isScreenshotPermissionGranted) {
            // 权限已获取，直接截屏
            performScreenshot()
        } else {
            // 显示引导对话框，提示用户到主应用授权
            showScreenshotPermissionGuideDialog()
        }
    }
    
    /**
     * 显示截屏权限引导对话框
     */
    private fun showScreenshotPermissionGuideDialog() {
        // 强制关闭现有对话框
        dismissPermissionDialog()
        
        // 防止重复显示对话框
        if (isRequestingPermission) {
            Log.d(TAG, "权限请求已在进行中，跳过对话框显示")
            return
        }
        
        Log.d(TAG, "显示截屏权限对话框")
        
        try {
            permissionDialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("需要重新授权截屏权限")
                .setMessage("检测到截屏权限已失效或过期。\n\n点击\"立即授权\"重新获取权限，系统将弹出授权对话框。\n\n请在弹窗中选择\"立即开始\"。")
                .setPositiveButton("立即授权") { dialog, _ ->
                    Log.d(TAG, "用户点击立即授权")
                    dialog.dismiss()
                    requestScreenshotPermissionDirectly()
                }
                .setNegativeButton("取消") { dialog, _ ->
                    Log.d(TAG, "用户取消权限请求")
                    dialog.dismiss()
                    // 取消时恢复UI
                    restoreUIAfterScreenshot()
                }
                .setOnDismissListener { dialog ->
                    Log.d(TAG, "权限对话框被dismiss")
                    permissionDialog = null
                    // 对话框消失时，如果没有在请求权限，则恢复UI
                    if (!isRequestingPermission) {
                        restoreUIAfterScreenshot()
                    }
                }
                .setCancelable(true)
                .create()
            
            permissionDialog?.window?.setType(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            
            permissionDialog?.show()
            Log.d(TAG, "权限对话框已显示")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示权限对话框失败", e)
            permissionDialog = null
            restoreUIAfterScreenshot()
        }
    }
    
    /**
     * 强制关闭权限对话框
     */
    private fun dismissPermissionDialog() {
        try {
            permissionDialog?.let { dialog ->
                if (dialog.isShowing) {
                    Log.d(TAG, "强制关闭现有权限对话框")
                    dialog.dismiss()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭权限对话框失败", e)
        } finally {
            permissionDialog = null
        }
    }
    
    /**
     * 直接请求截屏权限
     */
    private fun requestScreenshotPermissionDirectly() {
        Log.d(TAG, "=== requestScreenshotPermissionDirectly() 开始 ===")
        Log.d(TAG, "当前状态检查: isRequestingPermission=$isRequestingPermission")
        
        if (isRequestingPermission) {
            Log.w(TAG, "❌ 权限请求已在进行中，跳过")
            return
        }
        
        Log.d(TAG, "开始权限请求流程...")
        
        try {
            isRequestingPermission = true
            Log.d(TAG, "✅ 权限请求状态已设置为true")
            
            // 强制关闭对话框
            Log.d(TAG, "强制关闭权限对话框...")
            dismissPermissionDialog()
            Log.d(TAG, "权限对话框已关闭")
            
            Log.d(TAG, "显示加载消息...")
            showLoadingMessage("🔐 正在请求截屏权限...")
            Log.d(TAG, "加载消息已显示")
            
            Log.d(TAG, "创建权限请求Intent...")
            val intent = Intent(this, com.readassist.ui.ScreenshotPermissionActivity::class.java).apply {
                action = com.readassist.ui.ScreenshotPermissionActivity.ACTION_REQUEST_SCREENSHOT_PERMISSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            Log.d(TAG, "Intent创建完成: $intent")
            Log.d(TAG, "Intent flags: ${intent.flags}")
            
            Log.d(TAG, "启动权限请求Activity...")
            startActivity(intent)
            Log.d(TAG, "✅ 权限请求Activity已启动")
            
            // 设置超时保护
            Log.d(TAG, "设置30秒超时保护...")
            serviceScope.launch {
                delay(30000) // 30秒超时
                if (isRequestingPermission) {
                    Log.w(TAG, "⏰ 权限请求超时，显示紧急重置选项")
                    isRequestingPermission = false
                    hideLoadingMessage()
                    
                    // 显示紧急重置选项
                    withContext(Dispatchers.Main) {
                        showEmergencyResetDialog()
                    }
                }
            }
            Log.d(TAG, "超时保护已设置")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动截屏权限请求Activity失败", e)
            Log.e(TAG, "异常详情: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "堆栈: ${e.stackTrace.take(3).joinToString("\n") { "  at $it" }}")
            
            isRequestingPermission = false
            Log.d(TAG, "权限请求状态已重置为false")
            
            hideLoadingMessage()
            showErrorMessage("❌ 无法启动权限请求，请重试或重启应用")
            restoreUIAfterScreenshot()
        }
        Log.d(TAG, "=== requestScreenshotPermissionDirectly() 结束 ===")
    }
    
    /**
     * 显示紧急重置对话框
     */
    private fun showEmergencyResetDialog() {
        try {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("权限请求超时")
                .setMessage("检测到权限请求可能卡住了。\n\n如果系统权限对话框仍然显示，请：\n1. 按设备返回键关闭它\n2. 点击下面的\"强制重置\"清理状态\n3. 重新尝试截屏")
                .setPositiveButton("强制重置") { dialog, _ ->
                    dialog.dismiss()
                    emergencyResetPermissionState()
                }
                .setNegativeButton("稍后重试") { dialog, _ ->
                    dialog.dismiss()
                    restoreUIAfterScreenshot()
                }
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示紧急重置对话框失败", e)
            emergencyResetPermissionState()
        }
    }
    
    /**
     * 紧急重置权限状态
     */
    private fun emergencyResetPermissionState() {
        Log.w(TAG, "=== emergencyResetPermissionState() 开始 ===")
        Log.w(TAG, "🚨 执行紧急权限状态重置")
        Log.d(TAG, "重置前状态: isRequestingPermission=$isRequestingPermission, isScreenshotPermissionGranted=$isScreenshotPermissionGranted")
        
        try {
            // 重置所有状态
            Log.d(TAG, "重置权限状态标志...")
            isRequestingPermission = false
            isScreenshotPermissionGranted = false
            Log.d(TAG, "权限状态标志已重置: isRequestingPermission=$isRequestingPermission, isScreenshotPermissionGranted=$isScreenshotPermissionGranted")
            
            // 清除偏好设置中的权限数据
            Log.d(TAG, "清除偏好设置中的权限数据...")
            app.preferenceManager.clearScreenshotPermission()
            Log.d(TAG, "偏好设置权限数据已清除")
            
            // 尝试强制结束权限请求Activity
            Log.d(TAG, "尝试强制结束权限请求Activity...")
            val killIntent = Intent(this, com.readassist.ui.ScreenshotPermissionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(killIntent)
            Log.d(TAG, "强制结束Activity的Intent已发送")
            
            // 延迟后恢复UI
            Log.d(TAG, "设置1秒延迟恢复UI...")
            serviceScope.launch {
                delay(1000)
                withContext(Dispatchers.Main) {
                    Log.d(TAG, "延迟恢复UI...")
                    restoreUIAfterScreenshot()
                    showSuccessMessage("🔄 权限状态已重置，可以重新尝试截屏")
                    Log.d(TAG, "UI恢复完成")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 紧急重置失败", e)
            Log.e(TAG, "异常详情: ${e.javaClass.simpleName}: ${e.message}")
            restoreUIAfterScreenshot()
            showErrorMessage("❌ 重置失败，请重启应用")
        }
        Log.w(TAG, "=== emergencyResetPermissionState() 结束 ===")
    }
    
    /**
     * 显示成功消息
     */
    private fun showSuccessMessage(message: String) {
        val successItem = ChatItem("", message, false, false, false)
        chatAdapter?.addItem(successItem)
        scrollToBottom()
    }
    
    /**
     * 重新检查截屏权限状态（可被外部调用）
     */
    fun recheckScreenshotPermission() {
        val hasPermission = app.preferenceManager.isScreenshotPermissionGranted()
        
        if (hasPermission != isScreenshotPermissionGranted) {
            // 权限状态发生变化，重新初始化
            isScreenshotPermissionGranted = hasPermission
            Log.d(TAG, "截屏权限状态已更新: $hasPermission")
            
            if (hasPermission) {
                initializeScreenshotPermission()
            } else {
                // 权限被撤销，清理服务
                screenshotService = null
            }
        }
    }
    
    /**
     * 检查截屏服务是否可用
     */
    private fun isScreenshotServiceReady(): Boolean {
        // 如果正在请求权限，不要重复检查
        if (isRequestingPermission) {
            Log.d(TAG, "正在请求权限中，跳过检查")
            return false
        }
        
        val service = screenshotService
        if (service == null) {
            Log.w(TAG, "ScreenshotService not connected")
            return false
        }
        
        if (!isScreenshotPermissionGranted) {
            Log.w(TAG, "Screenshot permission not granted in preferences")
            return false
        }
        
        // 简化权限检查，只检查MediaProjection是否存在
        // 不再进行复杂的验证，避免误判和循环
        if (!service.isMediaProjectionValid()) {
            Log.w(TAG, "MediaProjection is invalid, requesting permission")
            // 权限实际已失效，更新状态
            isScreenshotPermissionGranted = false
            app.preferenceManager.setScreenshotPermissionGranted(false)
            return false
        }
        
        return true
    }
    
    /**
     * 执行截屏操作
     */
    private fun performScreenshot() {
        serviceScope.launch {
            try {
                showLoadingMessage("准备截屏...")
                
                // 1. 检查服务状态
                if (!isScreenshotServiceReady()) {
                    hideLoadingMessage()
                    showScreenshotPermissionGuideDialog()
                    return@launch
                }
                
                // 2. 最小化UI干扰 - 只隐藏悬浮按钮，保持聊天窗口以便快速恢复
                floatingButton?.visibility = android.view.View.GONE
                
                // 3. 最小延迟等待UI更新
                kotlinx.coroutines.delay(50) // 从100ms优化为50ms
                
                // 4. 使用快速截屏服务
                val service = screenshotService
                if (service != null) {
                    Log.d(TAG, "使用快速截屏服务")
                    service.captureScreenFast() // 使用新的快速截屏方法
                } else {
                    Log.w(TAG, "ScreenshotService不可用")
                    restoreUIAfterScreenshot()
                    hideLoadingMessage()
                    showScreenshotServiceErrorDialog()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "截屏过程异常", e)
                hideLoadingMessage()
                showErrorMessage("截屏失败：${e.message}")
                restoreUIAfterScreenshot()
            }
        }
    }
    
    /**
     * 显示截屏服务错误对话框
     */
    private fun showScreenshotServiceErrorDialog() {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("截屏服务异常")
            .setMessage("截屏服务出现问题，可能的原因：\n\n• 截屏权限已失效\n• 服务连接中断\n• 设备性能限制\n\n建议操作：\n1. 重新授予截屏权限\n2. 重启应用\n3. 检查设备内存")
            .setPositiveButton("重新授权") { _, _ ->
                // 清除权限状态，强制重新授权
                app.preferenceManager.clearScreenshotPermission()
                isScreenshotPermissionGranted = false
                
                // 引导用户到主界面授权
                val intent = Intent(this, com.readassist.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
            .setNegativeButton("稍后重试", null)
            .setCancelable(true)
            .create()
            .apply {
                window?.setType(
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        android.view.WindowManager.LayoutParams.TYPE_PHONE
                    }
                )
            }
            .show()
    }
    
    // 存储截屏图片，等待用户输入
    private var pendingScreenshotBitmap: Bitmap? = null
    
    // 实现 ScreenshotService.ScreenshotCallback
    override fun onScreenshotSuccess(bitmap: Bitmap) {
        Log.d(TAG, "📸 截屏成功，尺寸: ${bitmap.width}x${bitmap.height}")
        
        serviceScope.launch {
            try {
                // 恢复界面
                restoreUIAfterScreenshot()
                
                // 快速处理截屏图片 - 简化局部截屏逻辑
                val finalBitmap = if (textSelectionBounds != null) {
                    Log.d(TAG, "🎯 进行局部截屏")
                    cropBitmapToSelection(bitmap, textSelectionBounds!!)
                } else {
                    bitmap
                }
                
                // 保存截屏图片，等待用户输入
                pendingScreenshotBitmap = finalBitmap
                
                // 恢复按钮状态
                stopScreenshotAnalysis()
                
                // 快速设置输入框内容
                inputEditText?.let { editText ->
                    val currentText = editText.text?.toString() ?: ""
                    val hasSelectedText = lastDetectedText.isNotEmpty() && 
                                        !lastDetectedText.contains("输入问题或点击分析") &&
                                        lastDetectedText.length > 10
                    
                    val promptText = when {
                        hasSelectedText && currentText.isEmpty() -> {
                            "选中文本：${lastDetectedText}\n\n请分析这张截屏图片："
                        }
                        hasSelectedText && currentText.isNotEmpty() -> {
                            "$currentText\n\n选中文本：${lastDetectedText}\n\n请分析这张截屏图片："
                        }
                        currentText.isEmpty() -> {
                            "请分析这张截屏图片："
                        }
                        else -> {
                            "$currentText\n\n请分析这张截屏图片："
                        }
                    }
                    
                    editText.setText(promptText)
                    editText.setSelection(editText.text?.length ?: 0)
                }
                
                // 显示简洁提示消息
                addSystemMessage("📸 截屏已准备就绪，请在输入框中添加您的问题，然后点击发送")
                
                // 清除选择位置信息和文本信息
                textSelectionBounds = null
                lastSelectionPosition = null
                lastDetectedText = ""
                
                Log.d(TAG, "📸 截屏处理完成")
                
            } catch (e: Exception) {
                Log.e(TAG, "💥 处理截屏失败", e)
                showErrorMessage("❌ 截屏处理失败：${e.message}")
                stopScreenshotAnalysis()
            }
        }
    }
    
    override fun onScreenshotFailed(error: String) {
        Log.e(TAG, "截屏失败: $error")
        
        serviceScope.launch {
            // 恢复界面
            restoreUIAfterScreenshot()
            showErrorMessage("📸 截屏失败：$error")
        }
    }
    
    /**
     * 截屏后恢复UI显示
     */
    private fun restoreUIAfterScreenshot() {
        // 1. 恢复悬浮按钮显示
        floatingButton?.visibility = android.view.View.VISIBLE
        
        // 2. 截屏完成后，总是恢复到聊天窗口显示状态
        // 因为用户点击截屏分析，期望看到分析结果
        showChatWindow()
        
        Log.d(TAG, "截屏后恢复UI显示完成")
    }
    
    /**
     * 添加聊天消息到界面
     */
    private fun addChatMessage(userMessage: String, aiMessage: String, isFromUser: Boolean) {
        val chatItem = if (isFromUser) {
            ChatItem(userMessage, "", true, false, false)
        } else {
            ChatItem("", aiMessage, false, false, false)
        }
        chatAdapter?.addItem(chatItem)
        scrollToBottom()
    }
    
    /**
     * 显示加载消息
     */
    private fun showLoadingMessage(message: String) {
        val loadingItem = ChatItem("", message, false, true, false)
        chatAdapter?.addItem(loadingItem)
        scrollToBottom()
    }
    
    /**
     * 隐藏加载消息
     */
    private fun hideLoadingMessage() {
        // 移除最后一个加载消息
        chatAdapter?.let { adapter ->
            if (adapter.count > 0) {
                val lastMessage = adapter.getItem(adapter.count - 1)
                if (lastMessage.isLoading) {
                    adapter.removeLastItem()
                }
            }
        }
    }
    
    /**
     * 显示错误消息
     */
    private fun showErrorMessage(message: String) {
        val errorItem = ChatItem("", message, false, false, true)
        chatAdapter?.addItem(errorItem)
        scrollToBottom()
    }
    
    /**
     * 获取最近的聊天上下文
     */
    private fun getRecentChatContext(): List<com.readassist.repository.ChatContext> {
        val messages = mutableListOf<com.readassist.repository.ChatContext>()
        chatAdapter?.let { adapter ->
            val count = minOf(adapter.count, 6) // 最近3轮对话
            for (i in (adapter.count - count) until adapter.count) {
                val message = adapter.getItem(i)
                if (message != null && message.isUserMessage) {
                    // 找对应的AI回复
                    val nextIndex = i + 1
                    if (nextIndex < adapter.count) {
                        val aiMessage = adapter.getItem(nextIndex)
                        if (aiMessage != null && !aiMessage.isUserMessage) {
                            messages.add(com.readassist.repository.ChatContext(
                                userMessage = message.userMessage,
                                aiResponse = aiMessage.aiMessage
                            ))
                        }
                    }
                }
            }
        }
        return messages
    }
    
    /**
     * 保存聊天消息到数据库
     */
    private fun saveChatMessage(userMessage: String, aiResponse: String) {
        Log.d(TAG, "💾💾💾 开始保存聊天消息到数据库")
        Log.d(TAG, "💾 会话ID: $currentSessionId")
        Log.d(TAG, "💾 用户消息: ${userMessage.take(100)}...")
        Log.d(TAG, "💾 AI回复: ${aiResponse.take(100)}...")
        Log.d(TAG, "💾 书籍名: $currentBookName")
        Log.d(TAG, "💾 应用包名: $currentAppPackage")
        
        serviceScope.launch {
            try {
                val result = app.chatRepository.sendMessage(
                    sessionId = currentSessionId,
                    userMessage = userMessage,
                    bookName = currentBookName.ifEmpty { "未知书籍" },
                    appPackage = currentAppPackage.ifEmpty { "unknown" },
                    promptTemplate = app.preferenceManager.getPromptTemplate()
                )
                
                when (result) {
                    is com.readassist.network.ApiResult.Success -> {
                        Log.d(TAG, "✅ 聊天记录保存成功")
                        Log.d(TAG, "✅ 保存的消息ID: ${result.data}")
                    }
                    is com.readassist.network.ApiResult.Error -> {
                        Log.e(TAG, "❌ 聊天记录保存失败", result.exception)
                    }
                    is com.readassist.network.ApiResult.NetworkError -> {
                        Log.e(TAG, "❌ 网络错误：${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 保存聊天记录异常", e)
            }
        }
    }
    

    
    /**
     * 根据文本选择位置裁剪图片
     */
    private fun cropBitmapToSelection(originalBitmap: Bitmap, selectionBounds: android.graphics.Rect): Bitmap {
        return try {
            Log.d(TAG, "🎯 开始局部截屏裁剪")
            
            // 确保裁剪区域在图片范围内
            val cropX = maxOf(0, selectionBounds.left - 50) // 左边留50像素边距
            val cropY = maxOf(0, selectionBounds.top - 50)  // 上边留50像素边距
            val cropWidth = minOf(
                originalBitmap.width - cropX,
                selectionBounds.width() + 100 // 左右各留50像素边距
            )
            val cropHeight = minOf(
                originalBitmap.height - cropY,
                selectionBounds.height() + 100 // 上下各留50像素边距
            )
            
            // 确保裁剪尺寸有效
            if (cropWidth <= 0 || cropHeight <= 0) {
                Log.w(TAG, "⚠️ 裁剪尺寸无效，使用原始图片")
                return originalBitmap
            }
            
            val croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                cropX,
                cropY,
                cropWidth,
                cropHeight
            )
            
            Log.d(TAG, "✅ 局部截屏完成: ${croppedBitmap.width}x${croppedBitmap.height}")
            croppedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 局部截屏失败，使用原始图片", e)
            originalBitmap
        }
    }
    
    private fun bindScreenshotService() {
        screenshotServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? ScreenshotService.ScreenshotBinder
                screenshotService = binder?.getService()
                screenshotService?.setScreenshotCallback(this@FloatingWindowService)
                Log.d(TAG, "ScreenshotService连接成功")
                
                // 初始化截屏权限（如果需要）
                initializeScreenshotPermission()
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                screenshotService = null
                Log.d(TAG, "ScreenshotService连接断开")
            }
        }
        
        val intent = Intent(this, ScreenshotService::class.java)
        // 先启动服务
        startService(intent)
        // 然后绑定服务
        bindService(intent, screenshotServiceConnection!!, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * 初始化截屏权限
     */
    private fun initializeScreenshotPermission() {
        // 检查是否已经有保存的截屏权限
        val hasPermission = app.preferenceManager.isScreenshotPermissionGranted()
        isScreenshotPermissionGranted = hasPermission
        
        if (hasPermission) {
            Log.d(TAG, "截屏权限已存在，无需重新请求")
            
            // 如果有保存的权限数据，尝试启动截屏服务
            val resultCode = app.preferenceManager.getScreenshotResultCode()
            val resultDataUri = app.preferenceManager.getScreenshotResultDataUri()
            
            if (resultCode != -1 && resultDataUri != null) {
                try {
                    val resultData = Intent.parseUri(resultDataUri, 0)
                    val intent = Intent(this, ScreenshotService::class.java).apply {
                        action = ScreenshotService.ACTION_START_SCREENSHOT
                        putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenshotService.EXTRA_RESULT_DATA, resultData)
                    }
                    startForegroundService(intent)
                    Log.d(TAG, "截屏服务已启动")
                } catch (e: Exception) {
                    Log.e(TAG, "启动截屏服务失败", e)
                    // 清除无效的权限数据
                    app.preferenceManager.clearScreenshotPermission()
                    isScreenshotPermissionGranted = false
                }
            }
        } else {
            Log.d(TAG, "截屏权限未授予，需要用户手动授权")
        }
    }
    
    /**
     * 请求新对话
     */
    private fun requestNewSession() {
        Log.d(TAG, "用户请求新对话")
        
        // 如果当前没有对话内容，直接开始新对话
        if (chatHistory.isEmpty()) {
            startNewSession()
            return
        }
        
        // 如果有对话内容，显示确认对话框
        try {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("开始新对话")
                .setMessage("确定要开始新对话吗？\n\n当前对话将保存到历史记录中，但聊天窗口会清空。")
                .setPositiveButton("开始新对话") { dialog, _ ->
                    dialog.dismiss()
                    startNewSession()
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示新对话确认框失败", e)
            // 直接开始新对话
            startNewSession()
        }
    }
    
    /**
     * 开始新对话会话
     */
    private fun startNewSession() {
        Log.d(TAG, "开始新对话会话")
        
        // 设置新会话标志
        isNewSessionRequested = true
        
        // 立即生成新的会话ID
        currentSessionId = app.chatRepository.generateSessionId(
            currentAppPackage.ifEmpty { "unknown" }, 
            currentBookName.ifEmpty { "新对话" }
        )
        
        // 清空聊天历史（UI显示）
        chatHistory.clear()
        chatAdapter?.notifyDataSetChanged()
        
        // 删除默认的系统消息，避免自动显示内容
        // addSystemMessage("✨ 新对话已开始")
        
        Log.d(TAG, "新会话ID: $currentSessionId")
    }
    
    /**
     * 添加系统消息
     */
    private fun addSystemMessage(message: String) {
        val systemItem = ChatItem("", "💡 $message", false, false, false)
        chatHistory.add(systemItem)
        chatAdapter?.notifyDataSetChanged()
        scrollToBottom()
    }
    
    /**
     * 处理文本选择激活
     */
    private fun handleTextSelectionActive() {
        Log.d(TAG, "🎯 文本选择激活")
        isTextSelectionActive = true
        
        // 移动悬浮按钮到屏幕中心附近
        moveButtonToSelectionArea()
        
        // 改变按钮外观，提示用户可以点击
        updateButtonAppearanceForSelection(true)
    }
    
    /**
     * 处理文本选择取消
     */
    private fun handleTextSelectionInactive() {
        Log.d(TAG, "❌ 文本选择取消")
        isTextSelectionActive = false
        
        // 恢复按钮到原始位置
        restoreButtonToOriginalPosition()
        
        // 恢复按钮外观
        updateButtonAppearanceForSelection(false)
    }
    
    /**
     * 移动按钮到选择区域
     */
    private fun moveButtonToSelectionArea() {
        if (floatingButtonParams == null || floatingButton == null) return
        
        // 只有在边缘状态时才移动按钮
        if (!isButtonAtEdge) {
            Log.d(TAG, "📍 按钮不在边缘状态，跳过移动到选择区域")
            return
        }
        
        try {
            val displayMetrics = resources.displayMetrics
            
            // 尝试获取选择文本的位置信息
            val selectionPosition = getTextSelectionPosition()
            
            val newX: Int
            val newY: Int
            
            if (selectionPosition != null) {
                // 如果能获取到选择位置，在选择区域附近显示按钮
                newX = (selectionPosition.first + dpToPx(60)).coerceIn(
                    dpToPx(60), 
                    displayMetrics.widthPixels - dpToPx(60)
                )
                newY = (selectionPosition.second - dpToPx(30)).coerceIn(
                    dpToPx(60), 
                    displayMetrics.heightPixels - dpToPx(60)
                )
                Log.d(TAG, "📍 根据选择位置移动按钮: 选择位置(${selectionPosition.first}, ${selectionPosition.second}) -> 按钮位置($newX, $newY)")
            } else {
                // 如果无法获取选择位置，移动到屏幕中心偏右
                newX = (displayMetrics.widthPixels * 0.75).toInt()
                newY = (displayMetrics.heightPixels * 0.4).toInt()
                Log.d(TAG, "📍 使用默认位置移动按钮: ($newX, $newY)")
            }
            
            floatingButtonParams?.apply {
                x = newX
                y = newY
            }
            
            windowManager.updateViewLayout(floatingButton, floatingButtonParams)
            isButtonMoved = true
            isButtonAtEdge = false
            
            Log.d(TAG, "📍 按钮已移动到选择区域: ($newX, $newY)")
        } catch (e: Exception) {
            Log.e(TAG, "移动按钮失败", e)
        }
    }
    
    /**
     * 尝试获取文本选择的位置信息
     */
    private fun getTextSelectionPosition(): Pair<Int, Int>? {
        // 优先使用保存的选择位置
        if (lastSelectionPosition != null) {
            Log.d(TAG, "📍 使用保存的选择位置: $lastSelectionPosition")
            return lastSelectionPosition
        }
        
        // 这里可以尝试从辅助功能服务获取选择位置
        // 目前先返回null，使用默认位置
        // 未来可以通过广播从TextAccessibilityService获取选择区域的坐标
        return null
    }
    
    /**
     * 恢复按钮到边缘位置
     */
    private fun restoreButtonToOriginalPosition() {
        if (floatingButtonParams == null || floatingButton == null) return
        
        // 只有在按钮被移动过的情况下才恢复
        if (!isButtonMoved) {
            Log.d(TAG, "📍 按钮未被移动，无需恢复")
            return
        }
        
        try {
            floatingButtonParams?.apply {
                x = edgeButtonX
                y = edgeButtonY
            }
            
            windowManager.updateViewLayout(floatingButton, floatingButtonParams)
            isButtonMoved = false
            isButtonAtEdge = true
            
            Log.d(TAG, "📍 按钮已恢复到边缘位置: ($edgeButtonX, $edgeButtonY)")
        } catch (e: Exception) {
            Log.e(TAG, "恢复按钮位置失败", e)
        }
    }
    
    /**
     * 更新按钮外观以指示选择状态
     */
    private fun updateButtonAppearanceForSelection(isSelectionMode: Boolean) {
        floatingButton?.let { button ->
            if (isSelectionMode) {
                // 选择模式：更明显的外观
                button.alpha = 0.9f
                button.scaleX = 1.2f
                button.scaleY = 1.2f
                
                // 可以考虑改变背景色或添加动画
                button.animate()
                    .scaleX(1.1f)
                    .scaleY(1.1f)
                    .setDuration(200)
                    .start()
                
                Log.d(TAG, "🎨 按钮外观已更新为选择模式")
            } else {
                // 普通模式：恢复原始外观
                button.alpha = 0.5f
                button.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(200)
                    .start()
                
                Log.d(TAG, "🎨 按钮外观已恢复为普通模式")
            }
        }
    }
    
    /**
     * 重写切换聊天窗口方法，在选择模式下自动获取选中文本
     */
    private fun toggleChatWindowWithSelection() {
        if (isTextSelectionActive) {
            // 在选择模式下，获取选中文本并显示聊天窗口
            Log.d(TAG, "🎯 选择模式下打开聊天窗口，尝试获取选中文本")
            
            // 请求获取当前选中的文本
            requestSelectedTextFromAccessibilityService()
            
            // 显示聊天窗口
            showChatWindow()
            
            // 重置选择状态
            handleTextSelectionInactive()
        } else {
            // 普通模式下的切换
            toggleChatWindow()
        }
    }
    
    /**
     * 请求从辅助功能服务获取选中文本
     */
    private fun requestSelectedTextFromAccessibilityService() {
        Log.d(TAG, "📤 请求获取选中文本")
        Log.d(TAG, "📝 当前保存的文本: ${lastDetectedText.take(50)}...")
        
        // 优先使用已保存的选中文本（在选择模式下保存的）
        if (lastDetectedText.isNotEmpty() && 
            !lastDetectedText.contains("输入问题或点击分析") && 
            lastDetectedText.length > 10) {
            
            Log.d(TAG, "✅ 使用已保存的选中文本: ${lastDetectedText.take(50)}...")
            importTextToInputField(lastDetectedText)
            lastDetectedText = "" // 清空已使用的文本
        } else {
            Log.d(TAG, "📤 向辅助功能服务请求新的选中文本")
            Log.d(TAG, "📝 已保存文本无效: '$lastDetectedText'")
            val intent = Intent("com.readassist.REQUEST_SELECTED_TEXT")
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        }
    }
    
    // === AI配置相关方法 ===
    
    /**
     * 显示配置必需对话框
     */
    private fun showConfigurationRequiredDialog() {
        try {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("需要配置AI服务")
                .setMessage("使用AI助手前需要先配置API Key。\n\n请选择：\n• 在主应用中完成配置\n• 或在聊天窗口中快速配置")
                .setPositiveButton("打开主应用") { _, _ ->
                    val intent = Intent(this, com.readassist.ui.MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    startActivity(intent)
                }
                .setNeutralButton("快速配置") { _, _ ->
                    showChatWindow()
                    showQuickConfigurationDialog()
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示配置对话框失败", e)
        }
    }
    
    /**
     * 显示快速配置对话框
     */
    private fun showQuickConfigurationDialog() {
        try {
            val platforms = com.readassist.model.AiPlatform.values()
            val platformNames = platforms.map { it.displayName }.toTypedArray()
            
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择AI平台")
                .setItems(platformNames) { _, which ->
                    val selectedPlatform = platforms[which]
                    showApiKeyInputDialog(selectedPlatform)
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示快速配置对话框失败", e)
        }
    }
    
    /**
     * 显示API Key输入对话框
     */
    private fun showApiKeyInputDialog(platform: com.readassist.model.AiPlatform) {
        try {
            val input = android.widget.EditText(this).apply {
                hint = platform.keyHint
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            
            val message = "配置 ${platform.displayName}\n\n${platform.keyHint}\n\n申请地址：${platform.signupUrl}"
            
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("输入API Key")
                .setMessage(message)
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val apiKey = input.text.toString().trim()
                    if (apiKey.isNotEmpty()) {
                        if (apiKey.matches(platform.keyValidationPattern.toRegex())) {
                            // 保存配置
                            app.preferenceManager.setApiKey(platform, apiKey)
                            app.preferenceManager.setCurrentAiPlatform(platform)
                            
                            // 设置默认模型
                            val defaultModel = com.readassist.model.AiModel.getDefaultModelForPlatform(platform)
                            if (defaultModel != null) {
                                app.preferenceManager.setCurrentAiModel(defaultModel.id)
                            }
                            
                            app.preferenceManager.setAiSetupCompleted(true)
                            
                            // 更新UI
                            updateAiConfigurationUI()
                            
                            Toast.makeText(this, "✅ ${platform.displayName} 配置成功", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "❌ API Key 格式不正确", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            android.view.WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示API Key输入对话框失败", e)
        }
    }
    
    /**
     * 设置AI配置UI
     */
    private fun setupAiConfigurationUI() {
        try {
            // 设置平台选择器
            setupPlatformSpinner()
            
            // 设置模型选择器
            setupModelSpinner()
            
            // 更新配置状态
            updateAiConfigurationUI()
            
        } catch (e: Exception) {
            Log.e(TAG, "设置AI配置UI失败", e)
        }
    }
    
    /**
     * 设置平台选择器
     */
    private fun setupPlatformSpinner() {
        val platforms = com.readassist.model.AiPlatform.values()
        val platformNames = platforms.map { it.displayName }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, platformNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        platformSpinner?.adapter = adapter
        
        // 设置当前选择
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentIndex = platforms.indexOf(currentPlatform)
        if (currentIndex >= 0) {
            platformSpinner?.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        platformSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPlatform = platforms[position]
                if (selectedPlatform != app.preferenceManager.getCurrentAiPlatform()) {
                    app.preferenceManager.setCurrentAiPlatform(selectedPlatform)
                    
                    // 设置默认模型
                    val defaultModel = com.readassist.model.AiModel.getDefaultModelForPlatform(selectedPlatform)
                    if (defaultModel != null) {
                        app.preferenceManager.setCurrentAiModel(defaultModel.id)
                    }
                    
                    // 更新模型选择器和状态
                    setupModelSpinner()
                    updateAiConfigurationUI()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 设置模型选择器
     */
    private fun setupModelSpinner() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val availableModels = com.readassist.model.AiModel.getDefaultModels()
            .filter { it.platform == currentPlatform }
        
        val modelNames = availableModels.map { "${it.displayName}${if (!it.supportsVision) " (仅文本)" else ""}" }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        modelSpinner?.adapter = adapter
        
        // 设置当前选择
        val currentModelId = app.preferenceManager.getCurrentAiModelId()
        val currentIndex = availableModels.indexOfFirst { it.id == currentModelId }
        if (currentIndex >= 0) {
            modelSpinner?.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        modelSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = availableModels[position]
                if (selectedModel.id != app.preferenceManager.getCurrentAiModelId()) {
                    app.preferenceManager.setCurrentAiModel(selectedModel.id)
                    updateAiConfigurationUI()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 更新AI配置UI状态
     */
    private fun updateAiConfigurationUI() {
        try {
            val isValid = app.preferenceManager.isCurrentConfigurationValid()
            val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
            val hasApiKey = app.preferenceManager.hasApiKey(currentPlatform)
            val currentModel = app.preferenceManager.getCurrentAiModel()
            
            configStatusIndicator?.apply {
                when {
                    isValid -> {
                        text = "✓"
                        setTextColor(0xFF4CAF50.toInt())
                        setBackgroundColor(0xFFE8F5E8.toInt())
                        setOnClickListener(null)
                    }
                    !hasApiKey -> {
                        text = "⚠"
                        setTextColor(0xFFFF9800.toInt())
                        setBackgroundColor(0xFFFFF3E0.toInt())
                        setOnClickListener {
                            showApiKeyInputDialog(currentPlatform)
                        }
                    }
                    else -> {
                        text = "❌"
                        setTextColor(0xFFF44336.toInt())
                        setBackgroundColor(0xFFFFEBEE.toInt())
                        setOnClickListener {
                            showQuickConfigurationDialog()
                        }
                    }
                }
            }
            
            // 更新截屏按钮状态
            screenshotButton?.apply {
                if (currentModel?.supportsVision == true && isValid) {
                    isEnabled = true
                    alpha = 1.0f
                    text = "截屏分析"
                } else {
                    isEnabled = false
                    alpha = 0.5f
                    text = if (currentModel?.supportsVision != true) "不支持截屏" else "需要配置"
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "更新AI配置UI失败", e)
        }
    }

}

/**
 * 聊天项数据类
 */
data class ChatItem(
    val userMessage: String,
    val aiMessage: String,
    val isUserMessage: Boolean,
    val isLoading: Boolean = false,
    val isError: Boolean = false
)

/**
 * 聊天适配器
 */
class ChatAdapter(
    private val context: Context,
    private val items: MutableList<ChatItem>
) : BaseAdapter() {
    
    override fun getCount(): Int = items.size
    
    override fun getItem(position: Int): ChatItem = items[position]
    
    override fun getItemId(position: Int): Long = position.toLong()
    
    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.chat_item, parent, false)
        
        val item = getItem(position)
        val messageTextView = view.findViewById<TextView>(R.id.messageTextView)
        
        // 确保文字可选择和复制
        messageTextView.setTextIsSelectable(true)
        messageTextView.isFocusable = true
        messageTextView.isFocusableInTouchMode = true
        
        // 设置自定义的文本选择动作模式回调，添加复制功能
        messageTextView.customSelectionActionModeCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                // 添加复制选项
                menu?.add(0, android.R.id.copy, 0, "复制")?.setIcon(android.R.drawable.ic_menu_save)
                return true
            }
            
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                // 移除默认的选择全部等选项，只保留复制
                menu?.clear()
                menu?.add(0, android.R.id.copy, 0, "复制")
                return true
            }
            
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
                return when (item?.itemId) {
                    android.R.id.copy -> {
                        // 获取选中的文本
                        val start = messageTextView.selectionStart
                        val end = messageTextView.selectionEnd
                        val selectedText = messageTextView.text.substring(start, end)
                        
                        // 复制到剪贴板
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("聊天内容", selectedText)
                        clipboard.setPrimaryClip(clip)
                        
                        // 显示提示
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        
                        mode?.finish()
                        true
                    }
                    else -> false
                }
            }
            
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                // 清理工作
            }
        }
        
        when {
            item.isUserMessage -> {
                messageTextView.text = item.userMessage
                messageTextView.setBackgroundColor(0xFFF5F5F5.toInt()) // 浅灰背景
                view.findViewById<View>(R.id.messageContainer)?.apply {
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                        leftMargin = context.resources.getDimensionPixelSize(R.dimen.chat_user_margin_start)
                        rightMargin = context.resources.getDimensionPixelSize(R.dimen.chat_margin_end)
                    }
                }
            }
            
            item.isLoading -> {
                messageTextView.text = "⏳ ${item.aiMessage}"
                messageTextView.setBackgroundColor(0xFFFFFFFF.toInt()) // 白色背景
            }
            
            item.isError -> {
                messageTextView.text = "❌ ${item.aiMessage}"
                messageTextView.setTextColor(0xFF666666.toInt()) // 灰色文字表示错误
                messageTextView.setBackgroundColor(0xFFFFFFFF.toInt())
            }
            
            else -> {
                messageTextView.text = item.aiMessage
                messageTextView.setBackgroundColor(0xFFFFFFFF.toInt()) // 白色背景
                messageTextView.setTextColor(0xFF000000.toInt()) // 黑色文字
            }
        }
        
        return view
    }
    
    fun addItem(item: ChatItem) {
        items.add(item)
        notifyDataSetChanged()
    }
    
    fun removeLastItem() {
        if (items.isNotEmpty()) {
            items.removeAt(items.size - 1)
            notifyDataSetChanged()
        }
    }
} 