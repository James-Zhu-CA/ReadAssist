package com.readassist.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import com.readassist.R
import com.readassist.databinding.ActivityMainBinding
import com.readassist.service.FloatingWindowService
import com.readassist.service.ScreenshotService
import com.readassist.utils.ApiKeyHelper
import com.readassist.utils.PermissionUtils
import com.readassist.viewmodel.MainViewModel

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var permissionChecker: PermissionUtils.PermissionChecker
    private lateinit var app: com.readassist.ReadAssistApplication
    
    // 截屏权限相关
    private val screenshotPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val app = application as com.readassist.ReadAssistApplication
        
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // 保存权限状态和数据到偏好设置
            app.preferenceManager.setScreenshotPermissionGranted(true)
            app.preferenceManager.setScreenshotPermissionData(
                result.resultCode,
                result.data?.toUri(0)
            )
            
            // 启动截屏服务并传递权限数据
            val intent = Intent(this, ScreenshotService::class.java).apply {
                action = ScreenshotService.ACTION_START_SCREENSHOT
                putExtra(ScreenshotService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenshotService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            
            showMessage("截屏权限已授予，截屏功能已启用")
            updateFloatingServiceStatus()
        } else {
            app.preferenceManager.setScreenshotPermissionGranted(false)
            showMessage("截屏权限被拒绝，截屏功能将无法使用")
            updateFloatingServiceStatus()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化应用实例
        app = application as com.readassist.ReadAssistApplication
        
        // 使用 ViewBinding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 初始化权限检查器
        permissionChecker = PermissionUtils.PermissionChecker(this)
        
        // 设置观察者
        setupObservers()
        
        // 设置点击事件
        setupClickListeners()
        
        // 检查首次启动
        checkFirstLaunch()
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到前台都检查状态
        viewModel.checkPermissions()
        viewModel.checkServiceStatus()
        viewModel.checkApiKey()
        
        // 更新悬浮窗服务状态
        updateFloatingServiceStatus()
        
        // 通知悬浮窗服务重新检查截屏权限（如果服务正在运行）
        if (isFloatingWindowServiceRunning()) {
            // 通过广播通知悬浮窗服务权限状态可能已变化
            val intent = Intent("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
            sendBroadcast(intent)
        }
    }
    
    /**
     * 设置观察者
     */
    private fun setupObservers() {
        // 权限状态观察
        viewModel.permissionStatus.observe(this, Observer { status ->
            updatePermissionStatus(status)
        })
        
        // 服务状态观察
        viewModel.isServiceRunning.observe(this, Observer { isRunning ->
            updateServiceStatus(isRunning)
        })
        
        // API Key 状态观察
        viewModel.hasApiKey.observe(this, Observer { hasKey ->
            updateApiKeyStatus(hasKey)
        })
        
        // 统计信息观察
        viewModel.statistics.observe(this, Observer { stats ->
            updateStatistics(stats)
        })
        
        // 加载状态观察
        viewModel.isLoading.observe(this, Observer { isLoading ->
            updateLoadingState(isLoading)
        })
        
        // 错误消息观察
        viewModel.errorMessage.observe(this, Observer { message ->
            if (message.isNotEmpty()) {
                showMessage(message)
                viewModel.clearErrorMessage()
            }
        })
    }
    
    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 权限设置按钮
        binding.btnPermissions.setOnClickListener {
            requestPermissions()
        }
        
        // API Key 设置按钮
        binding.btnApiKey.setOnClickListener {
            if (!app.preferenceManager.isAiSetupCompleted()) {
                showAiSetupWizard()
            } else {
                // 已配置，跳转到设置页面进行修改
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
        
        // 设置按钮
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        // 历史记录按钮
        binding.btnHistory.setOnClickListener {
            // TODO: 实现历史记录界面
            showMessage("历史记录功能即将推出")
        }
        
        // 清除数据按钮
        binding.btnClearData.setOnClickListener {
            showClearDataDialog()
        }
        
        // 添加悬浮窗管理按钮（如果布局中有的话）
        binding.btnFloatingWindow?.setOnClickListener {
            toggleFloatingWindowService()
        }
        
        // 添加截屏权限按钮（如果布局中有的话）
        binding.btnScreenshotPermission?.setOnClickListener {
            requestScreenshotPermission()
        }
    }
    
    /**
     * 检查首次启动
     */
    private fun checkFirstLaunch() {
        val app = application as com.readassist.ReadAssistApplication
        if (app.preferenceManager.isFirstLaunch()) {
            showWelcomeDialog()
            app.preferenceManager.setFirstLaunch(false)
        }
    }
    
    /**
     * 显示欢迎对话框
     */
    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle("欢迎使用 ReadAssist")
            .setMessage("ReadAssist 是专为 Supernote A5X 设计的智能阅读助手。\n\n首次使用需要配置AI服务：\n\n1. 选择AI平台（Gemini 或 SiliconFlow）\n2. 配置对应的API Key\n3. 授予必要权限\n4. 开始智能阅读！\n\n点击\"开始配置\"进入设置向导。")
            .setPositiveButton("开始配置") { _, _ ->
                if (!app.preferenceManager.isAiSetupCompleted()) {
                    showAiSetupWizard()
                } else {
                    requestPermissions()
                }
            }
            .setNegativeButton("稍后设置", null)
            .show()
    }
    
    /**
     * 请求权限
     */
    private fun requestPermissions() {
        permissionChecker.checkAndRequestPermissions(object : PermissionUtils.PermissionCallback {
            override fun onPermissionGranted() {
                showMessage("所有权限已授予")
                viewModel.checkPermissions()
                viewModel.checkServiceStatus()
            }
            
            override fun onPermissionDenied(missingPermissions: List<String>) {
                val message = "缺少权限：${missingPermissions.joinToString(", ")}"
                showMessage(message)
            }
        })
    }
    
    /**
     * 显示AI设置向导
     */
    private fun showAiSetupWizard() {
        val platforms = com.readassist.model.AiPlatform.values()
        val platformNames = platforms.map { it.displayName }.toTypedArray()
        
        // 添加调试信息
        android.util.Log.d("MainActivity", "平台数量: ${platforms.size}")
        platforms.forEachIndexed { index, platform ->
            android.util.Log.d("MainActivity", "平台 $index: ${platform.displayName}")
        }
        
        if (platforms.isEmpty()) {
            showMessage("❌ 没有可用的AI平台")
            return
        }
        
        try {
            AlertDialog.Builder(this)
                .setTitle("选择AI平台")
                .setMessage("请选择您要使用的AI平台：")
                .setItems(platformNames) { dialog, which ->
                    android.util.Log.d("MainActivity", "用户选择了平台: $which")
                    if (which >= 0 && which < platforms.size) {
                        val selectedPlatform = platforms[which]
                        android.util.Log.d("MainActivity", "选择的平台: ${selectedPlatform.displayName}")
                        dialog.dismiss()
                        showApiKeySetupDialog(selectedPlatform)
                    }
                }
                .setNegativeButton("取消") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(false)
                .create()
                .show()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "显示AI设置向导失败", e)
            showMessage("❌ 显示设置向导失败: ${e.message}")
        }
    }
    
    /**
     * 显示API Key设置对话框
     */
    private fun showApiKeySetupDialog(platform: com.readassist.model.AiPlatform) {
        val input = android.widget.EditText(this).apply {
            hint = platform.keyHint
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val message = "配置 ${platform.displayName}\n\n${platform.keyHint}\n\n申请地址：${platform.signupUrl}\n\n请复制您的API Key到下方："
        
        AlertDialog.Builder(this)
            .setTitle("配置API Key")
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
                        
                        showMessage("✅ ${platform.displayName} 配置成功！")
                        
                        // 更新UI
                        viewModel.checkApiKey()
                        
                        // 继续权限设置
                        requestPermissions()
                    } else {
                        showMessage("❌ API Key 格式不正确，请检查后重试")
                        showApiKeySetupDialog(platform) // 重新显示对话框
                    }
                } else {
                    showMessage("请输入API Key")
                    showApiKeySetupDialog(platform) // 重新显示对话框
                }
            }
            .setNegativeButton("返回") { _, _ ->
                showAiSetupWizard() // 返回平台选择
            }
            .setNeutralButton("打开申请页面") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(platform.signupUrl))
                    startActivity(intent)
                    showApiKeySetupDialog(platform) // 重新显示对话框
                } catch (e: Exception) {
                    showMessage("无法打开浏览器")
                    showApiKeySetupDialog(platform)
                }
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 显示 API Key 设置对话框（兼容旧版本）
     */
    @Deprecated("使用新的多平台配置系统")
    private fun showApiKeyDialog() {
        // 重定向到新的设置界面
        startActivity(Intent(this, SettingsActivity::class.java))
    }
    
    /**
     * 显示清除数据确认对话框
     */
    private fun showClearDataDialog() {
        AlertDialog.Builder(this)
            .setTitle("清除所有数据")
            .setMessage("此操作将删除所有聊天记录和设置，且无法恢复。确定要继续吗？")
            .setPositiveButton("确定") { _, _ ->
                viewModel.clearAllData()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 更新权限状态显示
     */
    private fun updatePermissionStatus(status: PermissionUtils.PermissionStatus) {
        when {
            status.allGranted -> {
                binding.tvPermissionStatus.text = "✓ 所有权限已授予"
                binding.tvPermissionStatus.setTextColor(0xFF333333.toInt())
                binding.btnPermissions.text = "权限管理"
            }
            else -> {
                binding.tvPermissionStatus.text = "⚠ 缺少权限：${status.missingPermissions.joinToString(", ")}"
                binding.tvPermissionStatus.setTextColor(0xFF666666.toInt())
                binding.btnPermissions.text = "授予权限"
            }
        }
    }
    
    /**
     * 更新服务状态显示
     */
    private fun updateServiceStatus(isRunning: Boolean) {
        if (isRunning) {
            binding.tvServiceStatus.text = "✓ 服务正在运行"
            binding.tvServiceStatus.setTextColor(0xFF333333.toInt())
        } else {
            binding.tvServiceStatus.text = "⚠ 服务未运行"
            binding.tvServiceStatus.setTextColor(0xFF666666.toInt())
        }
    }
    
    /**
     * 更新 API Key 状态显示
     */
    private fun updateApiKeyStatus(hasKey: Boolean) {
        val app = application as com.readassist.ReadAssistApplication
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentModel = app.preferenceManager.getCurrentAiModel()
        val isConfigured = app.preferenceManager.isCurrentConfigurationValid()
        
        if (isConfigured && hasKey && currentModel != null) {
            binding.tvApiKeyStatus.text = "✓ ${currentPlatform.displayName} - ${currentModel.displayName}"
            binding.tvApiKeyStatus.setTextColor(0xFF4CAF50.toInt())
            binding.btnApiKey.text = "重新配置"
        } else if (app.preferenceManager.isAiSetupCompleted()) {
            binding.tvApiKeyStatus.text = "⚠ 配置不完整或无效"
            binding.tvApiKeyStatus.setTextColor(0xFFFF9800.toInt())
            binding.btnApiKey.text = "修复配置"
        } else {
            binding.tvApiKeyStatus.text = "❌ 未配置AI服务"
            binding.tvApiKeyStatus.setTextColor(0xFFF44336.toInt())
            binding.btnApiKey.text = "开始配置"
        }
    }
    
    /**
     * 更新统计信息显示
     */
    private fun updateStatistics(stats: com.readassist.repository.ChatStatistics) {
        binding.tvStatistics.text = "消息: ${stats.totalMessages} | 会话: ${stats.totalSessions}"
    }
    
    /**
     * 更新加载状态
     */
    private fun updateLoadingState(isLoading: Boolean) {
        // 简单的加载状态显示
        binding.btnApiKey.isEnabled = !isLoading
        binding.btnClearData.isEnabled = !isLoading
    }
    
    /**
     * 显示消息
     */
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 切换悬浮窗服务状态
     */
    private fun toggleFloatingWindowService() {
        if (isFloatingWindowServiceRunning()) {
            // 停止悬浮窗服务
            stopService(Intent(this, FloatingWindowService::class.java))
            showMessage("悬浮窗服务已停止")
        } else {
            // 检查权限后启动悬浮窗服务
            if (PermissionUtils.hasOverlayPermission(this)) {
                startFloatingWindowService()
            } else {
                showMessage("请先授予悬浮窗权限")
                requestPermissions()
            }
        }
        
        // 更新状态显示
        updateFloatingServiceStatus()
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindowService() {
        val intent = Intent(this, FloatingWindowService::class.java)
        startForegroundService(intent)
        showMessage("悬浮窗服务已启动")
    }
    
    /**
     * 检查悬浮窗服务是否运行
     */
    private fun isFloatingWindowServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (FloatingWindowService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    /**
     * 请求截屏权限
     */
    private fun requestScreenshotPermission() {
        if (isScreenshotPermissionGranted()) {
            showMessage("截屏权限已授予")
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle("截屏权限")
            .setMessage("截屏功能需要录屏权限来捕获屏幕内容。\n\n点击确定后，请在系统弹窗中选择\"立即开始\"。\n\n注意：此权限仅用于截屏分析，不会进行录制。")
            .setPositiveButton("授予权限") { _, _ ->
                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenshotPermissionLauncher.launch(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 检查截屏权限是否已授予
     */
    private fun isScreenshotPermissionGranted(): Boolean {
        val app = application as com.readassist.ReadAssistApplication
        return app.preferenceManager.isScreenshotPermissionGranted()
    }
    
    /**
     * 更新悬浮窗服务状态显示
     */
    private fun updateFloatingServiceStatus() {
        val isRunning = isFloatingWindowServiceRunning()
        
        binding.btnFloatingWindow?.text = if (isRunning) {
            "停止悬浮窗"
        } else {
            "启动悬浮窗"
        }
        
        // 更新状态文本（如果有对应的TextView）
        binding.tvFloatingWindowStatus?.text = if (isRunning) {
            "✓ 悬浮窗服务运行中"
        } else {
            "⚠ 悬浮窗服务未运行"
        }
        
        binding.tvFloatingWindowStatus?.setTextColor(
            if (isRunning) 0xFF333333.toInt() else 0xFF666666.toInt()
        )
        
        // 更新截屏权限状态
        binding.btnScreenshotPermission?.text = if (isScreenshotPermissionGranted()) {
            "截屏权限已授予"
        } else {
            "授予截屏权限"
        }
        
        binding.tvScreenshotStatus?.text = if (isScreenshotPermissionGranted()) {
            "✓ 截屏权限已授予"
        } else {
            "⚠ 截屏权限未授予"
        }
        
        binding.tvScreenshotStatus?.setTextColor(
            if (isScreenshotPermissionGranted()) 0xFF333333.toInt() else 0xFF666666.toInt()
        )
    }
} 