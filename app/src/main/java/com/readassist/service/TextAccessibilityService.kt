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
        
        // 记录所有事件（用于调试）
        Log.d(TAG, "🎯 收到辅助功能事件: ${getEventTypeName(event.eventType)} from ${event.packageName}")
        
        // 特别关注 Supernote 事件，显示更多信息
        if (event.packageName?.toString() == "com.ratta.supernote.launcher" || 
            event.packageName?.toString() == "com.supernote.document") {
            
            // 检查事件是否包含目标文本
            val eventText = event.text?.joinToString(" ") ?: ""
            val containsTargetText = eventText.contains("They need emotional bonds too", ignoreCase = true) ||
                eventText.contains("emotional bonds", ignoreCase = true) ||
                eventText.contains("bonds too", ignoreCase = true)
            
            if (containsTargetText) {
                Log.d(TAG, "🚨🚨🚨 事件包含目标文本: ${getEventTypeName(event.eventType)}")
                Log.d(TAG, "🚨🚨🚨 目标文本内容: $eventText")
            }
            
            // 检查是否是可能的文本选择相关事件
            val isTextSelectionRelated = isTextSelectionRelatedEvent(event)
            
            if (isTextSelectionRelated) {
                Log.d(TAG, "🎯🎯🎯 可能的文本选择事件: ${getEventTypeName(event.eventType)}")
            } else {
                Log.d(TAG, "🔥 Supernote 事件: ${getEventTypeName(event.eventType)}, 类名=${event.className}")
            }
            
            // 详细输出所有事件属性
            Log.d(TAG, "🔥 Supernote 原始事件详情:")
            Log.d(TAG, "  📋 事件类型: ${event.eventType} (${getEventTypeName(event.eventType)})")
            Log.d(TAG, "  📋 包名: ${event.packageName}")
            Log.d(TAG, "  📋 类名: ${event.className}")
            Log.d(TAG, "  📋 文本列表: ${event.text}")
            Log.d(TAG, "  📋 内容描述: ${event.contentDescription}")
            Log.d(TAG, "  📋 fromIndex: ${event.fromIndex}")
            Log.d(TAG, "  📋 toIndex: ${event.toIndex}")
            Log.d(TAG, "  📋 itemCount: ${event.itemCount}")
            Log.d(TAG, "  📋 addedCount: ${event.addedCount}")
            Log.d(TAG, "  📋 removedCount: ${event.removedCount}")
            Log.d(TAG, "  📋 beforeText: ${event.beforeText}")
            Log.d(TAG, "  📋 currentItemIndex: ${event.currentItemIndex}")
            Log.d(TAG, "  📋 maxScrollX: ${event.maxScrollX}")
            Log.d(TAG, "  📋 maxScrollY: ${event.maxScrollY}")
            Log.d(TAG, "  📋 scrollX: ${event.scrollX}")
            Log.d(TAG, "  📋 scrollY: ${event.scrollY}")
            
            // 如果有源节点，输出源节点信息
            val sourceNode = event.source
            if (sourceNode != null) {
                try {
                    val sourceNodeText = sourceNode.text?.toString() ?: ""
                    val sourceContainsTarget = sourceNodeText.contains("They need emotional bonds too", ignoreCase = true) ||
                        sourceNodeText.contains("emotional bonds", ignoreCase = true) ||
                        sourceNodeText.contains("bonds too", ignoreCase = true)
                    
                    if (sourceContainsTarget) {
                        Log.d(TAG, "🚨🚨🚨 源节点包含目标文本!")
                        Log.d(TAG, "🚨🚨🚨 源节点目标文本: $sourceNodeText")
                    }
                    
                    Log.d(TAG, "🔥 Supernote 源节点信息:")
                    Log.d(TAG, "  📋 源节点类名: ${sourceNode.className}")
                    Log.d(TAG, "  📋 源节点文本: ${sourceNode.text}")
                    Log.d(TAG, "  📋 源节点描述: ${sourceNode.contentDescription}")
                    Log.d(TAG, "  📋 源节点选中状态: ${sourceNode.isSelected}")
                    Log.d(TAG, "  📋 源节点焦点状态: ${sourceNode.isFocused}")
                    Log.d(TAG, "  📋 源节点可访问焦点: ${sourceNode.isAccessibilityFocused}")
                    Log.d(TAG, "  📋 源节点可点击: ${sourceNode.isClickable}")
                    Log.d(TAG, "  📋 源节点可长按: ${sourceNode.isLongClickable}")
                    Log.d(TAG, "  📋 源节点子节点数: ${sourceNode.childCount}")
                    
                    // 如果是文本选择事件，输出更多详情
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                        Log.d(TAG, "🎯🎯🎯 文本选择事件特殊信息:")
                        Log.d(TAG, "  📋 选择开始: ${event.fromIndex}")
                        Log.d(TAG, "  📋 选择结束: ${event.toIndex}")
                        Log.d(TAG, "  📋 源节点全文: ${sourceNode.text}")
                        
                        // 尝试提取选中部分
                        val fullText = sourceNode.text?.toString()
                        if (!fullText.isNullOrBlank() && event.fromIndex >= 0 && event.toIndex > event.fromIndex) {
                            try {
                                val selectedPart = fullText.substring(event.fromIndex, event.toIndex)
                                Log.d(TAG, "  🎯 提取的选中文本: $selectedPart")
                            } catch (e: Exception) {
                                Log.d(TAG, "  ❌ 提取选中文本失败: ${e.message}")
                            }
                        }
                    }
                    
                    // 如果是可能的文本选择相关事件，深度分析源节点
                    if (isTextSelectionRelated) {
                        analyzeNodeForTextSelection(sourceNode)
                    }
                } finally {
                    sourceNode.recycle()
                }
            } else {
                Log.d(TAG, "🔥 Supernote 源节点为空")
            }
            
            // 如果事件包含文本，详细记录
            if (!event.text.isNullOrEmpty()) {
                event.text.forEachIndexed { index, text ->
                    if (!text.isNullOrBlank()) {
                        Log.d(TAG, "🔥 Supernote 事件文本$index: ${text}")
                    }
                }
            }
        }
        
        try {
            handleAccessibilityEvent(event)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling accessibility event", e)
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
     * 处理辅助功能事件
     */
    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        
        // 记录事件详细信息
        Log.d(TAG, "📋 事件详情: 包名=$packageName, 类名=${event.className}, 文本=${event.text}")
        
        // 只处理支持的应用
        if (!SUPPORTED_PACKAGES.contains(packageName)) {
            Log.d(TAG, "⚠️ 跳过不支持的应用: $packageName")
            return
        }
        
        Log.d(TAG, "✅ 处理支持的应用事件: $packageName")
        
        // 更新当前应用信息
        updateCurrentAppInfo(packageName, event)
        
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                Log.d(TAG, "Window state changed: $packageName")
                extractBookNameFromWindow(event)
            }
            
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 过滤掉明显的元数据事件
                if (isMetadataEvent(event)) {
                    Log.d(TAG, "🚫 过滤元数据事件: ${event.text}")
                    return
                }
                
                // 内容变化时，可能有新的文本可供提取
                if (preferenceManager.isAutoAnalyzeEnabled()) {
                    extractTextFromEvent(event)
                }
                
                // 特殊处理：Supernote 的文本选择可能通过内容变化事件体现
                if (packageName == "com.ratta.supernote.launcher" || packageName == "com.supernote.document") {
                    handleSupernoteContentChange(event)
                    // 额外检查是否有文本选择弹窗出现
                    checkForSupernoteTextSelectionPopup(event)
                }
            }
            
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // 用户点击时，可能选中了文本
                extractTextFromEvent(event)
            }
            
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 文本变化（如用户输入）
                handleTextChanged(event)
            }
            
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // 文本选择变化事件 - 用户选择文本时触发
                Log.d(TAG, "🎯🎯🎯 文本选择变化事件")
                handleTextSelectionChanged(event)
            }
            
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                // 视图选择事件 - 某些应用可能使用这个事件
                Log.d(TAG, "视图选择事件: ${event.packageName}")
                handleViewSelected(event)
            }
            
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                // 长按事件 - 可能触发文本选择
                Log.d(TAG, "长按事件: ${event.packageName}")
                handleLongClick(event)
            }
            
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // 焦点事件 - 某些情况下可能包含选中文本
                Log.d(TAG, "焦点事件: ${event.packageName}")
                handleViewFocused(event)
            }
            
            else -> {
                // 记录其他未处理的事件
                Log.d(TAG, "🔍 其他事件: ${getEventTypeName(event.eventType)} - ${event.text}")
                // 尝试从任何事件中提取文本
                handleGenericEvent(event)
            }
        }
    }
    
    /**
     * 更新当前应用信息
     */
    private fun updateCurrentAppInfo(packageName: String, event: AccessibilityEvent) {
        currentAppPackage = packageName
        
        // 尝试从窗口标题提取书名
        val windowTitle = event.className?.toString() ?: ""
        if (windowTitle.isNotEmpty() && currentBookName.isEmpty()) {
            currentBookName = extractBookNameFromTitle(windowTitle)
        }
    }
    
    /**
     * 从事件中提取文本
     */
    private fun extractTextFromEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        try {
            val extractedText = extractTextFromNode(rootNode)
            if (extractedText.isNotEmpty() && extractedText != lastProcessedText) {
                lastProcessedText = extractedText
                notifyTextDetected(extractedText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from event", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 从节点树中提取文本
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo): String {
        val textBuilder = StringBuilder()
        
        // 获取当前节点的文本
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank() && isValidText(nodeText)) {
            textBuilder.append(nodeText).append(" ")
        }
        
        // 递归遍历子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val childText = extractTextFromNode(childNode)
                    textBuilder.append(childText)
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return textBuilder.toString().trim()
    }
    
    /**
     * 验证文本是否有效
     */
    private fun isValidText(text: String): Boolean {
        if (text.length < 3) return false
        
        // 特殊监控：如果包含目标文本，立即标记为有效并记录
        if (text.contains("They need emotional bonds too", ignoreCase = true) ||
            text.contains("emotional bonds", ignoreCase = true) ||
            text.contains("bonds too", ignoreCase = true)) {
            Log.d(TAG, "🎯🎯🎯 发现目标文本片段: ${text.take(100)}...")
            return true
        }
        
        // 过滤UI占位符和无效内容
        val invalidPatterns = listOf(
            "^https?://.*", // URL
            "^\\d+$", // 纯数字
            "^[\\s\\p{Punct}]+$", // 只有标点符号和空格
            "^[a-zA-Z]{1,3}$" // 单独的短英文单词
        )
        
        // 过滤UI占位符文本
        val uiPlaceholders = listOf(
            "输入问题或点击分析",
            "请输入",
            "点击分析",
            "发送",
            "取消",
            "确定",
            "设置",
            "菜单",
            "返回",
            "关闭"
        )
        
        // 检查是否包含UI占位符
        if (uiPlaceholders.any { placeholder -> text.contains(placeholder) }) {
            return false
        }
        
        return invalidPatterns.none { pattern ->
            text.matches(pattern.toRegex())
        }
    }
    
    /**
     * 处理文本变化事件
     */
    private fun handleTextChanged(event: AccessibilityEvent) {
        val text = event.text?.firstOrNull()?.toString()
        if (!text.isNullOrBlank() && isValidText(text)) {
            // 记录文本变化，但不立即处理
            Log.d(TAG, "Text changed: $text")
        }
    }
    
    /**
     * 处理文本选择变化事件
     */
    private fun handleTextSelectionChanged(event: AccessibilityEvent) {
        Log.d(TAG, "=== 文本选择事件触发 ===")
        Log.d(TAG, "事件包名: ${event.packageName}")
        Log.d(TAG, "事件类名: ${event.className}")
        Log.d(TAG, "事件文本: ${event.text}")
        Log.d(TAG, "fromIndex: ${event.fromIndex}, toIndex: ${event.toIndex}")
        
        try {
            // 获取选中的文本
            val selectedText = extractSelectedText(event)
            Log.d(TAG, "提取的选中文本: $selectedText")
            
            if (!selectedText.isNullOrBlank() && isValidText(selectedText)) {
                Log.d(TAG, "✅ 文本选择检测成功: ${selectedText.take(50)}...")
                
                // 更新最后处理的文本
                lastProcessedText = selectedText
                
                // 通知检测到选中文本，并标记为选择事件
                notifyTextSelected(selectedText)
            } else {
                Log.d(TAG, "❌ 选中文本无效或为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文本选择时出错", e)
        }
        Log.d(TAG, "=== 文本选择事件处理结束 ===")
    }
    
    /**
     * 处理视图选择事件（备用方法）
     */
    private fun handleViewSelected(event: AccessibilityEvent) {
        Log.d(TAG, "=== 视图选择事件触发 ===")
        Log.d(TAG, "事件包名: ${event.packageName}")
        Log.d(TAG, "事件类名: ${event.className}")
        Log.d(TAG, "事件文本: ${event.text}")
        
        try {
            val eventText = event.text?.joinToString(" ") { it.toString() }
            if (!eventText.isNullOrBlank() && isValidText(eventText)) {
                Log.d(TAG, "✅ 视图选择检测到文本: ${eventText.take(50)}...")
                
                // 更新最后处理的文本
                lastProcessedText = eventText
                
                // 通知检测到选中文本
                notifyTextSelected(eventText)
            } else {
                Log.d(TAG, "❌ 视图选择文本无效或为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理视图选择时出错", e)
        }
        Log.d(TAG, "=== 视图选择事件处理结束 ===")
    }
    
    /**
     * 处理长按事件
     */
    private fun handleLongClick(event: AccessibilityEvent) {
        Log.d(TAG, "=== 长按事件触发 ===")
        Log.d(TAG, "事件包名: ${event.packageName}")
        Log.d(TAG, "事件类名: ${event.className}")
        Log.d(TAG, "事件文本: ${event.text}")
        
        // 长按后延迟检查是否有文本选择
        mainHandler.postDelayed({
            checkForTextSelection()
        }, 500) // 延迟500ms检查
        
        Log.d(TAG, "=== 长按事件处理结束 ===")
    }
    
    /**
     * 处理焦点事件
     */
    private fun handleViewFocused(event: AccessibilityEvent) {
        // 只记录，不做特殊处理，避免日志过多
        if (event.text?.isNotEmpty() == true) {
            Log.d(TAG, "焦点事件包含文本: ${event.text}")
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
                
                // 避免重复处理相同文本
                if (selectedText != lastProcessedText) {
                    lastProcessedText = selectedText
                    notifyTextSelected(selectedText)
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
        // 移除常见的应用后缀
        val cleanTitle = title
            .replace(" - Adobe Acrobat Reader", "")
            .replace(" - WPS Office", "")
            .replace(" - Supernote", "")
            .replace(" - SuperNote", "")
            .replace("SuperNote Launcher", "")
            .replace("com.ratta.supernote.launcher", "")
            .replace(".pdf", "")
            .replace(".epub", "")
            .replace(".txt", "")
            .replace(".doc", "")
            .replace(".docx", "")
            .trim()
        
        return if (cleanTitle.length > 50) {
            cleanTitle.take(50) + "..."
        } else {
            cleanTitle.ifEmpty { "Supernote阅读器" }
        }
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
                    notifyTextSelected(text)
                    return
                }
                
                if (!text.isNullOrBlank() && text != lastClipboardText) {
                    // 检查是否包含元数据
                    if (text.contains("Homo deus") || (text.contains("/") && text.contains("--"))) {
                        Log.e(TAG, "🚨🚨🚨 剪贴板中检测到元数据！")
                        Log.e(TAG, "🚨 完整内容: $text")
                        Log.e(TAG, "🚨 当前应用: $currentAppPackage")
                        Log.e(TAG, "🚨 调用栈:")
                        Thread.currentThread().stackTrace.take(8).forEach { element ->
                            Log.e(TAG, "🚨   at ${element.className}.${element.methodName}(${element.fileName}:${element.lineNumber})")
                        }
                    }
                    
                    // 首先检查是否是书籍元数据（从剪贴板层面过滤）
                    val isBookMeta = isBookMetadata(text)
                    
                    if (isBookMeta) {
                        Log.d(TAG, "🚫🚫🚫 过滤剪贴板元数据: ${text.take(100)}...")
                        Log.d(TAG, "🚫 元数据详情: 长度=${text.length}")
                        return
                    }
                    
                    // 检查是否是有效的内容文本
                    val isValidContent = isValidText(text) && isContentText(text)
                    
                    Log.d(TAG, "📋 文本验证: 有效=$isValidContent, 元数据=$isBookMeta, 长度=${text.length}")
                    
                    if (isValidContent) {
                        lastClipboardText = text
                        lastProcessedText = text
                        
                        Log.d(TAG, "📋📋📋 有效剪贴板文本: ${text.take(50)}...")
                        
                        // 如果是来自 Supernote，优先作为文本选择处理
                        if (currentAppPackage == "com.ratta.supernote.launcher" || currentAppPackage == "com.supernote.document") {
                            Log.d(TAG, "🎯🎯🎯 Supernote 剪贴板选中文本")
                            notifyTextSelected(text)
                        } else {
                            Log.d(TAG, "📝 其他应用剪贴板文本")
                            notifyTextDetected(text)
                        }
                    } else {
                        Log.d(TAG, "❌ 剪贴板文本无效: 长度=${text.length}, 内容=${text.take(30)}...")
                    }
                } else {
                    Log.d(TAG, "❌ 剪贴板文本为空或重复")
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
        // 🚫 直接过滤掉所有从extractTextFromNode获取的文本
        Log.d(TAG, "🚫 过滤来自extractTextFromNode的文本，不发送给AI")
        return
        
        // 移除下面的isBookMetadata检查，因为永远不会执行
    }
    
    /**
     * 通知检测到选中文本
     */
    private fun notifyTextSelected(text: String) {
        Log.d(TAG, "📝📝📝 notifyTextSelected 被调用")
        Log.d(TAG, "🔍 选中文本内容: ${text.take(200)}...")
        Log.d(TAG, "📊 选中文本长度: ${text.length}")
        
        // 移除元数据检测，直接发送所有选中文本
        val intent = Intent(ACTION_TEXT_SELECTED).apply {
            putExtra(EXTRA_DETECTED_TEXT, text)
            putExtra(EXTRA_SOURCE_APP, currentAppPackage)
            putExtra(EXTRA_BOOK_NAME, currentBookName)
            putExtra(EXTRA_IS_SELECTION, true)
            
            // 尝试获取文本选择位置信息
            val selectionBounds = getTextSelectionBounds()
            if (selectionBounds != null) {
                putExtra("SELECTION_X", selectionBounds.left)
                putExtra("SELECTION_Y", selectionBounds.top)
                putExtra("SELECTION_WIDTH", selectionBounds.width())
                putExtra("SELECTION_HEIGHT", selectionBounds.height())
                Log.d(TAG, "📍 发送文本选择位置: $selectionBounds")
            } else {
                Log.d(TAG, "📍 未能获取文本选择位置")
            }
        }
        
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "Text selection detected and broadcast sent")
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindowService() {
        try {
            val intent = Intent(this, FloatingWindowService::class.java)
            startService(intent)
            Log.d(TAG, "FloatingWindowService started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FloatingWindowService", e)
        }
    }
    
    /**
     * 停止悬浮窗服务
     */
    private fun stopFloatingWindowService() {
        try {
            val intent = Intent(this, FloatingWindowService::class.java)
            stopService(intent)
            Log.d(TAG, "FloatingWindowService stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingWindowService", e)
        }
    }
    
    /**
     * 手动获取当前页面文本（供外部调用）
     */
    fun getCurrentPageText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        
        return try {
            extractTextFromNode(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current page text", e)
            ""
        } finally {
            rootNode.recycle()
        }
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
        try {
            val eventText = event.text?.joinToString(" ") { it.toString() }
            if (!eventText.isNullOrBlank() && isValidText(eventText) && eventText.length > 10) {
                Log.d(TAG, "🎯 通用事件检测到长文本: ${eventText.take(50)}...")
                
                // 避免重复处理相同文本
                if (eventText != lastProcessedText) {
                    lastProcessedText = eventText
                    notifyTextSelected(eventText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理通用事件时出错", e)
        }
    }
    
    /**
     * 处理 Supernote 特有的内容变化事件
     */
    private fun handleSupernoteContentChange(event: AccessibilityEvent) {
        Log.d(TAG, "🔍 Supernote 内容变化检测...")
        
        // 检查是否是选择取消事件
        val className = event.className?.toString() ?: ""
        val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
        
        // 如果是空的内容变化，可能是取消选择
        if (eventText.isEmpty() && (className.contains("RelativeLayout") || className.contains("LinearLayout"))) {
            Log.d(TAG, "🔍 检测到可能的选择取消事件")
            // 延迟检查选择状态
            mainHandler.postDelayed({
                detectTextSelectionState()
            }, 200)
        }
        
        // 延迟检查，因为 Supernote 的文本选择可能需要时间
        mainHandler.postDelayed({
            checkSupernoteTextSelection()
        }, 300) // 300ms 延迟
        
        // 同时检查是否有文本选择状态变化
        checkSupernoteSelectionState(event)
    }
    
    /**
     * 检查 Supernote 选择状态变化
     */
    private fun checkSupernoteSelectionState(event: AccessibilityEvent) {
        val className = event.className?.toString() ?: ""
        val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
        
        // 检查是否进入或退出文本选择模式
        if (className.contains("Document") || className.contains("ImageView")) {
            // 延迟检查选择状态，确保UI更新完成
            mainHandler.postDelayed({
                detectTextSelectionState()
            }, 100)
        }
    }
    
    /**
     * 检测文本选择状态
     */
    private fun detectTextSelectionState() {
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 查找是否有文本选择相关的UI元素
            val hasSelection = findTextSelectionIndicators(rootNode)
            
            if (hasSelection) {
                Log.d(TAG, "🎯 检测到文本选择状态")
                notifyTextSelectionActive()
            } else {
                Log.d(TAG, "❌ 未检测到文本选择状态，可能已取消选择")
                notifyTextSelectionInactive()
            }
        } catch (e: Exception) {
            Log.e(TAG, "检测文本选择状态时出错", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 查找文本选择指示器
     */
    private fun findTextSelectionIndicators(node: AccessibilityNodeInfo): Boolean {
        // 检查当前节点是否有选择状态
        if (node.isSelected || node.isAccessibilityFocused) {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && nodeText.length > 10) {
                Log.d(TAG, "🎯 找到选中节点: ${nodeText.take(30)}...")
                return true
            }
        }
        
        // 检查节点描述
        val contentDescription = node.contentDescription?.toString()
        if (!contentDescription.isNullOrBlank()) {
            if (contentDescription.contains("选中") || 
                contentDescription.contains("selected") ||
                contentDescription.contains("highlight")) {
                Log.d(TAG, "🎯 找到选择描述: $contentDescription")
                return true
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    if (findTextSelectionIndicators(childNode)) {
                        return true
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return false
    }
    
    /**
     * 通知文本选择激活
     */
    private fun notifyTextSelectionActive() {
        val intent = Intent("com.readassist.TEXT_SELECTION_ACTIVE")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📍 通知文本选择激活")
    }
    
    /**
     * 通知文本选择取消
     */
    private fun notifyTextSelectionInactive() {
        val intent = Intent("com.readassist.TEXT_SELECTION_INACTIVE")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📍 通知文本选择取消")
    }
    
    /**
     * 检查 Supernote 的文本选择状态
     */
    private fun checkSupernoteTextSelection() {
        Log.d(TAG, "🔍 检查 Supernote 文本选择状态...")
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 方法1: 查找可能的文本选择UI元素
            val selectedText = findSupernoteSelectedText(rootNode)
            if (!selectedText.isNullOrBlank() && isValidText(selectedText)) {
                Log.d(TAG, "✅ Supernote 检测到选中文本: ${selectedText.take(50)}...")
                
                // 避免重复处理相同文本
                if (selectedText != lastProcessedText) {
                    lastProcessedText = selectedText
                    notifyTextSelected(selectedText)
                }
            } else {
                Log.d(TAG, "❌ Supernote 未检测到有效选中文本")
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查 Supernote 文本选择时出错", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 在 Supernote UI 中查找选中的文本
     */
    private fun findSupernoteSelectedText(node: AccessibilityNodeInfo): String? {
        // 检查当前节点
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            // 检查节点是否有选中状态的指示
            if (node.isSelected || node.isFocused || node.isAccessibilityFocused) {
                Log.d(TAG, "🎯 找到可能的选中节点: $nodeText")
                if (isValidText(nodeText) && nodeText.length > 5) {
                    return nodeText.trim()
                }
            }
            
            // 检查节点描述中是否包含选中文本的线索
            val contentDescription = node.contentDescription?.toString()
            if (!contentDescription.isNullOrBlank() && (contentDescription.contains("选中") || contentDescription.contains("selected"))) {
                Log.d(TAG, "🎯 找到选中描述: $contentDescription")
                if (isValidText(nodeText) && nodeText.length > 5) {
                    return nodeText.trim()
                }
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val selectedText = findSupernoteSelectedText(childNode)
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
     * 检查 Supernote 文本选择弹窗
     */
    private fun checkForSupernoteTextSelectionPopup(event: AccessibilityEvent) {
        // 检查是否是文本选择相关的UI变化
        val className = event.className?.toString() ?: ""
        val eventText = event.text?.joinToString(" ") { it.toString() } ?: ""
        
        Log.d(TAG, "🔍 检查 Supernote 弹窗: 类名=$className, 文本=$eventText")
        
        // 特别检查 FrameLayout 和 LinearLayout - 这些通常是选择弹窗
        val isSelectionPopup = className.contains("FrameLayout") || 
            className.contains("LinearLayout") ||
            className.contains("PopupWindow") || 
            className.contains("Dialog") ||
            className.contains("SelectionMenu") ||
            className.contains("ActionMode")
        
        // 检查是否有文本选择相关的关键词
        val hasSelectionKeywords = eventText.contains("复制") || 
            eventText.contains("分享") || 
            eventText.contains("高亮") ||
            eventText.contains("笔记") ||
            eventText.contains("Copy") ||
            eventText.contains("Share") ||
            eventText.contains("Highlight")
        
        if (isSelectionPopup || hasSelectionKeywords) {
            Log.d(TAG, "🎯 检测到可能的文本选择弹窗: $className")
            
            // 立即通知文本选择激活
            notifyTextSelectionActive()
            
            // 延迟检查，确保弹窗完全显示
            mainHandler.postDelayed({
                extractTextFromSupernoteSelection()
            }, 200)
        }
        
        // 额外检查：监听所有内容变化，寻找选择状态的变化
        checkForSelectionStateChange(event)
    }
    
    /**
     * 从 Supernote 选择中提取文本
     */
    private fun extractTextFromSupernoteSelection() {
        Log.d(TAG, "🔍 尝试从 Supernote 选择中提取文本...")
        
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 方法1: 查找所有可能包含选中文本的节点
            val selectedText = findAllPossibleSelectedText(rootNode)
            
            if (!selectedText.isNullOrBlank() && isValidText(selectedText) && selectedText.length > 10) {
                Log.d(TAG, "✅ 成功提取 Supernote 选中文本: ${selectedText.take(50)}...")
                
                // 避免重复处理相同文本
                if (selectedText != lastProcessedText) {
                    lastProcessedText = selectedText
                    notifyTextSelected(selectedText)
                }
            } else {
                Log.d(TAG, "❌ 未能提取到有效的选中文本")
                
                // 备用方案：尝试获取当前页面的所有文本，让用户手动选择
                val pageText = extractTextFromNode(rootNode)
                if (pageText.isNotEmpty() && pageText.length > 50) {
                    Log.d(TAG, "💡 备用方案：提取页面文本供用户选择")
                    // 这里可以考虑显示一个对话框让用户确认要分析的文本
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提取 Supernote 选中文本时出错", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 查找所有可能的选中文本
     */
    private fun findAllPossibleSelectedText(node: AccessibilityNodeInfo): String? {
        Log.d(TAG, "🔍🔍🔍 开始查找选中文本...")
        
        // 只在必要时进行深度UI树分析（避免日志过多）
        // analyzeUITreeStructure(node, 0)
        
        // 首先尝试查找真正选中的文本（有选中状态的节点）
        val selectedText = findActualSelectedText(node)
        if (selectedText != null) {
            Log.d(TAG, "🎯 找到真正选中的文本: ${selectedText.take(50)}...")
            return selectedText
        }
        
        // 如果没有找到选中状态的文本，查找内容区域的文本
        val contentText = findContentAreaText(node)
        if (contentText != null) {
            Log.d(TAG, "🎯 找到内容区域文本: ${contentText.take(50)}...")
            return contentText
        }
        
        // 最后的备用方案：查找所有可能的文本
        val possibleTexts = mutableListOf<String>()
        findTextNodes(node, possibleTexts)
        
        // 显示所有原始文本候选
        Log.d(TAG, "📋 原始文本候选: ${possibleTexts.size}个")
        possibleTexts.forEachIndexed { index, text ->
            Log.d(TAG, "原始候选$index (长度${text.length}): ${text.take(100)}...")
        }
        
        // 过滤掉UI占位符和元数据
        val filteredTexts = possibleTexts.filter { text ->
            val isValidLength = text.length > 20 // 提高最小长度要求
            val isValidTextContent = isValidText(text)
            val isNotUIPlaceholder = !text.contains("输入问题或点击分析") &&
                !text.contains("请输入") &&
                !text.contains("点击") &&
                !text.contains("按钮") &&
                !text.contains("菜单") &&
                !text.contains("设置")
            // 移除元数据检查：val isNotBookMetadata = !isBookMetadata(text)
            val isContentTextType = isContentText(text)
            
            Log.d(TAG, "🔍 文本过滤检查: ${text.take(30)}...")
            Log.d(TAG, "  长度OK=$isValidLength, 有效文本=$isValidTextContent, 非UI=$isNotUIPlaceholder")
            Log.d(TAG, "  是内容=$isContentTextType")
            
            // 移除 isNotBookMetadata 条件
            isValidLength && isValidTextContent && isNotUIPlaceholder && isContentTextType
        }
        
        Log.d(TAG, "🔍 过滤后的文本候选: ${filteredTexts.size}个")
        filteredTexts.forEachIndexed { index, text ->
            Log.d(TAG, "过滤候选$index (长度${text.length}): ${text.take(100)}...")
        }
        
        // 查找最可能是正文内容的文本
        for (text in filteredTexts) {
            if (text.length in 50..1000) { // 调整长度范围，更符合正文段落
                // 检查是否是连续的句子或段落
                if (text.contains("。") || text.contains(".") || text.contains("，") || text.contains(",")) {
                    Log.d(TAG, "🎯 找到可能的正文内容: ${text.take(50)}...")
                    return text.trim()
                }
            }
        }
        
        // 如果没有找到理想的文本，返回最长的有效文本
        return filteredTexts
            .filter { it.length > 30 } // 确保不是短标题
            .maxByOrNull { it.length }
            ?.trim()
    }
    
    /**
     * 深度分析UI树结构（专门用于调试Supernote）
     */
    private fun analyzeUITreeStructure(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val nodeText = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        
        Log.d(TAG, "🌳 ${indent}节点[深度$depth]:")
        Log.d(TAG, "🌳 ${indent}  类名: $className")
        Log.d(TAG, "🌳 ${indent}  文本: ${if (nodeText.isNotEmpty()) nodeText.take(50) + "..." else "无"}")
        Log.d(TAG, "🌳 ${indent}  描述: ${if (contentDesc.isNotEmpty()) contentDesc else "无"}")
        Log.d(TAG, "🌳 ${indent}  选中: ${node.isSelected}")
        Log.d(TAG, "🌳 ${indent}  焦点: ${node.isFocused}")
        Log.d(TAG, "🌳 ${indent}  可访问焦点: ${node.isAccessibilityFocused}")
        Log.d(TAG, "🌳 ${indent}  可点击: ${node.isClickable}")
        Log.d(TAG, "🌳 ${indent}  可长按: ${node.isLongClickable}")
        Log.d(TAG, "🌳 ${indent}  子节点数: ${node.childCount}")
        
        // 如果节点有特殊状态或包含文本，特别标记
        if (node.isSelected || node.isAccessibilityFocused || nodeText.length > 20) {
            Log.d(TAG, "🌳 ${indent}  ⭐ 特殊节点: 选中=${node.isSelected}, 焦点=${node.isAccessibilityFocused}, 文本长度=${nodeText.length}")
        }
        
        // 递归分析子节点（限制深度避免日志过多）
        if (depth < 5) {
            for (i in 0 until node.childCount) {
                val childNode = node.getChild(i)
                if (childNode != null) {
                    try {
                        analyzeUITreeStructure(childNode, depth + 1)
                    } finally {
                        childNode.recycle()
                    }
                }
            }
        } else if (node.childCount > 0) {
            Log.d(TAG, "🌳 ${indent}  ... (深度限制，跳过${node.childCount}个子节点)")
        }
    }
    
    /**
     * 判断是否是文本选择相关的事件
     */
    private fun isTextSelectionRelatedEvent(event: AccessibilityEvent): Boolean {
        return when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> true
            AccessibilityEvent.TYPE_VIEW_SELECTED -> true
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> true
            AccessibilityEvent.TYPE_VIEW_CONTEXT_CLICKED -> true
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> true
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> true
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START -> true
            AccessibilityEvent.TYPE_GESTURE_DETECTION_END -> true
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> true
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> true
            1024 -> true // TYPE_VIEW_ACCESSIBILITY_FOCUSED
            2048 -> true // TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
            512 -> true  // TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY
            else -> {
                // 检查事件是否包含选择相关的关键词
                val className = event.className?.toString() ?: ""
                val contentDesc = event.contentDescription?.toString() ?: ""
                val eventText = event.text?.joinToString(" ") ?: ""
                
                className.contains("Selection", ignoreCase = true) ||
                className.contains("Highlight", ignoreCase = true) ||
                className.contains("ActionMode", ignoreCase = true) ||
                contentDesc.contains("选中", ignoreCase = true) ||
                contentDesc.contains("selected", ignoreCase = true) ||
                contentDesc.contains("copy", ignoreCase = true) ||
                contentDesc.contains("复制", ignoreCase = true) ||
                eventText.contains("选中", ignoreCase = true) ||
                eventText.contains("selected", ignoreCase = true)
            }
        }
    }
    
    /**
     * 专门分析节点是否包含文本选择信息
     */
    private fun analyzeNodeForTextSelection(node: AccessibilityNodeInfo) {
        Log.d(TAG, "🔍🔍🔍 深度分析节点文本选择信息:")
        
        val nodeText = node.text?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString() ?: ""
        
        // 检查是否包含目标文本
        val containsTargetText = nodeText.contains("They need emotional bonds too", ignoreCase = true) ||
            nodeText.contains("emotional bonds", ignoreCase = true) ||
            nodeText.contains("bonds too", ignoreCase = true)
        
        if (containsTargetText) {
            Log.d(TAG, "🚨🚨🚨 深度分析发现目标文本!")
            Log.d(TAG, "🚨🚨🚨 目标文本完整内容: $nodeText")
        }
        
        Log.d(TAG, "  🔍 节点类名: $className")
        Log.d(TAG, "  🔍 节点文本长度: ${nodeText.length}")
        Log.d(TAG, "  🔍 节点文本内容: ${nodeText.take(100)}${if (nodeText.length > 100) "..." else ""}")
        Log.d(TAG, "  🔍 内容描述: $contentDesc")
        Log.d(TAG, "  🔍 选中状态: ${node.isSelected}")
        Log.d(TAG, "  🔍 焦点状态: ${node.isFocused}")
        Log.d(TAG, "  🔍 可访问焦点: ${node.isAccessibilityFocused}")
        
        // 检查是否有选择范围信息
        try {
            val actions = node.actionList
            Log.d(TAG, "  🔍 可用操作: ${actions?.map { it.label ?: it.id.toString() }}")
        } catch (e: Exception) {
            Log.d(TAG, "  🔍 获取操作列表失败: ${e.message}")
        }
        
        // 尝试获取文本选择范围
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
                val textSelectionStart = node.textSelectionStart
                val textSelectionEnd = node.textSelectionEnd
                Log.d(TAG, "  🔍 文本选择范围: $textSelectionStart - $textSelectionEnd")
                
                if (textSelectionStart >= 0 && textSelectionEnd > textSelectionStart && !nodeText.isNullOrBlank()) {
                    try {
                        val selectedText = nodeText.substring(textSelectionStart, textSelectionEnd)
                        Log.d(TAG, "  🎯🎯🎯 找到选中文本: $selectedText")
                        
                        // 移除元数据检查，只保留基本验证
                        if (selectedText.length > 5 && isValidText(selectedText)) {
                            Log.d(TAG, "  ✅ 有效选中文本，立即处理")
                            notifyTextSelected(selectedText)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "  ❌ 提取选中文本失败: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "  🔍 获取文本选择范围失败: ${e.message}")
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val childText = childNode.text?.toString() ?: ""
                    
                    // 检查子节点是否包含目标文本
                    val childContainsTarget = childText.contains("They need emotional bonds too", ignoreCase = true) ||
                        childText.contains("emotional bonds", ignoreCase = true) ||
                        childText.contains("bonds too", ignoreCase = true)
                    
                    if (childContainsTarget) {
                        Log.d(TAG, "🚨🚨🚨 子节点$i 包含目标文本!")
                        Log.d(TAG, "🚨🚨🚨 子节点$i 目标文本: $childText")
                    }
                    
                    if (childText.length > 10) {
                        Log.d(TAG, "  🔍 子节点$i 文本: ${childText.take(50)}...")
                        Log.d(TAG, "  🔍 子节点$i 选中: ${childNode.isSelected}")
                        Log.d(TAG, "  🔍 子节点$i 焦点: ${childNode.isAccessibilityFocused}")
                        
                        // 检查子节点的文本选择范围
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
                            val childSelStart = childNode.textSelectionStart
                            val childSelEnd = childNode.textSelectionEnd
                            if (childSelStart >= 0 && childSelEnd > childSelStart) {
                                Log.d(TAG, "  🎯 子节点$i 有选择范围: $childSelStart - $childSelEnd")
                                try {
                                    val selectedText = childText.substring(childSelStart, childSelEnd)
                                    Log.d(TAG, "  🎯🎯🎯 子节点选中文本: $selectedText")
                                    
                                    if (selectedText.length > 5 && isValidText(selectedText)) {
                                        Log.d(TAG, "  ✅ 子节点有效选中文本，立即处理")
                                        notifyTextSelected(selectedText)
                                    }
                                } catch (e: Exception) {
                                    Log.d(TAG, "  ❌ 子节点提取选中文本失败: ${e.message}")
                                }
                            }
                        }
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
    }
    
    /**
     * 查找真正选中的文本（有选中状态的节点）
     */
    private fun findActualSelectedText(node: AccessibilityNodeInfo): String? {
        // 检查当前节点是否被选中
        if (node.isSelected || node.isAccessibilityFocused) {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && nodeText.length > 20 && !isBookMetadata(nodeText)) {
                Log.d(TAG, "🎯 找到选中状态的节点: ${nodeText.take(50)}...")
                return nodeText.trim()
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val selectedText = findActualSelectedText(childNode)
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
     * 查找内容区域的文本（排除UI元素）
     */
    private fun findContentAreaText(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: ""
        
        // 查找可能是内容区域的节点
        if (className.contains("ImageView") || 
            className.contains("TextView") || 
            className.contains("WebView") ||
            className.contains("ScrollView")) {
            
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && 
                nodeText.length > 50 && 
                !isBookMetadata(nodeText) &&
                isContentText(nodeText)) {
                
                Log.d(TAG, "🎯 找到内容区域文本: ${nodeText.take(50)}...")
                return nodeText.trim()
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val contentText = findContentAreaText(childNode)
                    if (contentText != null) {
                        return contentText
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return null
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
        // 检查是否包含页码格式 "数字 / 数字"
        val pagePattern = "\\d+\\s*/\\s*\\d+".toRegex()
        if (pagePattern.containsMatchIn(text)) {
            Log.d(TAG, "🚫 检测到页码格式: ${pagePattern.find(text)?.value}")
            return true
        }
        
        // 检查是否包含出版信息模式 "-- 作者名 [作者名] -- 地点, 省份, 年份 -- 出版社"
        val publicationPattern = "--.*?\\[.*?\\].*?--.*?,.*?,.*?\\d{4}.*?--".toRegex()
        if (publicationPattern.containsMatchIn(text)) {
            Log.d(TAG, "🚫 检测到出版信息格式")
            return true
        }
        
        // 检查是否包含ISBN或哈希值
        if (text.contains("ISBN") || text.matches(".*[a-f0-9]{32}.*".toRegex())) {
            Log.d(TAG, "🚫 检测到ISBN或哈希值")
            return true
        }
        
        // 检查是否包含 Anna's Archive 标识
        if (text.contains("Anna's Archive", ignoreCase = true)) {
            Log.d(TAG, "🚫 检测到Anna's Archive标识")
            return true
        }
        // 检查是否包含明显的元数据特征
        val metadataKeywords = listOf(
            "Homo deus",
            "Yuval Noah Harari",
            "Toronto, Ontario",
            "McClelland & Stewart",
            "Anna's Archive",
            "brief history",
            "ISBN",
            "9780771038686",
            "7cc779d9f1068ac2c00aaf4d44be9c8e"
        )
        
        // 检查是否包含元数据关键词
        if (metadataKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }) {
            Log.d(TAG, "🚫 检测到元数据关键词: $text")
            return true
        }
        
        // 检查是否是页码格式 "96 / 460" 或 "97 / 460Homo deus"
        if (text.matches("^\\d+\\s*/\\s*\\d+.*".toRegex())) {
            Log.d(TAG, "🚫 检测到页码格式: $text")
            return true
        }
        
        // 检查是否包含年份和分隔符模式 "2016 --"
        if (text.contains("\\d{4}\\s*--".toRegex())) {
            Log.d(TAG, "🚫 检测到年份分隔符: $text")
            return true
        }
        
        // 检查是否包含长哈希值
        if (text.contains("[a-f0-9]{32}".toRegex())) {
            Log.d(TAG, "🚫 检测到哈希值: $text")
            return true
        }
        
        // 检查是否是方括号格式的元数据 "[Author, Name] -- Location, Year --"
        if (text.contains("\\[.*\\].*--.*--".toRegex())) {
            Log.d(TAG, "🚫 检测到方括号元数据: $text")
            return true
        }
        
        // 检查是否包含多个分隔符 "--"，这通常是元数据的特征
        val separatorCount = text.split("--").size - 1
        if (separatorCount >= 3) {
            Log.d(TAG, "🚫 检测到多个分隔符: $text")
            return true
        }
        
        // 新增：检查是否包含书名和下划线的组合（如 "Homo deus _ a brief history"）
        if (text.contains("_") && text.contains("brief history", ignoreCase = true)) {
            Log.d(TAG, "🚫 检测到书名下划线格式: $text")
            return true
        }
        
        // 新增：检查是否以页码开头且包含书名（如 "97 / 460Homo deus _ a brief history"）
        if (text.matches("^\\d+\\s*/\\s*\\d+[A-Za-z].*".toRegex())) {
            Log.d(TAG, "🚫 检测到页码+书名格式: $text")
            return true
        }
        
        // 新增：检查是否包含作者名格式（如 "Yuval Noah Harari [Harari, Yuval Noah]"）
        if (text.contains("\\[.*,.*\\]".toRegex())) {
            Log.d(TAG, "🚫 检测到作者名格式: $text")
            return true
        }
        
        // 新增：检查是否包含出版信息（如 "Toronto" 等地名）
        val locationKeywords = listOf("Toronto", "Ontario", "McClelland", "Stewart")
        if (locationKeywords.any { keyword -> text.contains(keyword, ignoreCase = true) }) {
            Log.d(TAG, "🚫 检测到出版地信息: $text")
            return true
        }
        
        return false
    }
    
    /**
     * 判断是否是正文内容
     */
    private fun isContentText(text: String): Boolean {
        // 正文内容的特征
        val contentIndicators = listOf(
            "\\.", // 包含句号
            "，", // 包含中文逗号
            ",", // 包含英文逗号
            "。", // 包含中文句号
            "the\\s+", // 包含英文冠词
            "and\\s+", // 包含连词
            "in\\s+", // 包含介词
            "of\\s+", // 包含介词
            "to\\s+", // 包含介词
            "that\\s+", // 包含连词
            "is\\s+", // 包含be动词
            "was\\s+", // 包含be动词过去式
            "were\\s+", // 包含be动词复数过去式
            "have\\s+", // 包含助动词
            "had\\s+", // 包含助动词过去式
            "will\\s+", // 包含情态动词
            "would\\s+", // 包含情态动词
            "can\\s+", // 包含情态动词
            "could\\s+" // 包含情态动词
        )
        
        // 检查是否包含正文特征
        val hasContentFeatures = contentIndicators.any { pattern ->
            text.contains(pattern.toRegex(RegexOption.IGNORE_CASE))
        }
        
        // 检查文本长度和结构
        val hasGoodLength = text.length in 50..2000
        val hasMultipleWords = text.split("\\s+".toRegex()).size > 10
        
        return hasContentFeatures && hasGoodLength && hasMultipleWords
    }
    
    /**
     * 递归查找所有文本节点
     */
    private fun findTextNodes(node: AccessibilityNodeInfo, textList: MutableList<String>) {
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank() && nodeText.length > 5) {
            textList.add(nodeText)
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    findTextNodes(childNode, textList)
                } finally {
                    childNode.recycle()
                }
            }
        }
    }
    
    /**
     * 处理选中文本请求
     */
    private fun handleSelectedTextRequest() {
        Log.d(TAG, "🔍 开始处理选中文本请求...")
        
        // 优先使用最近保存的有效选中文本
        if (lastProcessedText.isNotEmpty() && 
            !lastProcessedText.contains("输入问题或点击分析") && 
            lastProcessedText.length > 10 && 
            isValidText(lastProcessedText)) {
            
            Log.d(TAG, "✅ 使用已保存的选中文本: ${lastProcessedText.take(50)}...")
            notifyTextSelected(lastProcessedText)
            return
        }
        
        Log.d(TAG, "📝 已保存文本无效，尝试重新提取...")
        Log.d(TAG, "📝 已保存文本内容: $lastProcessedText")
        
        // 尝试从当前UI中提取选中文本
        val rootNode = rootInActiveWindow
        if (rootNode != null) {
            try {
                val selectedText = findAllPossibleSelectedText(rootNode)
                
                if (!selectedText.isNullOrBlank() && 
                    isValidText(selectedText) && 
                    !selectedText.contains("输入问题或点击分析") &&
                    selectedText.length > 10) {
                    
                    Log.d(TAG, "✅ 重新提取成功: ${selectedText.take(50)}...")
                    
                    // 更新最后处理的文本
                    lastProcessedText = selectedText
                    
                    // 发送选中文本
                    notifyTextSelected(selectedText)
                    return
                }
             } finally {
                 rootNode.recycle()
             }
         }
        
        Log.d(TAG, "❌ 未能获取到有效的选中文本，尝试备用方案...")
        
        // 备用方案：获取当前页面的部分文本
        val pageText = getCurrentPageText()
        if (pageText.isNotEmpty()) {
            // 取页面文本的一部分作为示例
            val sampleText = pageText.take(200)
            if (isValidText(sampleText) && !sampleText.contains("输入问题或点击分析")) {
                Log.d(TAG, "💡 使用页面文本作为备用: ${sampleText.take(50)}...")
                notifyTextSelected(sampleText)
            } else {
                Log.d(TAG, "❌ 页面文本也无效")
                notifyTextSelectionError()
            }
        } else {
            Log.d(TAG, "❌ 无法获取任何文本")
            notifyTextSelectionError()
        }
    }
    
    /**
     * 通知文本选择错误
     */
    private fun notifyTextSelectionError() {
        val intent = Intent("com.readassist.TEXT_SELECTION_ERROR")
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d(TAG, "📍 通知文本选择错误")
    }
    
    /**
     * 检查选择状态变化（基于UI变化推断）
     */
    private fun checkForSelectionStateChange(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        
        try {
            // 查找可能包含选中文本的节点
            val hasSelectedContent = findAnySelectedContent(rootNode)
            
            if (hasSelectedContent) {
                Log.d(TAG, "🎯 基于UI变化检测到可能的文本选择")
                notifyTextSelectionActive()
                
                // 延迟获取选中文本
                mainHandler.postDelayed({
                    extractTextFromSupernoteSelection()
                }, 300)
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查选择状态变化时出错", e)
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 查找任何可能的选中内容
     */
    private fun findAnySelectedContent(node: AccessibilityNodeInfo): Boolean {
        // 检查当前节点
        if (node.isSelected || node.isAccessibilityFocused) {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && nodeText.length > 5) {
                Log.d(TAG, "🎯 找到选中节点: ${nodeText.take(30)}...")
                return true
            }
        }
        
        // 检查节点描述
        val contentDescription = node.contentDescription?.toString()
        if (!contentDescription.isNullOrBlank()) {
            if (contentDescription.contains("选中") || 
                contentDescription.contains("selected") ||
                contentDescription.contains("highlight") ||
                contentDescription.contains("copy") ||
                contentDescription.contains("复制")) {
                Log.d(TAG, "🎯 找到选择相关描述: $contentDescription")
                return true
            }
        }
        
        // 检查类名是否暗示选择状态
        val className = node.className?.toString() ?: ""
        if (className.contains("Selection") || 
            className.contains("Highlight") ||
            className.contains("ActionMode")) {
            Log.d(TAG, "🎯 找到选择相关类名: $className")
            return true
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    if (findAnySelectedContent(childNode)) {
                        return true
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return false
    }
    
    /**
     * 获取文本选择区域的边界
     */
    private fun getTextSelectionBounds(): android.graphics.Rect? {
        val rootNode = rootInActiveWindow ?: return null
        
        return try {
            findSelectedNodeBounds(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "获取文本选择边界失败", e)
            null
        } finally {
            rootNode.recycle()
        }
    }
    
    /**
     * 查找选中节点的边界
     */
    private fun findSelectedNodeBounds(node: AccessibilityNodeInfo): android.graphics.Rect? {
        // 检查当前节点是否被选中
        if (node.isSelected || node.isAccessibilityFocused) {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrBlank() && nodeText.length > 10 && !isBookMetadata(nodeText)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                Log.d(TAG, "🎯 找到选中节点边界: $bounds")
                return bounds
            }
        }
        
        // 检查是否有文本选择范围
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            val textSelectionStart = node.textSelectionStart
            val textSelectionEnd = node.textSelectionEnd
            if (textSelectionStart >= 0 && textSelectionEnd > textSelectionStart) {
                val nodeText = node.text?.toString()
                if (!nodeText.isNullOrBlank()) {
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    Log.d(TAG, "🎯 找到文本选择范围边界: $bounds")
                    return bounds
                }
            }
        }
        
        // 递归检查子节点
        for (i in 0 until node.childCount) {
            val childNode = node.getChild(i)
            if (childNode != null) {
                try {
                    val bounds = findSelectedNodeBounds(childNode)
                    if (bounds != null) {
                        return bounds
                    }
                } finally {
                    childNode.recycle()
                }
            }
        }
        
        return null
    }
} 