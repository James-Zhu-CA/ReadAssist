package com.readassist.service.managers

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.BaseAdapter
import android.widget.AdapterView
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import com.readassist.R
import com.readassist.model.AiPlatform
import com.readassist.service.ChatItem
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 管理聊天窗口的创建、显示和交互
 */
class ChatWindowManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val preferenceManager: PreferenceManager,
    private val coroutineScope: CoroutineScope,
    private val callbacks: ChatWindowCallbacks
) {
    companion object {
        private const val TAG = "ChatWindowManager"
        private const val CHAT_WINDOW_WIDTH_RATIO = 0.8f
        private const val CHAT_WINDOW_HEIGHT_RATIO = 0.6f
    }
    
    // 聊天窗口UI组件
    private var chatWindow: View? = null
    private var chatWindowParams: WindowManager.LayoutParams? = null
    private var chatAdapter: ChatAdapter? = null
    private var chatListView: ListView? = null
    private var inputEditText: EditText? = null
    private var sendButton: Button? = null
    private var analyzeButton: Button? = null
    private var screenshotButton: Button? = null
    private var newChatButton: Button? = null
    
    // AI配置相关UI组件
    private var platformSpinner: Spinner? = null
    private var modelSpinner: Spinner? = null
    private var configStatusIndicator: TextView? = null
    
    // 聊天记录
    private val chatHistory = mutableListOf<ChatItem>()
    
    // 当前状态
    private var isWindowVisible = false
    
    // 主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    /**
     * 创建并显示聊天窗口
     */
    fun showChatWindow() {
        if (chatWindow != null) {
            hideChatWindow()
        }
        
        createChatWindow()
        isWindowVisible = true
        
        // 通知回调
        callbacks.onChatWindowShown()
    }
    
    /**
     * 隐藏聊天窗口
     */
    fun hideChatWindow() {
        removeChatWindow()
        isWindowVisible = false
        
        // 通知回调
        callbacks.onChatWindowHidden()
    }
    
    /**
     * 创建聊天窗口
     */
    private fun createChatWindow() {
        // 创建一个包含背景遮罩的容器
        val containerView = android.widget.FrameLayout(context)
        
        // 创建半透明背景遮罩，覆盖整个屏幕
        val backgroundOverlay = View(context).apply {
            setBackgroundColor(0x80000000.toInt()) // 半透明黑色
            setOnClickListener {
                // 点击背景遮罩关闭窗口
                hideChatWindow()
            }
        }
        
        // 创建聊天窗口内容
        val chatContent = LayoutInflater.from(context).inflate(R.layout.chat_window, null)
        
        // 设置聊天内容的布局参数
        val displayMetrics = context.resources.displayMetrics
        val chatContentParams = android.widget.FrameLayout.LayoutParams(
            (displayMetrics.widthPixels * CHAT_WINDOW_WIDTH_RATIO).toInt(),
            (displayMetrics.heightPixels * CHAT_WINDOW_HEIGHT_RATIO).toInt()
        ).apply {
            gravity = Gravity.CENTER
        }
        
        // 将背景遮罩和聊天内容添加到容器中
        containerView.addView(backgroundOverlay, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
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
            
            // 更新窗口标题
            updateWindowTitle()
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
            chatAdapter = ChatAdapter(context, chatHistory)
            chatListView?.adapter = chatAdapter
            
            // 设置按钮点击事件
            sendButton?.setOnClickListener {
                val message = inputEditText?.text?.toString()?.trim()
                if (!message.isNullOrEmpty()) {
                    inputEditText?.setText("")
                    callbacks.onMessageSend(message)
                }
            }
            
            analyzeButton?.setOnClickListener {
                callbacks.onAnalyzeButtonClick()
            }
            
            screenshotButton?.setOnClickListener {
                callbacks.onScreenshotButtonClick()
            }
            
            newChatButton?.setOnClickListener {
                callbacks.onNewChatButtonClick()
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
     * 移除聊天窗口
     */
    private fun removeChatWindow() {
        chatWindow?.let { window ->
            try {
                windowManager.removeView(window)
                chatWindow = null
                chatWindowParams = null
                Log.d(TAG, "Chat window removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove chat window", e)
            }
        }
    }
    
    /**
     * 添加用户消息到聊天列表
     */
    fun addUserMessage(message: String) {
        val userChatItem = ChatItem(message, "", true, false)
        chatAdapter?.addItem(userChatItem)
        scrollToBottom()
    }
    
    /**
     * 添加AI消息到聊天列表
     */
    fun addAiMessage(message: String, isError: Boolean = false) {
        val aiChatItem = ChatItem("", message, false, false, isError)
        chatAdapter?.addItem(aiChatItem)
        scrollToBottom()
    }
    
    /**
     * 添加加载中消息
     */
    fun addLoadingMessage(message: String) {
        val loadingChatItem = ChatItem("", message, false, true)
        chatAdapter?.addItem(loadingChatItem)
        scrollToBottom()
    }
    
    /**
     * 添加系统消息
     */
    fun addSystemMessage(message: String) {
        val systemItem = ChatItem("", "💡 $message", false, false, false)
        chatAdapter?.addItem(systemItem)
        scrollToBottom()
    }
    
    /**
     * 移除最后一条消息（通常是加载中消息）
     */
    fun removeLastMessage() {
        chatAdapter?.removeLastItem()
    }
    
    /**
     * 清空聊天历史
     */
    fun clearChatHistory() {
        chatHistory.clear()
        chatAdapter?.notifyDataSetChanged()
    }
    
    /**
     * 更新聊天历史
     */
    fun updateChatHistory(newHistory: List<ChatItem>) {
        chatHistory.clear()
        chatHistory.addAll(newHistory)
        chatAdapter?.notifyDataSetChanged()
        scrollToBottom()
    }
    
    /**
     * 将文本导入到输入框
     */
    fun importTextToInputField(text: String) {
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
            
            // 显示提示信息
            addSystemMessage("📝 已导入选中文本，您可以编辑后点击发送按钮")
        }
    }
    
    /**
     * 更新分析按钮状态
     */
    fun updateAnalyzeButton(text: String?) {
        if (text.isNullOrEmpty()) {
            analyzeButton?.visibility = View.GONE
        } else {
            val shortText = if (text.length > 20) "${text.take(20)}..." else text
            analyzeButton?.apply {
                this.setText("分析选中文本 ($shortText)")
                visibility = View.VISIBLE
            }
        }
    }
    
    /**
     * 设置截屏按钮状态
     */
    fun updateScreenshotButton(enabled: Boolean, supportMessage: String? = null) {
        screenshotButton?.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else 0.5f
            text = if (enabled) "截屏分析" else (supportMessage ?: "不支持截屏")
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
            updateAiConfigurationStatus()
            
        } catch (e: Exception) {
            Log.e(TAG, "设置AI配置UI失败", e)
        }
    }
    
    /**
     * 设置平台选择器
     */
    private fun setupPlatformSpinner() {
        val platforms = AiPlatform.values()
        val platformNames = platforms.map { it.displayName }
        
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, platformNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        platformSpinner?.adapter = adapter
        
        // 设置当前选择
        val currentPlatform = preferenceManager.getCurrentAiPlatform()
        val currentIndex = platforms.indexOf(currentPlatform)
        if (currentIndex >= 0) {
            platformSpinner?.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        platformSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPlatform = platforms[position]
                if (selectedPlatform != preferenceManager.getCurrentAiPlatform()) {
                    preferenceManager.setCurrentAiPlatform(selectedPlatform)
                    
                    // 设置默认模型
                    val defaultModel = com.readassist.model.AiModel.getDefaultModelForPlatform(selectedPlatform)
                    if (defaultModel != null) {
                        preferenceManager.setCurrentAiModel(defaultModel.id)
                    }
                    
                    // 更新模型选择器和状态
                    setupModelSpinner()
                    updateAiConfigurationStatus()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 设置模型选择器
     */
    private fun setupModelSpinner() {
        val currentPlatform = preferenceManager.getCurrentAiPlatform()
        val availableModels = com.readassist.model.AiModel.getDefaultModels()
            .filter { it.platform == currentPlatform }
        
        val modelNames = availableModels.map { "${it.displayName}${if (!it.supportsVision) " (仅文本)" else ""}" }
        
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        modelSpinner?.adapter = adapter
        
        // 设置当前选择
        val currentModelId = preferenceManager.getCurrentAiModelId()
        val currentIndex = availableModels.indexOfFirst { it.id == currentModelId }
        if (currentIndex >= 0) {
            modelSpinner?.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        modelSpinner?.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = availableModels[position]
                if (selectedModel.id != preferenceManager.getCurrentAiModelId()) {
                    preferenceManager.setCurrentAiModel(selectedModel.id)
                    updateAiConfigurationStatus()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 更新AI配置状态
     */
    fun updateAiConfigurationStatus() {
        try {
            val isValid = preferenceManager.isCurrentConfigurationValid()
            val currentPlatform = preferenceManager.getCurrentAiPlatform()
            val hasApiKey = preferenceManager.hasApiKey(currentPlatform)
            val currentModel = preferenceManager.getCurrentAiModel()
            
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
                            callbacks.onConfigStatusClick(currentPlatform)
                        }
                    }
                    else -> {
                        text = "❌"
                        setTextColor(0xFFF44336.toInt())
                        setBackgroundColor(0xFFFFEBEE.toInt())
                        setOnClickListener {
                            callbacks.onConfigStatusClick(null)
                        }
                    }
                }
            }
            
            // 更新截屏按钮状态
            val supportsVision = currentModel?.supportsVision == true
            updateScreenshotButton(supportsVision && isValid, 
                if (!supportsVision) "不支持截屏" else "需要配置")
            
        } catch (e: Exception) {
            Log.e(TAG, "更新AI配置UI失败", e)
        }
    }
    
    /**
     * 显示API Key输入对话框
     */
    fun showApiKeyInputDialog(platform: AiPlatform) {
        callbacks.onShowApiKeyDialog(platform)
    }
    
    /**
     * 获取当前聊天历史
     */
    fun getChatHistory(): List<ChatItem> = chatHistory.toList()
    
    /**
     * 滚动到底部
     */
    fun scrollToBottom() {
        chatListView?.post {
            chatAdapter?.let { adapter ->
                if (adapter.count > 0) {
                    chatListView?.setSelection(adapter.count - 1)
                }
            }
        }
    }
    
    /**
     * 窗口是否可见
     */
    fun isVisible(): Boolean = isWindowVisible
    
    /**
     * 更新窗口标题
     */
    fun updateWindowTitle() {
        chatWindow?.let { window ->
            val titleText = window.findViewById<TextView>(R.id.titleText)
            
            // 从偏好设置获取当前应用和书籍名称
            val appPreference = preferenceManager.getString("current_app_package", "com.readassist")
            val bookPreference = preferenceManager.getString("current_book_name", "阅读笔记")
            
            // 获取可显示的应用名称
            val appDisplayName = when (appPreference) {
                "com.supernote.document" -> "Supernote"
                "com.ratta.supernote.launcher" -> "Supernote"
                "com.adobe.reader" -> "Adobe Reader"
                "com.kingsoft.moffice_eng" -> "WPS Office"
                "com.readassist" -> "ReadAssist"
                else -> appPreference.substringAfterLast(".")
            }
            
            // 设置标题
            val title = if (bookPreference == "阅读笔记" || bookPreference.isEmpty()) {
                "AI阅读助手"
            } else {
                "$bookPreference"
            }
            
            // 设置副标题
            val subtitle = if (appPreference != "com.readassist") {
                " - $appDisplayName"
            } else {
                ""
            }
            
            // 更新UI
            titleText?.text = title + subtitle
            
            Log.d(TAG, "📱 更新窗口标题: $title$subtitle")
        }
    }
    
    /**
     * 聊天窗口回调接口
     */
    interface ChatWindowCallbacks {
        fun onChatWindowShown()
        fun onChatWindowHidden()
        fun onMessageSend(message: String)
        fun onAnalyzeButtonClick()
        fun onScreenshotButtonClick()
        fun onNewChatButtonClick()
        fun onConfigStatusClick(platform: AiPlatform?)
        fun onShowApiKeyDialog(platform: AiPlatform)
    }
}

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
        messageTextView.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                // 添加复制选项
                menu?.add(0, android.R.id.copy, 0, "复制")?.setIcon(android.R.drawable.ic_menu_save)
                return true
            }
            
            override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
                // 移除默认的选择全部等选项，只保留复制
                menu?.clear()
                menu?.add(0, android.R.id.copy, 0, "复制")
                return true
            }
            
            override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
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
            
            override fun onDestroyActionMode(mode: ActionMode?) {
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