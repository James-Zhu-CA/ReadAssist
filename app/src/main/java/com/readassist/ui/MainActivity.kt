package com.readassist.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.readassist.R
import com.readassist.databinding.ActivityMainBinding
import com.readassist.service.FloatingWindowServiceNew
import com.readassist.service.ScreenshotService
import com.readassist.utils.ApiKeyHelper
import com.readassist.utils.DeviceUtils
import com.readassist.utils.DeviceType
import com.readassist.utils.PermissionUtils
import com.readassist.viewmodel.MainViewModel
import android.util.Log
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import com.readassist.utils.DeviceScreenshotManager
import com.readassist.utils.PreferenceManager
import com.readassist.utils.LanguageManager

class MainActivity : BaseActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var permissionChecker: PermissionUtils.PermissionChecker
    private lateinit var app: com.readassist.ReadAssistApplication
    
    // UI elements for storage permission
    // These are already part of binding, direct references here are not strictly needed if accessed via binding
    // private lateinit var tvStoragePermissionStatus: TextView 
    // private lateinit var btnRequestStoragePermission: Button
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
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
            
            showMessage(getString(R.string.screenshot_permission_granted))
            updateFloatingServiceStatus()
        } else {
            app.preferenceManager.setScreenshotPermissionGranted(false)
            showMessage(getString(R.string.screenshot_permission_denied))
            updateFloatingServiceStatus()
        }
    }
    
    // SAF目录权限相关
    private val safDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val deviceScreenshotManager = DeviceScreenshotManager(this, app.preferenceManager)
            val success = deviceScreenshotManager.handleDirectoryAccessResult(
                DeviceScreenshotManager.REQUEST_CODE_SAF_DIRECTORY,
                result.resultCode,
                result.data
            )
            
            if (success) {
                showMessage(getString(R.string.supernote_dir_permission_granted))
                // 刷新权限状态显示
                viewModel.checkPermissions()
            } else {
                showMessage(getString(R.string.permission_grant_failed))
            }
        } else {
            showMessage(getString(R.string.user_cancelled_permission))
        }
    }
    
    private val overlayPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.readassist.OVERLAY_PERMISSION_DENIED") {
                // 显示悬浮窗权限提示
                showOverlayPermissionDialog()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化应用实例
        app = application as com.readassist.ReadAssistApplication
        
        // 添加语言调试信息
        Log.e(TAG, "=== MainActivity onCreate 语言调试 ===")
        Log.e(TAG, "应用存储的语言设置: ${app.preferenceManager.getAppLanguage()}")
        Log.e(TAG, "当前Context Locale: ${this.resources.configuration.locales.get(0)}")
        Log.e(TAG, "系统默认Locale: ${java.util.Locale.getDefault()}")
        Log.e(TAG, "读取main_subtitle字符串: '${getString(R.string.main_subtitle)}'")
        Log.e(TAG, "读取system_status字符串: '${getString(R.string.system_status)}'")
        Log.e(TAG, "读取usage_statistics字符串: '${getString(R.string.usage_statistics)}'")
        Log.e(TAG, "读取function_menu字符串: '${getString(R.string.function_menu)}'")
        Log.e(TAG, "读取settings字符串: '${getString(R.string.settings)}'")
        Log.e(TAG, "读取history字符串: '${getString(R.string.history)}'")
        
        // 检查资源配置
        val config = this.resources.configuration
        Log.e(TAG, "Resources Configuration locale: ${config.locales.get(0)}")
        Log.e(TAG, "Resources Configuration: $config")
        Log.e(TAG, "=============================")
        
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
        
        // Initialize new UI elements for storage permission
        // tvStoragePermissionStatus = binding.tvStoragePermissionStatus
        // btnRequestStoragePermission = binding.btnRequestStoragePermission

        // 首次打开时刷新悬浮窗状态
        updateFloatingServiceStatus()
        

        
        // 注册广播接收器
        registerReceiver(overlayPermissionReceiver, IntentFilter("com.readassist.OVERLAY_PERMISSION_DENIED"))
    }
    
    override fun onResume() {
        super.onResume()
        Log.e(TAG, "MainActivity.onResume() 被调用，开始检查权限状态")
        
        // 直接检查并记录存储权限状态
        val storageStatus = PermissionUtils.hasStoragePermissions(this)
        Log.e(TAG, "存储权限状态: ${storageStatus.allGranted}, 缺失权限: ${storageStatus.missingPermissions}")
        
        // 每次回到前台都检查状态
        viewModel.checkPermissions()
        viewModel.checkApiKey()
        
        // 更新悬浮窗服务状态
        updateFloatingServiceStatus()
        
        // 通知悬浮窗服务重新检查截屏权限（如果服务正在运行）
        if (isFloatingWindowServiceRunning()) {
            // 通过广播通知悬浮窗服务权限状态可能已变化
            val intent = Intent("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
            sendBroadcast(intent)
        }
        
        // 强制刷新权限UI显示 - 通过ViewModel触发Observer更新
        viewModel.checkPermissions()
    }
    
    /**
     * 设置观察者
     */
    private fun setupObservers() {
        // 权限状态观察
        viewModel.permissionStatus.observe(this, Observer { status ->
            updatePermissionStatus(status)
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
        // binding.btnPermissions.setOnClickListener {
        //     requestPermissions()
        // }
        
        // API Key 设置按钮
        binding.btnApiKey.setOnClickListener {
            android.util.Log.d("MainActivity", "API Key 按钮被点击")
            
            if (!app.preferenceManager.isAiSetupCompleted()) {
                android.util.Log.d("MainActivity", "AI配置未完成，显示设置向导")
                showAiSetupWizard()
            } else {
                android.util.Log.d("MainActivity", "AI配置已完成，跳转到设置页面")
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
            startActivity(Intent(this, com.readassist.ui.HistoryActivity::class.java))
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
        
        binding.btnRequestStoragePermission.setOnClickListener {
            viewModel.requestStoragePermissions(this)
        }
        
        // 添加截屏自动弹窗开关监听
        binding.switchScreenshotAutoPopup.isChecked = app.preferenceManager.getBoolean("screenshot_auto_popup", true)
        binding.switchScreenshotAutoPopup.setOnCheckedChangeListener { _, isChecked ->
            app.preferenceManager.setBoolean("screenshot_auto_popup", isChecked)
            Log.d(TAG, "截屏自动弹窗设置已${if (isChecked) "开启" else "关闭"}")
            val message = if (isChecked) getString(R.string.screenshot_auto_popup_enabled) else getString(R.string.screenshot_auto_popup_disabled)
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 检查首次启动
     */
    private fun checkFirstLaunch() {
        val app = application as com.readassist.ReadAssistApplication
        
        // 只有在真正的首次启动时才显示欢迎对话框
        if (app.preferenceManager.isFirstLaunch()) {
            showWelcomeDialog()
            app.preferenceManager.setFirstLaunch(false)
        } else if (!app.preferenceManager.isAiSetupCompleted()) {
            // 如果不是首次启动，但AI配置未完成，只在特定情况下显示设置向导
            // 避免每次权限设置返回都重新显示
            android.util.Log.d("MainActivity", "检测到AI配置未完成，但不是首次启动")
            // 可以在这里添加一个标志，避免频繁显示设置向导
        }
    }
    
    /**
     * 显示欢迎对话框
     */
    private fun showWelcomeDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.welcome_title))
            .setMessage(getString(R.string.welcome_message))
            .setPositiveButton(getString(R.string.start_configuration)) { _, _ ->
                if (!app.preferenceManager.isAiSetupCompleted()) {
                    try {
                        showAiSetupWizard()
                    } catch (e: Exception) {
                        android.util.Log.e("MainActivity", "启动设置向导失败，转到设置页面", e)
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                } else {
                    // requestPermissions()
                }
            }
            .setNegativeButton(getString(R.string.setup_later), null)
            .show()
    }
    
    /**
     * 请求权限
     */
    private fun requestPermissions() {
        permissionChecker.checkAndRequestPermissions(object : PermissionUtils.PermissionCallback {
            override fun onPermissionGranted() {
                showMessage(getString(R.string.all_permissions_granted))
                viewModel.checkPermissions()
            }
            
            override fun onPermissionDenied(missingPermissions: List<String>) {
                val message = getString(R.string.missing_permissions, missingPermissions.joinToString(", "))
                showMessage(message)
            }
        })
    }
    
    /**
     * 显示AI设置向导 - 墨水屏优化版本
     */
    private fun showAiSetupWizard() {
        // 首先检查AI配置是否已完成，如果已完成，则不应再显示此向导
        if (app.preferenceManager.isAiSetupCompleted()) {
            android.util.Log.d("MainActivity", "AI配置已完成，跳过设置向导。")
            return 
        }

        try {
            android.util.Log.d("MainActivity", "=== showAiSetupWizard 墨水屏优化版本 ===")
            
            val options = arrayOf(
                getString(R.string.option_google_gemini),
                getString(R.string.option_siliconflow)
            )
            
            val adapter = android.widget.ArrayAdapter<String>(
                this,
                android.R.layout.simple_list_item_1,
                options
            )
            
            val listView = android.widget.ListView(this).apply {
                this.adapter = adapter
                setPadding(24, 16, 24, 16)
                setBackgroundColor(0xFFFFFFFF.toInt())
                dividerHeight = 1
                setDivider(android.graphics.drawable.ColorDrawable(0xFFCCCCCC.toInt()))
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            lateinit var platformDialog: android.app.AlertDialog // 声明以便后续dismiss

            listView.setOnItemClickListener { _, _, position, _ ->
                android.util.Log.d("MainActivity", "用户点击了位置 $position: ${options[position]}")
                platformDialog.dismiss() // 点击后先关闭平台选择对话框

                when (position) {
                    0 -> {
                        android.util.Log.d("MainActivity", "选择了Gemini")
                        showMessage(getString(R.string.selected_gemini))
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            showGeminiSetupDialog()
                        }, 300)
                    }
                    1 -> {
                        android.util.Log.d("MainActivity", "选择了SiliconFlow")
                        showMessage(getString(R.string.selected_siliconflow))
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            showSiliconFlowSetupDialog()
                        }, 300)
                    }
                }
            }
            
            platformDialog = android.app.AlertDialog.Builder(this)
                .setTitle("🔧 ${getString(R.string.setup_wizard_title)}")
                .setMessage(getString(R.string.setup_wizard_message))
                .setView(listView)
                .setNegativeButton("❌ ${getString(R.string.setup_wizard_cancel)}") { dialog, _ ->
                    android.util.Log.d("MainActivity", "用户取消设置")
                    dialog.dismiss()
                }
                .setCancelable(true)
                .create()
            
            platformDialog.show()
            
            platformDialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            
            android.util.Log.d("MainActivity", "✅ 墨水屏优化对话框已显示")
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "❌ showAiSetupWizard失败", e)
            showMessage(getString(R.string.setup_failed_fallback))
            showPlatformSelectionFallback()
        }
    }
    
    /**
     * 备选方案：使用简单的选择菜单
     */
    private fun showPlatformSelectionFallback() {
        try {
            val menu = android.widget.PopupMenu(this, findViewById(android.R.id.content))
            menu.menu.add(0, 1, 1, "🤖 ${getString(R.string.option_google_gemini).removePrefix("• ")}")
            menu.menu.add(0, 2, 2, "⚡ ${getString(R.string.option_siliconflow).removePrefix("• ")}")
            menu.menu.add(0, 3, 3, getString(R.string.option_manual_setup))
            
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        showMessage(getString(R.string.selected_gemini))
                        showGeminiSetupDialog()
                        true
                    }
                    2 -> {
                        showMessage(getString(R.string.selected_siliconflow))
                        showSiliconFlowSetupDialog()
                        true
                    }
                    3 -> {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        true
                    }
                    else -> false
                }
            }
            
            menu.show()
            
        } catch (e: Exception) {
            // 最后的备选方案
            android.util.Log.e("MainActivity", "备选方案也失败，直接跳转设置页面", e)
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
    
    /**
     * 显示Gemini设置对话框
     */
    private fun showGeminiSetupDialog() {
        val platform = com.readassist.model.AiPlatform.GEMINI
        val input = android.widget.EditText(this).apply {
                            hint = getString(R.string.gemini_api_key_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        // 声明apiKeyDialog变量以便在PositiveButton中dismiss
        lateinit var apiKeyDialog: AlertDialog

        apiKeyDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.gemini_setup_title))
            .setMessage(getString(R.string.gemini_setup_message))
            .setView(input)
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    if (apiKey.startsWith("AIza")) {
                        app.preferenceManager.setApiKey(platform, apiKey)
                        app.preferenceManager.setCurrentAiPlatform(platform)
                        val defaultModel = com.readassist.model.AiModel.getDefaultModelForPlatform(platform)
                        if (defaultModel != null) {
                            app.preferenceManager.setCurrentAiModel(defaultModel.id)
                        }
                        app.preferenceManager.setAiSetupCompleted(true)
                        
                        apiKeyDialog.dismiss() // 关闭API Key输入对话框
                        showMessage(getString(R.string.platform_config_success, "Gemini"))
                        viewModel.checkApiKey()
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            requestPermissions()
                        }, 500)
                    } else {
                        showMessage(getString(R.string.platform_api_key_invalid, "Gemini"))
                        showGeminiSetupDialog() // 重新显示当前对话框
                    }
                } else {
                    showMessage(getString(R.string.please_enter_api_key))
                    showGeminiSetupDialog() // 重新显示当前对话框
                }
            }
            .setNegativeButton(getString(R.string.back_button)) { dialog, _ ->
                dialog.dismiss()
                showAiSetupWizard() // 返回平台选择
            }
            .setNeutralButton(getString(R.string.apply_key_button)) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))
                    startActivity(intent)
                    // 用户去申请Key，当前对话框保留，回来后可以继续输入
                } catch (e: Exception) {
                    showMessage(getString(R.string.cannot_open_browser))
                }
            }
            .setCancelable(false)
            .show()
    }
    
    /**
     * 显示SiliconFlow设置对话框
     */
    private fun showSiliconFlowSetupDialog() {
        val platform = com.readassist.model.AiPlatform.SILICONFLOW
        val input = android.widget.EditText(this).apply {
                            hint = getString(R.string.siliconflow_api_key_hint)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        lateinit var apiKeyDialog: AlertDialog // 声明以便后续dismiss

        apiKeyDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.siliconflow_setup_title))
            .setMessage(getString(R.string.siliconflow_setup_message))
            .setView(input)
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    if (apiKey.startsWith("sk-")) {
                        app.preferenceManager.setApiKey(platform, apiKey)
                        app.preferenceManager.setCurrentAiPlatform(platform)
                        val defaultModel = com.readassist.model.AiModel.getDefaultModelForPlatform(platform)
                        if (defaultModel != null) {
                            app.preferenceManager.setCurrentAiModel(defaultModel.id)
                        }
                        app.preferenceManager.setAiSetupCompleted(true)

                        apiKeyDialog.dismiss() // 关闭API Key输入对话框
                        showMessage(getString(R.string.platform_config_success, "SiliconFlow"))
                        viewModel.checkApiKey()
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            requestPermissions()
                        }, 500)
                    } else {
                        showMessage(getString(R.string.platform_api_key_invalid, "SiliconFlow"))
                        showSiliconFlowSetupDialog() // 重新显示当前对话框
                    }
                } else {
                    showMessage(getString(R.string.please_enter_api_key))
                    showSiliconFlowSetupDialog() // 重新显示当前对话框
                }
            }
            .setNegativeButton(getString(R.string.back_button)) { dialog, _ ->
                dialog.dismiss()
                showAiSetupWizard() // 返回平台选择
            }
            .setNeutralButton(getString(R.string.apply_key_button)) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://siliconflow.com"))
                    startActivity(intent)
                    // 用户去申请Key，当前对话框保留，回来后可以继续输入
                } catch (e: Exception) {
                    showMessage(getString(R.string.cannot_open_browser))
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
            .setTitle(getString(R.string.clear_all_data_title))
            .setMessage(getString(R.string.clear_all_data_message))
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                viewModel.clearAllData()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    
    /**
     * 更新权限状态显示 - 改进截屏权限说明
     */
    private fun updatePermissionStatus(status: MainViewModel.PermissionStates) {
        Log.d(TAG, "更新权限状态显示")

        val overlayGranted = PermissionUtils.hasOverlayPermission(this)
        val accessibilityGranted = PermissionUtils.hasAccessibilityPermission(this)
        val storageStatus = PermissionUtils.hasStoragePermissions(this)
        val screenshotGranted = app.preferenceManager.isScreenshotPermissionGranted()
        val floatingServiceRunning = isFloatingWindowServiceRunning()

        // 获取通用设备截屏目录管理器
        val deviceScreenshotManager = com.readassist.utils.DeviceScreenshotManager(this, app.preferenceManager)
        val currentDeviceConfig = deviceScreenshotManager.getCurrentDeviceConfig()
        val hasCurrentDeviceAccess = deviceScreenshotManager.hasDirectoryAccess(currentDeviceConfig)
        
        // 存储权限状态显示 - 墨水屏优化，统一使用黑色文字
        if (storageStatus.allGranted) {
            binding.tvStoragePermissionStatus.text = getString(R.string.storage_permission_granted_external)
            binding.tvStoragePermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestStoragePermission.visibility = View.GONE
        } else {
            val missingPerms = storageStatus.missingPermissions.joinToString(", ") { 
                when(it) {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储"
                    else -> it
                }
            }
            binding.tvStoragePermissionStatus.text = getString(R.string.storage_permission_missing, missingPerms)
            binding.tvStoragePermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestStoragePermission.visibility = View.VISIBLE
            binding.btnRequestStoragePermission.text = getString(R.string.request_storage_permission)
            binding.btnRequestStoragePermission.setOnClickListener {
                viewModel.requestStoragePermissions(this@MainActivity)
            }
        }

        // 通用设备截屏文件目录权限状态显示 - 增强版
        binding.tvIReaderDirectoryStatus.visibility = View.VISIBLE
        binding.btnRequestIReaderDirectory.visibility = View.VISIBLE
        
        // 检查各个目录的权限状态
        val deviceType = com.readassist.utils.DeviceUtils.getDeviceType()
        val directoryStatusText = when (deviceType) {
            com.readassist.utils.DeviceType.SUPERNOTE -> {
                // 检查SAF权限而不是直接文件系统访问
                val deviceScreenshotManager = DeviceScreenshotManager(this, app.preferenceManager)
                val config = deviceScreenshotManager.getCurrentDeviceConfig()
                val hasSafAccess = deviceScreenshotManager.hasDirectoryAccess(config)
                
                // 添加调试日志
                Log.d(TAG, "Supernote目录权限检查: config=${config.displayName}, hasSafAccess=$hasSafAccess")
                Log.d(TAG, "Supernote配置详情: systemPath=${config.systemPath}, safPrefKey=${config.safPrefKey}")
                val savedUri = app.preferenceManager.getString(config.safPrefKey, "")
                Log.d(TAG, "Supernote保存的URI: $savedUri")
                
                if (hasSafAccess) {
                    getString(R.string.supernote_screenshot_dir_authorized)
                } else {
                    getString(R.string.supernote_screenshot_dir_not_authorized)
                }
            }
            com.readassist.utils.DeviceType.IREADER -> {
                if (hasCurrentDeviceAccess) {
                    getString(R.string.ireader_screenshot_dir_authorized, currentDeviceConfig.systemPath)
                } else {
                    getString(R.string.ireader_screenshot_dir_not_authorized, currentDeviceConfig.systemPath)
                }
            }
            else -> {
                val screenshotDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "Screenshots")
                val canWrite = try {
                    screenshotDir.exists() || screenshotDir.mkdirs()
                    val testFile = java.io.File(screenshotDir, ".test_write_permission")
                    testFile.createNewFile() && testFile.delete()
                } catch (e: Exception) {
                    false
                }
                
                if (canWrite) {
                    "通用截屏目录：✅ 可写入 (${screenshotDir.absolutePath})"
                } else {
                    "通用截屏目录：❌ 无写入权限 (${screenshotDir.absolutePath})"
                }
            }
        }
        
        binding.tvIReaderDirectoryStatus.text = directoryStatusText
        binding.tvIReaderDirectoryStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
        
        // 根据权限状态决定按钮显示
        val hasDirectoryAccess = when (deviceType) {
            com.readassist.utils.DeviceType.SUPERNOTE -> {
                directoryStatusText.contains("✅")
            }
            com.readassist.utils.DeviceType.IREADER -> {
                hasCurrentDeviceAccess
            }
            else -> {
                directoryStatusText.contains("✅")
            }
        }
        
        if (hasDirectoryAccess) {
            // 已授权：隐藏按钮
            binding.btnRequestIReaderDirectory.visibility = View.GONE
        } else {
            // 未授权：显示授权按钮
            binding.btnRequestIReaderDirectory.visibility = View.VISIBLE
            binding.btnRequestIReaderDirectory.text = getString(R.string.start_directory_authorization)
        }
        
        binding.btnRequestIReaderDirectory.setOnClickListener {
            // 根据设备类型和权限状态决定跳转逻辑
            val deviceType = com.readassist.utils.DeviceUtils.getDeviceType()
            
            when (deviceType) {
                com.readassist.utils.DeviceType.SUPERNOTE -> {
                    // Supernote设备：直接启动SAF授权
                    requestSupernoteDirectoryAccess()
                }
                com.readassist.utils.DeviceType.IREADER -> {
                    // iReader设备：检查是否需要授权或配置
                    if (hasCurrentDeviceAccess) {
                        // 已有权限，可能需要重新配置
                        startActivity(Intent(this@MainActivity, com.readassist.ui.DeviceSetupActivity::class.java))
                    } else {
                        // 需要权限：启动目录选择
                        requestIReaderDirectoryAccess()
                    }
                }
                else -> {
                    // 通用设备：启动设备配置Activity
                    startActivity(Intent(this@MainActivity, com.readassist.ui.DeviceSetupActivity::class.java))
                }
            }
        }
            
        // 根据设备类型显示不同的截屏权限说明
        if (com.readassist.utils.DeviceUtils.isIReaderDevice()) {
            // 掌阅设备特殊说明
                            binding.tvScreenshotStatus.text = getString(R.string.screenshot_permission_ireader_tip)
            binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnScreenshotPermission.visibility = View.GONE
        } else {
            
            // 非掌阅设备的截屏权限显示 - 简化权限检查逻辑
            if (screenshotGranted) {
                // 简化验证：只检查基本权限标志和resultCode
                val resultCode = app.preferenceManager.getScreenshotResultCode()
                
                // 添加调试日志
                Log.d(TAG, "截屏权限检查: screenshotGranted=$screenshotGranted, resultCode=$resultCode")
                
                // MediaProjection的RESULT_OK是-1，只检查这个核心条件
                if (resultCode == -1) {
                    binding.tvScreenshotStatus.text = getString(R.string.screenshot_permission_granted_restart)
                    binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                    binding.btnScreenshotPermission.visibility = View.VISIBLE
                    binding.btnScreenshotPermission.text = getString(R.string.reauthorize)
                } else {
                    binding.tvScreenshotStatus.text = getString(R.string.screenshot_permission_expired)
                    binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                    binding.btnScreenshotPermission.visibility = View.VISIBLE
                    binding.btnScreenshotPermission.text = getString(R.string.grant_screenshot_permission_btn)
                }
            } else {
                binding.tvScreenshotStatus.text = getString(R.string.screenshot_permission_not_granted)
                binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                binding.btnScreenshotPermission.visibility = View.VISIBLE
                binding.btnScreenshotPermission.text = getString(R.string.grant_screenshot_permission_btn)
            }
        }

        // 无障碍服务权限 - 统一使用黑色文字
        if (accessibilityGranted) {
            binding.tvAccessibilityPermissionStatus.text = getString(R.string.accessibility_permission_granted)
            binding.tvAccessibilityPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestAccessibilityPermission.visibility = View.GONE
        } else {
            binding.tvAccessibilityPermissionStatus.text = getString(R.string.accessibility_permission_not_granted)
            binding.tvAccessibilityPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestAccessibilityPermission.visibility = View.VISIBLE
            binding.btnRequestAccessibilityPermission.text = getString(R.string.grant_accessibility_permission_btn)
            binding.btnRequestAccessibilityPermission.setOnClickListener {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        }

        // Log the final state of UI elements for debugging
        Log.d(TAG, "tvScreenshotStatus: ${binding.tvScreenshotStatus.text}")
        Log.d(TAG, "btnScreenshotPermission visible: ${binding.btnScreenshotPermission.visibility == View.VISIBLE}")
        Log.d(TAG, "tvStoragePermissionStatus: ${binding.tvStoragePermissionStatus.text}")
        Log.d(TAG, "btnRequestStoragePermission visible: ${binding.btnRequestStoragePermission.visibility == View.VISIBLE}")
        if (DeviceUtils.isIReaderDevice()) {
            Log.d(TAG, "tvIReaderDirectoryStatus: ${binding.tvIReaderDirectoryStatus.text}")
            Log.d(TAG, "btnRequestIReaderDirectory visible: ${binding.btnRequestIReaderDirectory.visibility == View.VISIBLE}")
        }
    }
    
    /**
     * 更新 API Key 状态显示 - 墨水屏优化，统一使用黑色文字
     */
    private fun updateApiKeyStatus(hasKey: Boolean) {
        val app = application as com.readassist.ReadAssistApplication
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentModel = app.preferenceManager.getCurrentAiModel()
        val isConfigured = app.preferenceManager.isCurrentConfigurationValid()
        
        if (isConfigured && hasKey && currentModel != null) {
            binding.tvApiKeyStatus.text = "✓ ${currentPlatform.displayName} - ${currentModel.displayName}"
            binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnApiKey.text = getString(R.string.reconfigure)
        } else if (app.preferenceManager.isAiSetupCompleted()) {
            binding.tvApiKeyStatus.text = getString(R.string.configuration_incomplete)
            binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnApiKey.text = getString(R.string.fix_configuration)
        } else {
            binding.tvApiKeyStatus.text = getString(R.string.api_service_not_configured)
            binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnApiKey.text = getString(R.string.start_configuration)
        }
    }
    
    /**
     * 更新统计信息显示
     */
    private fun updateStatistics(stats: com.readassist.repository.ChatStatistics) {
        binding.tvStatistics.text = getString(R.string.message_and_session_stats, stats.totalMessages, stats.totalSessions)
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
        val isServiceRunning = isFloatingWindowServiceRunning()
        if (isServiceRunning) {
            stopService(Intent(this, FloatingWindowServiceNew::class.java))
            showMessage(getString(R.string.turn_off_floating_button))
        } else {
            if (PermissionUtils.hasOverlayPermission(this)) {
                startFloatingWindowService()
            } else {
                showMessage(getString(R.string.grant_overlay_permission))
                requestPermissions()
            }
        }
        updateFloatingServiceStatus()
    }
    
    /**
     * 启动悬浮窗服务
     */
    private fun startFloatingWindowService() {
        val intent = Intent(this, FloatingWindowServiceNew::class.java)
        startForegroundService(intent)
        showMessage(getString(R.string.floating_service_started))
    }
    
    /**
     * 检查悬浮窗服务是否运行
     */
    private fun isFloatingWindowServiceRunning(): Boolean {
        val manager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (FloatingWindowServiceNew::class.java.name == service.service.className) {
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
            showMessage(getString(R.string.screenshot_permission_granted_simple))
            return
        }
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_title_screenshot))
            .setMessage(getString(R.string.permission_message_screenshot))
            .setPositiveButton(getString(R.string.grant_permission_label)) { _, _ ->
                val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                screenshotPermissionLauncher.launch(intent)
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
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
     * 更新悬浮窗服务状态显示 - 墨水屏优化，统一使用黑色文字
     */
    private fun updateFloatingServiceStatus() {
        val isServiceRunning = isFloatingWindowServiceRunning()
        if (isServiceRunning) {
            binding.tvFloatingWindowStatus.text = getString(R.string.floating_button_running)
            binding.tvFloatingWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnFloatingWindow.text = getString(R.string.stop_floating_button)
        } else {
            binding.tvFloatingWindowStatus.text = getString(R.string.floating_button_stopped)
            binding.tvFloatingWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnFloatingWindow.text = getString(R.string.turn_on_floating_button)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Pass the results to PermissionChecker to handle and invoke the original callback
        permissionChecker.handleRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // The original callback in checkAndRequestPermissions will call viewModel.checkPermissions()
        // However, it's also safe to call it here to ensure UI is updated promptly, 
        // especially if the callback logic in PermissionChecker becomes complex.
        // For now, we rely on the callback via PermissionChecker.

        // Specific handling for storage can also be done here if needed, 
        // but PermissionChecker should ideally consolidate this.
        // Example: (This might be redundant if PermissionChecker handles it via callback)
        if (requestCode == PermissionUtils.REQUEST_CODE_STORAGE_PERMISSION) {
            viewModel.checkPermissions() // Re-check permissions to update UI based on this specific request code
            // Toast messages for direct feedback can also be here if desired
            // if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            //     Toast.makeText(this, "存储权限已授予 (MainActivity)", Toast.LENGTH_SHORT).show()
            // } else {
            //     Toast.makeText(this, "存储权限被拒绝 (MainActivity)", Toast.LENGTH_SHORT).show()
            // }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Add specific handling for storage if PermissionChecker doesn't cover it well enough or for direct calls
        if (requestCode == PermissionUtils.REQUEST_CODE_STORAGE_PERMISSION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                // 保存权限状态和数据到偏好设置
                app.preferenceManager.setScreenshotPermissionGranted(true)
                app.preferenceManager.setScreenshotPermissionData(
                    resultCode,
                    data.toUri(0)
                )
                
                // 启动截屏服务并传递权限数据
                val intent = Intent(this, ScreenshotService::class.java).apply {
                    action = ScreenshotService.ACTION_START_SCREENSHOT
                    putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(ScreenshotService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(intent)
                
                showMessage(getString(R.string.screenshot_permission_granted_simple))
                updateFloatingServiceStatus()
            } else {
                app.preferenceManager.setScreenshotPermissionGranted(false)
                showMessage(getString(R.string.screenshot_permission_denied_simple))
                updateFloatingServiceStatus()
            }
        }
    }





    private fun isIReaderX3Pro(): Boolean {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        return manufacturer.contains("ireader") || manufacturer.contains("掌阅") || model.contains("x3 pro") || model.contains("x3pro")
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.overlay_permission_title))
            .setMessage(getString(R.string.overlay_permission_message))
            .setPositiveButton(getString(R.string.go_to_settings)) { _, _ ->
                // 跳转到悬浮窗权限设置页面
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }



    /**
     * 请求iReader设备的截屏目录访问权限
     */
    private fun requestIReaderDirectoryAccess() {
        val deviceScreenshotManager = DeviceScreenshotManager(this, app.preferenceManager)
        val config = deviceScreenshotManager.getCurrentDeviceConfig()
        
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.ireader_directory_auth_title))
            .setMessage(getString(R.string.ireader_directory_auth_message, config.systemPath))
            .setPositiveButton(getString(R.string.grant_access_button)) { _, _ ->
                try {
                    // 使用Intent.ACTION_OPEN_DOCUMENT_TREE直接创建授权Intent
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    safDirectoryLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "启动SAF授权失败", e)
                    showMessage(getString(R.string.authorization_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(getString(R.string.cancel_button_x), null)
            .show()
    }

    /**
     * 请求Supernote设备的截屏目录访问权限
     */
    private fun requestSupernoteDirectoryAccess() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.supernote_directory_auth_title))
            .setMessage(getString(R.string.supernote_directory_auth_message))
            .setPositiveButton(getString(R.string.grant_access_button)) { _, _ ->
                try {
                    val deviceScreenshotManager = DeviceScreenshotManager(this, app.preferenceManager)
                    val config = deviceScreenshotManager.getCurrentDeviceConfig()
                    
                    // 使用Intent.ACTION_OPEN_DOCUMENT_TREE直接创建授权Intent
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            // 尝试指向SCREENSHOT目录
                            val uri = android.provider.DocumentsContract.buildDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:SCREENSHOT"
                            )
                            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                        }
                    }
                    
                    safDirectoryLauncher.launch(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "启动SAF授权失败", e)
                    showMessage(getString(R.string.authorization_failed, e.message ?: ""))
                }
            }
            .setNegativeButton(getString(R.string.cancel_button_x), null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        unregisterReceiver(overlayPermissionReceiver)
    }
} 