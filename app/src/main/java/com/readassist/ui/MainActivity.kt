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

class MainActivity : AppCompatActivity() {
    
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
            
            showMessage("截屏权限已授予，截屏功能已启用")
            updateFloatingServiceStatus()
        } else {
            app.preferenceManager.setScreenshotPermissionGranted(false)
            showMessage("截屏权限被拒绝，截屏功能将无法使用")
            updateFloatingServiceStatus()
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
            Toast.makeText(this, "截屏自动弹窗已${if (isChecked) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
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
            .setTitle("欢迎使用 ReadAssist")
            .setMessage("ReadAssist 是专为超级笔记、掌阅等电纸书设计的智能阅读助手。\n\n首次使用需要配置AI服务：\n\n1. 选择AI平台（Gemini 或 SiliconFlow）\n2. 申请并配置对应的API Key\n3. 授权必要权限（无障碍服务、存储和截屏权限）\n4. 开始智能阅读！\n\n点击\"开始配置\"进入设置向导。")
            .setPositiveButton("开始配置") { _, _ ->
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
            }
            
            override fun onPermissionDenied(missingPermissions: List<String>) {
                val message = "缺少权限：${missingPermissions.joinToString(", ")}"
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
                "• Google Gemini",
                "• SiliconFlow" 
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
                        showMessage("✅ 已选择 Google Gemini")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            showGeminiSetupDialog()
                        }, 300)
                    }
                    1 -> {
                        android.util.Log.d("MainActivity", "选择了SiliconFlow")
                        showMessage("✅ 已选择 SiliconFlow")
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            showSiliconFlowSetupDialog()
                        }, 300)
                    }
                }
            }
            
            platformDialog = android.app.AlertDialog.Builder(this)
                .setTitle("🔧 选择AI平台")
                .setMessage("请选择要使用的AI服务：")
                .setView(listView)
                .setNegativeButton("❌ 取消") { dialog, _ ->
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
            showMessage("❌ 显示设置失败，转到手动设置")
            showPlatformSelectionFallback()
        }
    }
    
    /**
     * 备选方案：使用简单的选择菜单
     */
    private fun showPlatformSelectionFallback() {
        try {
            val menu = android.widget.PopupMenu(this, findViewById(android.R.id.content))
            menu.menu.add(0, 1, 1, "🤖 Google Gemini")
            menu.menu.add(0, 2, 2, "⚡ SiliconFlow")
            menu.menu.add(0, 3, 3, "⚙️ 手动设置")
            
            menu.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        showMessage("✅ 已选择 Google Gemini")
                        showGeminiSetupDialog()
                        true
                    }
                    2 -> {
                        showMessage("✅ 已选择 SiliconFlow")
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
            hint = "请输入 Gemini API Key (以 AIza 开头)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        // 声明apiKeyDialog变量以便在PositiveButton中dismiss
        lateinit var apiKeyDialog: AlertDialog

        apiKeyDialog = AlertDialog.Builder(this)
            .setTitle("配置 Google Gemini")
            .setMessage("Gemini API Key 申请地址：\nhttps://aistudio.google.com/apikey\n\n请将您的API Key输入到下方：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
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
                        showMessage("✅ Gemini 配置成功！现在进行权限设置...")
                        viewModel.checkApiKey()
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            requestPermissions()
                        }, 500)
                    } else {
                        showMessage("❌ Gemini API Key 格式不正确")
                        showGeminiSetupDialog() // 重新显示当前对话框
                    }
                } else {
                    showMessage("请输入API Key")
                    showGeminiSetupDialog() // 重新显示当前对话框
                }
            }
            .setNegativeButton("返回") { dialog, _ ->
                dialog.dismiss()
                showAiSetupWizard() // 返回平台选择
            }
            .setNeutralButton("申请Key") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey"))
                    startActivity(intent)
                    // 用户去申请Key，当前对话框保留，回来后可以继续输入
                } catch (e: Exception) {
                    showMessage("无法打开浏览器")
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
            hint = "请输入 SiliconFlow API Key (以 sk- 开头)"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        lateinit var apiKeyDialog: AlertDialog // 声明以便后续dismiss

        apiKeyDialog = AlertDialog.Builder(this)
            .setTitle("配置 SiliconFlow")
            .setMessage("SiliconFlow API Key 申请地址：\nhttps://siliconflow.com\n\n请将您的API Key输入到下方：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
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
                        showMessage("✅ SiliconFlow 配置成功！现在进行权限设置...")
                        viewModel.checkApiKey()
                        
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            requestPermissions()
                        }, 500)
                    } else {
                        showMessage("❌ SiliconFlow API Key 格式不正确")
                        showSiliconFlowSetupDialog() // 重新显示当前对话框
                    }
                } else {
                    showMessage("请输入API Key")
                    showSiliconFlowSetupDialog() // 重新显示当前对话框
                }
            }
            .setNegativeButton("返回") { dialog, _ ->
                dialog.dismiss()
                showAiSetupWizard() // 返回平台选择
            }
            .setNeutralButton("申请Key") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://siliconflow.com"))
                    startActivity(intent)
                    // 用户去申请Key，当前对话框保留，回来后可以继续输入
                } catch (e: Exception) {
                    showMessage("无法打开浏览器")
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
     * 更新权限状态显示 - 改进截屏权限说明
     */
    private fun updatePermissionStatus(status: MainViewModel.PermissionStates) {
        Log.d(TAG, "更新权限状态显示")

        val overlayGranted = PermissionUtils.hasOverlayPermission(this)
        val accessibilityGranted = PermissionUtils.hasAccessibilityPermission(this)
        val storageStatus = PermissionUtils.hasStoragePermissions(this)
        val screenshotGranted = app.preferenceManager.isScreenshotPermissionGranted()
        val floatingServiceRunning = isFloatingWindowServiceRunning()

        // 获取StorageAccessManager来检查掌阅截屏目录权限
        val storageAccessManager = com.readassist.utils.StorageAccessManager(this, app.preferenceManager)
        val isIReaderDevice = com.readassist.utils.DeviceUtils.isIReaderDevice()
        val hasIReaderDirectoryAccess = if (isIReaderDevice) storageAccessManager.hasIReaderDirectoryAccess() else true
        
        // 存储权限状态显示 - 墨水屏优化，统一使用黑色文字
        if (storageStatus.allGranted) {
            binding.tvStoragePermissionStatus.text = "存储权限：已授予（读写外部存储）"
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
            binding.tvStoragePermissionStatus.text = "存储权限：缺少 ($missingPerms)"
            binding.tvStoragePermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestStoragePermission.visibility = View.VISIBLE
            binding.btnRequestStoragePermission.text = "授予存储权限"
            binding.btnRequestStoragePermission.setOnClickListener {
                viewModel.requestStoragePermissions(this@MainActivity)
            }
        }

        // 掌阅设备截屏文件目录权限状态显示（仅掌阅设备显示）
        if (isIReaderDevice) {
            // 显示截屏文件目录权限状态
            binding.tvIReaderDirectoryStatus.visibility = View.VISIBLE
            binding.btnRequestIReaderDirectory.visibility = View.VISIBLE
            
            if (hasIReaderDirectoryAccess) {
                binding.tvIReaderDirectoryStatus.text = "掌阅截屏文件目录：已授予"
                binding.tvIReaderDirectoryStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                binding.btnRequestIReaderDirectory.visibility = View.GONE
            } else {
                binding.tvIReaderDirectoryStatus.text = "掌阅截屏文件目录：未授予"
                binding.tvIReaderDirectoryStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                binding.btnRequestIReaderDirectory.visibility = View.VISIBLE
                binding.btnRequestIReaderDirectory.text = "配置目录权限"
                binding.btnRequestIReaderDirectory.setOnClickListener {
                    // 启动掌阅设备配置Activity
                    startActivity(Intent(this@MainActivity, com.readassist.ui.IReaderSetupActivity::class.java))
                }
            }
            
            // 如果是掌阅设备，修改截屏权限提示
            binding.tvScreenshotStatus.text = "截屏权限：您的设备是掌阅公司的，建议将按键长按设置为截屏，使用会更便捷"
            binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnScreenshotPermission.visibility = View.GONE
        } else {
            // 非掌阅设备，隐藏掌阅特定的权限显示
            binding.tvIReaderDirectoryStatus.visibility = View.GONE
            binding.btnRequestIReaderDirectory.visibility = View.GONE
            
            // 非掌阅设备的截屏权限显示 - 改进说明
            if (screenshotGranted) {
                // 验证权限是否真正有效
                val resultCode = app.preferenceManager.getScreenshotResultCode()
                val resultDataUri = app.preferenceManager.getScreenshotResultDataUri()
                
                if (resultCode != -1 && resultDataUri != null) {
                    binding.tvScreenshotStatus.text = "截屏权限：已授予（重启后需重新授权）"
                    binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                    binding.btnScreenshotPermission.visibility = View.VISIBLE
                    binding.btnScreenshotPermission.text = "重新授权"
                } else {
                    binding.tvScreenshotStatus.text = "截屏权限：已失效（请重新授权）"
                    binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                    binding.btnScreenshotPermission.visibility = View.VISIBLE
                    binding.btnScreenshotPermission.text = "授权截屏权限"
                }
            } else {
                binding.tvScreenshotStatus.text = "截屏权限：未授予"
                binding.tvScreenshotStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
                binding.btnScreenshotPermission.visibility = View.VISIBLE
                binding.btnScreenshotPermission.text = "授权截屏权限"
            }
        }

        // 无障碍服务权限 - 统一使用黑色文字
        if (accessibilityGranted) {
            binding.tvAccessibilityPermissionStatus.text = "无障碍服务权限：已授予"
            binding.tvAccessibilityPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestAccessibilityPermission.visibility = View.GONE
        } else {
            binding.tvAccessibilityPermissionStatus.text = "无障碍服务权限：未授权"
            binding.tvAccessibilityPermissionStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnRequestAccessibilityPermission.visibility = View.VISIBLE
            binding.btnRequestAccessibilityPermission.text = "授予无障碍权限"
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
        if (isIReaderDevice) {
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
            binding.btnApiKey.text = "重新配置"
        } else if (app.preferenceManager.isAiSetupCompleted()) {
            binding.tvApiKeyStatus.text = "⚠ 配置不完整或无效"
            binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnApiKey.text = "修复配置"
        } else {
            binding.tvApiKeyStatus.text = "❌ 未配置AI服务"
            binding.tvApiKeyStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
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
        val isServiceRunning = isFloatingWindowServiceRunning()
        if (isServiceRunning) {
            stopService(Intent(this, FloatingWindowServiceNew::class.java))
            showMessage("悬浮按钮已停止")
        } else {
            if (PermissionUtils.hasOverlayPermission(this)) {
                startFloatingWindowService()
            } else {
                showMessage("请先授予悬浮窗权限")
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
        showMessage("悬浮窗服务已启动")
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
     * 更新悬浮窗服务状态显示 - 墨水屏优化，统一使用黑色文字
     */
    private fun updateFloatingServiceStatus() {
        val isServiceRunning = isFloatingWindowServiceRunning()
        if (isServiceRunning) {
            binding.tvFloatingWindowStatus.text = "✓ 悬浮按钮运行中"
            binding.tvFloatingWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnFloatingWindow.text = "停止悬浮按钮"
        } else {
            binding.tvFloatingWindowStatus.text = "- 悬浮按钮已停止"
            binding.tvFloatingWindowStatus.setTextColor(ContextCompat.getColor(this, R.color.black))
            binding.btnFloatingWindow.text = "启动悬浮按钮"
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
                
                showMessage("截屏权限已授予，截屏功能已启用")
                updateFloatingServiceStatus()
            } else {
                app.preferenceManager.setScreenshotPermissionGranted(false)
                showMessage("截屏权限被拒绝，截屏功能将无法使用")
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
            .setTitle("需要悬浮窗权限")
            .setMessage("ReadAssist需要悬浮窗权限才能显示悬浮按钮。请在设置中授予权限。")
            .setPositiveButton("去设置") { _, _ ->
                // 跳转到悬浮窗权限设置页面
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            .setNegativeButton("取消", null)
            .show()
    }



    override fun onDestroy() {
        super.onDestroy()
        // 注销广播接收器
        unregisterReceiver(overlayPermissionReceiver)
    }
} 