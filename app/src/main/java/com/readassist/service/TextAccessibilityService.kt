package com.readassist.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.readassist.utils.PreferenceManager

class TextAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "TextAccessibilityService"
        const val ACTION_TEXT_DETECTED = "com.readassist.TEXT_DETECTED"
        const val ACTION_TEXT_SELECTED = "com.readassist.TEXT_SELECTED"
        const val EXTRA_DETECTED_TEXT = "detected_text"
        const val EXTRA_SOURCE_APP = "source_app"
        const val EXTRA_BOOK_NAME = "book_name"
        const val EXTRA_IS_SELECTION = "is_selection"
        
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
    }
    
    private lateinit var preferenceManager: PreferenceManager
    private lateinit var clipboardManager: ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var lastClipboardText: String = ""
    private var lastProcessedText: String = ""
    private var currentAppPackage: String = ""
    private var currentBookName: String = ""
    
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
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "🚀 TextAccessibilityService onCreate() 开始")
        
        preferenceManager = PreferenceManager(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        
        // 监听剪贴板变化
        setupClipboardListener()
        
        // 注册文本请求广播接收器
        val requestFilter = IntentFilter("com.readassist.REQUEST_SELECTED_TEXT")
        LocalBroadcastManager.getInstance(this).registerReceiver(textRequestReceiver, requestFilter)
        
        Log.i(TAG, "✅ TextAccessibilityService onCreate() 完成")
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "🔗 Accessibility service connected - 服务已连接")
        
        // 更新偏好设置中的辅助功能状态
        preferenceManager.setAccessibilityEnabled(true)
        
        // 启动悬浮窗服务
        startFloatingWindowService()
        
        Log.i(TAG, "✅ onServiceConnected() 完成，开始监听事件")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "TextAccessibilityService destroyed")
        
        // 更新偏好设置
        preferenceManager.setAccessibilityEnabled(false)
        
        // 注销广播接收器
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(textRequestReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering text request receiver", e)
        }
        
        // 停止悬浮窗服务
        stopFloatingWindowService()
    }
    
    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            Log.d(TAG, "❌ 收到空的辅助功能事件")
            return
        }
        
        val eventPackageName = event.packageName?.toString() ?: return
        
        // 对 Supernote 应用进行特殊日志记录
        val isSupernoteApp = eventPackageName.contains("supernote") || eventPackageName.contains("ratta")
        val logLevel = if (isSupernoteApp) "🔴🔴🔴" else "🎯"
        
        Log.d(TAG, "$logLevel 收到辅助功能事件: ${getEventTypeName(event.eventType)} from $eventPackageName")

        // 只处理支持的应用
        if (!SUPPORTED_PACKAGES.contains(eventPackageName)) {
            if (isSupernoteApp) {
                Log.w(TAG, "🔴🔴🔴 Supernote相关应用未被识别为支持的应用: $eventPackageName")
                
                // 对于疑似 Supernote 应用，尝试强制处理
                Log.d(TAG, "🔴 尝试强制处理 Supernote 应用: $eventPackageName")
                updateCurrentAppInfo(eventPackageName, event)
            } else {
                // Log.d(TAG, "⚠️ 跳过不支持的应用: $eventPackageName") // 减少不必要的日志
            }
            return
        }
        
        // 更新当前应用信息（主要为了获取包名，书名提取可以按需保留或移除）
        updateCurrentAppInfo(eventPackageName, event)
        
        // 核心修改：只对特定类型的事件做最基础的处理，主要依赖剪贴板
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isSupernoteApp) {
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
            // 移除大部分其他事件类型的主动文本提取和广播
            else -> {
                if (isSupernoteApp) {
                    Log.d(TAG, "🔴 Supernote其他事件: ${getEventTypeName(event.eventType)}")
                } else {
                    Log.d(TAG, "🔍 其他事件 (仅记录，不主动提取文本): ${getEventTypeName(event.eventType)} from $eventPackageName")
                }
            }
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
     * 设置剪贴板监听器
     */
    private fun setupClipboardListener() {
        clipboardManager.addPrimaryClipChangedListener {
            mainHandler.post {
                handleClipboardChange()
            }
        }
    }
    
    /**
     * 处理剪贴板变化
     */
    private fun handleClipboardChange() {
        Log.d(TAG, "🔔 剪贴板变化事件触发")
        try {
            val clipData = clipboardManager.primaryClip
            Log.d(TAG, "📋 剪贴板数据: ${clipData != null}, 项目数: ${clipData?.itemCount ?: 0}")
            
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()
                Log.d(TAG, "📋📋📋 剪贴板文本: ${text?.take(100) ?: "null"}")
                Log.d(TAG, "📋 上次文本: ${lastClipboardText.take(50)}")
                Log.d(TAG, "📋 当前应用: $currentAppPackage")
                
                // 特殊检查：如果包含目标文本，立即处理
                if (!text.isNullOrBlank() && (
                    text.contains("They need emotional bonds too", ignoreCase = true) ||
                    text.contains("emotional bonds", ignoreCase = true) ||
                    text.contains("bonds too", ignoreCase = true))) {
                    Log.d(TAG, "🚨🚨🚨 剪贴板发现目标文本!")
                    Log.d(TAG, "🚨🚨🚨 目标文本完整内容: $text")
                    
                    lastClipboardText = text
                    lastProcessedText = text
                    
                    // 立即作为选中文本处理
                    Log.d(TAG, "🎯🎯🎯 立即处理目标文本")
                    notifyTextSelected(text, false, currentAppPackage, currentBookName)
                    return
                }
                
                // Clipboard change handling
                if (text != null && text.isNotEmpty()) {
                    // Check for clipboard changes
                    if (text != lastClipboardText) {
                        lastClipboardText = text
                        
                        Log.d(TAG, "📋 检测到剪贴板变化: ${text.take(50)}...")
                        
                        // 避免处理ReadAssist自己的剪贴板内容
                        if (currentAppPackage != "com.readassist" && currentAppPackage != "com.readassist.debug") {
                            lastProcessedText = text // 避免重复处理
                            // Update the call to use the new parameters
                            notifyTextSelected(text, false, currentAppPackage, currentBookName)
                        } else {
                            Log.d(TAG, "🚫 忽略来自ReadAssist应用的剪贴板变化")
                        }
                    }
                } else {
                    Log.d(TAG, "📋 剪贴板文本为空")
                }
            } else {
                Log.d(TAG, "❌ 剪贴板数据为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling clipboard change", e)
        }
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
            val intent = Intent(this, FloatingWindowServiceNew::class.java)
            startService(intent)
            Log.d(TAG, "FloatingWindowServiceNew started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FloatingWindowServiceNew", e)
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
                val pdfFilePaths = texts.filter { 
                    it.contains("/storage/") && 
                    (it.contains(".pdf", true) || 
                     it.contains(".mark", true) || 
                     it.contains(".epub", true))
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
} 