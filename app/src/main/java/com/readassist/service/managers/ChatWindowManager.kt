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
import android.widget.CheckBox
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
import com.readassist.ReadAssistApplication
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
    private var newChatButton: Button? = null
    private var tvTitle: TextView? = null
    
    // 文本选择管理器
    private var textSelectionManager: TextSelectionManager? = null
    
    // 保存输入框内容，防止窗口重建时丢失
    private var lastInputText: String = ""
    
    // AI配置相关UI组件
    private var platformSpinner: Spinner? = null
    private var modelSpinner: Spinner? = null
    private var configStatusIndicator: TextView? = null
    
    // 新增勾选项UI组件
    private var checkSendScreenshot: CheckBox? = null
    private var tvScreenshotTime: TextView? = null
    private var checkSendClipboard: CheckBox? = null
    private var tvClipboardContent: TextView? = null
    private var checkAutoPopup: CheckBox? = null
    
    // 聊天记录
    private val chatHistory = mutableListOf<ChatItem>()
    
    // 当前状态
    private var isWindowVisible = false
    
    // 主线程Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // 新增勾选项监听回调
    interface OnCheckStateChangedListener {
        fun onCheckStateChanged()
    }
    private var checkStateChangedListener: OnCheckStateChangedListener? = null
    fun setOnCheckStateChangedListener(listener: OnCheckStateChangedListener) {
        checkStateChangedListener = listener
    }
    
    /**
     * 截屏监控勾选框回调接口
     */
    interface OnScreenshotMonitoringStateChangedListener {
        fun onScreenshotMonitoringStateChanged(enabled: Boolean)
    }
    
    private var screenshotMonitoringListener: OnScreenshotMonitoringStateChangedListener? = null
    
    fun setOnScreenshotMonitoringStateChangedListener(listener: OnScreenshotMonitoringStateChangedListener) {
        screenshotMonitoringListener = listener
    }
    
    /**
     * 获取截屏监控状态
     */
    fun isScreenshotMonitoringEnabled(): Boolean {
        return checkAutoPopup?.isChecked ?: true
    }
    
    /**
     * 设置截屏监控状态
     */
    fun setScreenshotMonitoringEnabled(enabled: Boolean) {
        checkAutoPopup?.isChecked = enabled
    }
    
    /**
     * 检查并根据勾选状态更新输入框内容
     * 只有当输入框内容是默认的提示文本时才会更新
     */
    fun updateInputTextByCheckState(newHint: String) {
        Log.e(TAG, "[日志追踪] 检查是否需要更新输入框内容")
        
        // 获取当前输入框内容
        val currentText = inputEditText?.text?.toString() ?: ""
        
        // 检查当前内容是否是默认提示文本
        val isDefaultPrompt = currentText.isBlank() ||
            currentText == "请分析这张截屏图片：" ||
            currentText == "请分析这段文字：" ||
            currentText == "请分析发给你的图片和文字内容。" ||
            currentText == "请输入您的问题或内容" ||
            currentText.startsWith("请分析")
        
        // 特殊情况：如果新提示是"请输入您的问题或内容"，只设置hint不改变内容
        if (newHint == "请输入您的问题或内容") {
            Log.e(TAG, "[日志追踪] 提示为默认输入提示，只设置hint不改变内容")
            // 如果当前内容也是提示文本，则清空输入框
            if (isDefaultPrompt) {
                inputEditText?.text?.clear()
                Log.e(TAG, "[日志追踪] 清空输入框内容")
            }
            setInputHint(newHint)
        }
        // 普通情况：如果当前内容是默认提示，更新为新提示
        else if (isDefaultPrompt) {
            Log.e(TAG, "[日志追踪] 输入框内容是默认提示，更新为: $newHint")
            inputEditText?.setText(newHint)
            setInputHint(newHint)
        } else {
            Log.e(TAG, "[日志追踪] 输入框内容不是默认提示，保持不变: $currentText")
            setInputHint(newHint)
        }
    }
    
    /**
     * 创建并显示聊天窗口
     */
    fun showChatWindow() {
        Log.e(TAG, "[日志追踪] showChatWindow 被调用")
        if (chatWindow != null) {
            Log.e(TAG, "[日志追踪] 已有窗口，先隐藏")
            hideChatWindow()
        }
        
        createChatWindow()
        isWindowVisible = true
        
        // 通知回调，让外部处理截屏状态
        Log.e(TAG, "[日志追踪] 通知外部处理截屏状态")
        callbacks.onChatWindowShown()
        
        Log.e(TAG, "[日志追踪] showChatWindow 完成，窗口已显示")
    }
    
    /**
     * 隐藏聊天窗口
     */
    fun hideChatWindow() {
        // 保存输入框内容
        lastInputText = inputEditText?.text?.toString() ?: ""
        Log.e(TAG, "[日志追踪] 窗口隐藏时保存输入内容: $lastInputText")
        
        removeChatWindow()
        isWindowVisible = false
        
        // 通知回调
        callbacks.onChatWindowHidden()
    }
    
    /**
     * 创建聊天窗口
     */
    private fun createChatWindow() {
        Log.e(TAG, "[日志追踪] createChatWindow 被调用")
        // 创建一个包含背景遮罩的容器
        val containerView = android.widget.FrameLayout(context)
        
        // 创建半透明背景遮罩，覆盖整个屏幕
        val backgroundOverlay = View(context).apply {
            setBackgroundColor(0x80000000.toInt()) // 半透明黑色
            
            // 修改点击事件处理，增加安全边界区域
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_DOWN) {
                    // 获取聊天内容区域的边界
                    val chatContent = containerView.getChildAt(1) // 聊天内容是第二个子视图
                    if (chatContent != null) {
                        val location = IntArray(2)
                        chatContent.getLocationOnScreen(location)
                        
                        val contentLeft = location[0]
                        val contentTop = location[1]
                        val contentRight = contentLeft + chatContent.width
                        val contentBottom = contentTop + chatContent.height
                        
                        // 定义安全边界区域（在聊天内容周围增加48dp的安全区域）
                        val safetyMargin = (48 * context.resources.displayMetrics.density).toInt()
                        val safeLeft = contentLeft - safetyMargin
                        val safeTop = contentTop - safetyMargin
                        val safeRight = contentRight + safetyMargin
                        val safeBottom = contentBottom + safetyMargin
                        
                        val touchX = event.rawX.toInt()
                        val touchY = event.rawY.toInt()
                        
                        // 只有在安全区域之外的点击才关闭窗口
                        if (touchX < safeLeft || touchX > safeRight || 
                            touchY < safeTop || touchY > safeBottom) {
                            Log.e(TAG, "[触摸事件] 在安全区域外点击，关闭窗口")
                            hideChatWindow()
                            return@setOnTouchListener true
                        } else {
                            Log.e(TAG, "[触摸事件] 在安全区域内点击，不关闭窗口")
                            return@setOnTouchListener false
                        }
                    }
                }
                false
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
            Log.e(TAG, "[日志追踪] addView 执行成功，窗口已添加")
            
            // 更新窗口标题
            updateWindowTitle()
        } catch (e: Exception) {
            Log.e(TAG, "[日志追踪] addView 失败: ${e.message}", e)
        }
    }
    
    /**
     * 初始化聊天窗口视图组件
     */
    private fun initializeChatViews(contentView: View?) {
        val window = contentView ?: chatWindow
        window?.let { view ->
            // 初始化组件
            chatListView = view.findViewById(R.id.chatListView)
            inputEditText = view.findViewById(R.id.inputEditText)
            Log.e(TAG, "[日志追踪] 输入框初始化: ${inputEditText != null}")
            Log.e(TAG, "[日志追踪] 输入框ID: ${R.id.inputEditText}")
            Log.e(TAG, "[日志追踪] 输入框内容: ${inputEditText?.text}")
            
            // 恢复上次的输入内容
            if (lastInputText.isNotEmpty()) {
                Log.e(TAG, "[日志追踪] 恢复上次输入内容: $lastInputText")
                inputEditText?.setText(lastInputText)
            }
            
            // 添加文本变化监听器，保存输入内容
            inputEditText?.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    lastInputText = s?.toString() ?: ""
                    Log.e(TAG, "[日志追踪] 保存当前输入内容: $lastInputText")
                }
            })
            
            sendButton = view.findViewById(R.id.sendButton)
            newChatButton = view.findViewById(R.id.newChatButton)
            
            // 初始化AI配置UI组件
            platformSpinner = view.findViewById(R.id.platformSpinner)
            modelSpinner = view.findViewById(R.id.modelSpinner)
            configStatusIndicator = view.findViewById(R.id.configStatusIndicator)
            
            // 初始化新增勾选项
            checkSendScreenshot = view.findViewById(R.id.checkSendScreenshot)
            tvScreenshotTime = view.findViewById(R.id.tvScreenshotTime)
            checkSendClipboard = view.findViewById(R.id.checkSendClipboard)
            tvClipboardContent = view.findViewById(R.id.tvClipboardContent)
            
            // 初始化截屏监控勾选框
            checkAutoPopup = view.findViewById(R.id.checkAutoPopup)
            // 读取保存的偏好设置
            val savedState = preferenceManager.getBoolean("screenshot_auto_popup", true)
            checkAutoPopup?.isChecked = savedState
            
            // 剪贴板勾选项对所有设备开放
            checkSendClipboard?.visibility = View.VISIBLE
            tvClipboardContent?.visibility = View.VISIBLE
            
            // 初始化聊天适配器
            chatAdapter = ChatAdapter(context, chatHistory)
            chatListView?.adapter = chatAdapter
            
            // 设置按钮点击事件
            sendButton?.setOnClickListener {
                Log.e(TAG, "[日志追踪] 发送按钮被点击！")
                Log.e(TAG, "[日志追踪] 输入框对象: $inputEditText")
                Log.e(TAG, "[日志追踪] 输入框文本对象: ${inputEditText?.text}")
                val message = inputEditText?.text?.toString()?.trim()
                Log.e(TAG, "输入框内容: $message")
                
                // 新增：当输入框内容为空但有hint文本时，使用hint文本
                if (message.isNullOrEmpty() && 
                    (isSendScreenshotChecked() || isSendClipboardChecked())) {
                    val hintText = inputEditText?.hint?.toString()
                    Log.e(TAG, "[日志追踪] 输入框为空但有提示文本: $hintText")
                    
                    if (!hintText.isNullOrEmpty() && 
                        (hintText.contains("请分析这张截屏图片") || 
                         hintText.contains("请分析这段文字") || 
                         hintText.contains("请分析发给你的图片和文字内容"))) {
                        Log.e(TAG, "[日志追踪] 使用提示文本作为消息内容发送")
                        inputEditText?.setText("")
                        callbacks.onMessageSend(hintText)
                        return@setOnClickListener
                    }
                }
                
                if (!message.isNullOrEmpty()) {
                    inputEditText?.setText("")
                    callbacks.onMessageSend(message)
                } else {
                    Log.e(TAG, "[日志追踪] 输入框为空，不发送消息")
                }
            }
            
            newChatButton?.setOnClickListener {
                callbacks.onNewChatButtonClick()
            }
            
            // 关闭按钮
            view.findViewById<Button>(R.id.closeButton)?.setOnClickListener {
                hideChatWindow()
            }
            
            // 初始化AI配置UI
            setupAiConfigurationUI()
            
            // 如果有聊天记录，滚动到底部
            if (chatHistory.isNotEmpty()) {
                scrollToBottom()
            }
            
            checkSendScreenshot?.setOnCheckedChangeListener { _, isChecked ->
                // 触发勾选状态变化回调
                Log.e(TAG, "[日志追踪] 发送截图勾选框状态变化: $isChecked")
                val listener = checkStateChangedListener
                if (listener != null) {
                    listener.onCheckStateChanged()
                }
            }
            checkSendClipboard?.setOnCheckedChangeListener { _, isChecked ->
                // 触发勾选状态变化回调
                Log.e(TAG, "[日志追踪] 发送剪贴板勾选框状态变化: $isChecked")
                val listener = checkStateChangedListener
                if (listener != null) {
                    listener.onCheckStateChanged()
                }
            }
            
            checkAutoPopup?.setOnCheckedChangeListener { _, isChecked ->
                Log.e(TAG, "[日志追踪] 截屏监控勾选框状态变化: $isChecked")
                // 保存设置到偏好
                preferenceManager.setBoolean("screenshot_auto_popup", isChecked)
                // 通知监听器
                screenshotMonitoringListener?.onScreenshotMonitoringStateChanged(isChecked)
            }
        }
    }
    
    /**
     * 移除聊天窗口
     */
    private fun removeChatWindow() {
        Log.e(TAG, "[日志追踪] removeChatWindow 被调用")
        chatWindow?.let { window ->
            try {
                windowManager.removeView(window)
                Log.e(TAG, "[日志追踪] removeView 执行成功，窗口已移除")
                chatWindow = null
                chatWindowParams = null
            } catch (e: Exception) {
                Log.e(TAG, "[日志追踪] removeView 失败: ${e.message}", e)
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
        Log.e(TAG, "[日志追踪] 开始导入文本到输入框: $text")
        inputEditText?.let { editText ->
            // 获取当前输入框的文本
            val currentText = editText.text?.toString() ?: ""
            Log.e(TAG, "[日志追踪] 当前输入框内容: $currentText")
            
            // 如果输入框为空，直接设置文本
            if (currentText.isEmpty()) {
                editText.setText(text)
                Log.e(TAG, "[日志追踪] 输入框为空，直接设置文本: $text")
            } else {
                // 如果输入框有内容，在末尾添加选中文本
                val newText = if (currentText.endsWith(" ") || currentText.endsWith("\n")) {
                    currentText + text
                } else {
                    "$currentText\n$text"
                }
                editText.setText(newText)
                Log.e(TAG, "[日志追踪] 输入框有内容，追加文本: $newText")
            }
            
            // 将光标移到文本末尾
            editText.setSelection(editText.text?.length ?: 0)
            Log.e(TAG, "[日志追踪] 输入框内容设置完成，当前内容: ${editText.text}")
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
        val isIReader = com.readassist.utils.DeviceUtils.isIReaderDevice()
        val itemLayout = if (isIReader) R.layout.spinner_item_small else android.R.layout.simple_spinner_item
        val dropdownLayout = if (isIReader) R.layout.spinner_item_small else android.R.layout.simple_spinner_dropdown_item
        val adapter = ArrayAdapter(context, itemLayout, platformNames)
        adapter.setDropDownViewResource(dropdownLayout)
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
        
        val isIReader = com.readassist.utils.DeviceUtils.isIReaderDevice()
        val itemLayout = if (isIReader) R.layout.spinner_item_small else android.R.layout.simple_spinner_item
        val dropdownLayout = if (isIReader) R.layout.spinner_item_small else android.R.layout.simple_spinner_dropdown_item
        val adapter = ArrayAdapter(context, itemLayout, modelNames)
        adapter.setDropDownViewResource(dropdownLayout)
        
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
     * 优化：更新截屏图片勾选项和时间显示
     * @param screenshotTime 最近一张截屏的时间字符串，null或空表示无
     */
    fun updateScreenshotInfo(screenshotTime: String?) {
        if (screenshotTime.isNullOrBlank()) {
            tvScreenshotTime?.text = "无"
            checkSendScreenshot?.isEnabled = false
            checkSendScreenshot?.isChecked = false
        } else {
            tvScreenshotTime?.text = screenshotTime
            checkSendScreenshot?.isEnabled = true
        }
    }

    /**
     * 优化：更新剪贴板内容勾选项和内容显示
     * @param clipboardContent 当天剪贴板内容，null或空表示无
     */
    fun updateClipboardInfo(clipboardContent: String?) {
        if (clipboardContent.isNullOrBlank()) {
            tvClipboardContent?.text = "无"
            checkSendClipboard?.isEnabled = false
            checkSendClipboard?.isChecked = false
        } else {
            tvClipboardContent?.text = clipboardContent
            checkSendClipboard?.isEnabled = true
        }
    }
    
    /**
     * 聊天窗口回调接口
     */
    interface ChatWindowCallbacks {
        fun onChatWindowShown()
        fun onChatWindowHidden()
        fun onMessageSend(message: String)
        fun onNewChatButtonClick()
        fun onConfigStatusClick(platform: AiPlatform?)
        fun onShowApiKeyDialog(platform: AiPlatform)
    }

    fun setInputHint(hint: String) {
        Log.e(TAG, "[日志追踪] 设置输入框提示: $hint")
        inputEditText?.hint = hint
    }

    // 勾选项状态获取方法，提升为public
    fun isSendScreenshotChecked(): Boolean {
        return checkSendScreenshot?.isChecked ?: false
    }
    fun isSendClipboardChecked(): Boolean {
        return checkSendClipboard?.isChecked ?: false
    }

    // 设置"发送截图"勾选框状态
    fun setSendScreenshotChecked(checked: Boolean) {
        Log.e(TAG, "[日志追踪] 设置发送截图勾选框状态: $checked")
        checkSendScreenshot?.isChecked = checked
    }
    
    // 设置"发送剪贴板内容"勾选框状态
    fun setSendClipboardChecked(checked: Boolean) {
        Log.e(TAG, "[日志追踪] 设置发送剪贴板勾选框状态: $checked")
        checkSendClipboard?.isChecked = checked
    }

    /**
     * 设置文本选择管理器
     */
    fun setTextSelectionManager(manager: TextSelectionManager) {
        textSelectionManager = manager
    }

    /**
     * 更新聊天窗口信息和标题
     */
    fun updateChatWindowInfoAndTitle() {
        val appPackage = textSelectionManager?.getCurrentAppPackage() ?: ""
        val bookName = textSelectionManager?.getCurrentBookName() ?: ""
        
        // 更新窗口标题
        tvTitle?.text = when {
            bookName.isNotEmpty() -> bookName
            appPackage.isNotEmpty() -> appPackage
            else -> "阅读助手"
        }
    }

    /**
     * 更新聊天窗口输入提示
     */
    fun updateChatWindowInputHint(hint: String) {
        inputEditText?.hint = hint
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
                        leftMargin = 0
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