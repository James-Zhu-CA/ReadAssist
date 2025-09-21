package com.readassist.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.databinding.ActivitySettingsBinding
import com.readassist.model.AiPlatform
import com.readassist.model.AiModel
import com.readassist.utils.LanguageManager

class SettingsActivity : BaseActivity() {
    
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var app: ReadAssistApplication
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用 ViewBinding
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        app = application as ReadAssistApplication
        
        // 设置标题栏
        supportActionBar?.title = getString(R.string.settings_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        // 初始化设置项
        initializeSettings()
        
        // 设置监听器
        setupListeners()
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    /**
     * 初始化设置项
     */
    private fun initializeSettings() {
        // 设置AI平台和模型选择器
        setupPlatformSpinner()
        setupModelSpinner()
        
        // 设置语言选择器
        setupLanguageSpinner()
        
        // 设置Supernote专用设置
        setupSupernoteSettings()
        
        // 加载当前设置
        loadCurrentSettings()
    }
    
    /**
     * 设置AI平台选择器
     */
    private fun setupPlatformSpinner() {
        val platforms = AiPlatform.values()
        val platformNames = platforms.map { it.displayName }
        
        val adapter = ArrayAdapter(this, R.layout.spinner_item_small, platformNames)
        adapter.setDropDownViewResource(R.layout.spinner_item_small)
        
        binding.platformSpinner.adapter = adapter
        
        // 设置当前选择
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentIndex = platforms.indexOf(currentPlatform)
        if (currentIndex >= 0) {
            binding.platformSpinner.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        binding.platformSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedPlatform = platforms[position]
                if (selectedPlatform != app.preferenceManager.getCurrentAiPlatform()) {
                    app.preferenceManager.setCurrentAiPlatform(selectedPlatform)
                    
                    // 设置默认模型
                    val defaultModel = AiModel.getDefaultModelForPlatform(selectedPlatform)
                    if (defaultModel != null) {
                        app.preferenceManager.setCurrentAiModel(defaultModel.id)
                    }
                    
                    // 更新模型选择器和状态
                    setupModelSpinner()
                    updateConfigurationStatus()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 设置AI模型选择器
     */
    private fun setupModelSpinner() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val availableModels = AiModel.getDefaultModels()
            .filter { it.platform == currentPlatform }
        
        val modelNames = availableModels.map { 
            "${it.displayName}${if (!it.supportsVision) " (仅文本)" else ""}" 
        }
        
        val adapter = ArrayAdapter(this, R.layout.spinner_item_small, modelNames)
        adapter.setDropDownViewResource(R.layout.spinner_item_small)
        
        binding.modelSpinner.adapter = adapter
        
        // 设置当前选择
        val currentModelId = app.preferenceManager.getCurrentAiModelId()
        val currentIndex = availableModels.indexOfFirst { it.id == currentModelId }
        if (currentIndex >= 0) {
            binding.modelSpinner.setSelection(currentIndex)
        }
        
        // 设置选择监听器
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = availableModels[position]
                if (selectedModel.id != app.preferenceManager.getCurrentAiModelId()) {
                    app.preferenceManager.setCurrentAiModel(selectedModel.id)
                    updateConfigurationStatus()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 加载当前设置
     */
    private fun loadCurrentSettings() {
        // 加载提示模板
        val currentTemplate = app.preferenceManager.getPromptTemplate()
        binding.etPromptTemplate.setText(currentTemplate)
        
        // 加载自动分析设置
        val autoAnalyzeEnabled = app.preferenceManager.isAutoAnalyzeEnabled()
        binding.switchAutoAnalyze.isChecked = autoAnalyzeEnabled
        
        // 更新配置状态
        updateConfigurationStatus()
    }
    
    /**
     * 更新配置状态显示
     */
    private fun updateConfigurationStatus() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentModel = app.preferenceManager.getCurrentAiModel()
        val hasApiKey = app.preferenceManager.hasApiKey(currentPlatform)
        val isValid = app.preferenceManager.isCurrentConfigurationValid()
        
        // 更新当前配置显示
        if (currentModel != null) {
            binding.tvCurrentConfig.text = "${currentPlatform.displayName} - ${currentModel.displayName}"
        } else {
            binding.tvCurrentConfig.text = "${currentPlatform.displayName} - 未选择模型"
        }
        
        // 更新配置状态指示器
        binding.tvConfigStatus.apply {
            when {
                isValid -> {
                    text = "✓"
                    setTextColor(0xFF4CAF50.toInt())
                    setBackgroundColor(0xFFE8F5E8.toInt())
                }
                hasApiKey -> {
                    text = "⚠"
                    setTextColor(0xFFFF9800.toInt())
                    setBackgroundColor(0xFFFFF3E0.toInt())
                }
                else -> {
                    text = "❌"
                    setTextColor(0xFFF44336.toInt())
                    setBackgroundColor(0xFFFFEBEE.toInt())
                }
            }
        }
        
        // 更新API Key状态
        updateApiKeyStatus(currentPlatform, hasApiKey)
    }
    
    /**
     * 更新API Key状态显示
     */
    private fun updateApiKeyStatus(platform: AiPlatform, hasKey: Boolean) {
        if (hasKey) {
            val apiKey = app.preferenceManager.getApiKey(platform) ?: ""
            val maskedKey = com.readassist.utils.ApiKeyHelper.getMaskedApiKey(apiKey)
            binding.tvApiKeyStatus.text = getString(R.string.api_key_configured, maskedKey)
            binding.tvApiKeyStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.tvApiKeyStatus.text = getString(R.string.api_key_not_configured, platform.displayName)
            binding.tvApiKeyStatus.setTextColor(0xFFF44336.toInt())
        }
    }
    
    /**
     * 设置语言选择器
     */
    private fun setupLanguageSpinner() {
        // 简化的语言选项
        val languageOptions = listOf(
            "system" to getString(R.string.language_system),
            "zh" to getString(R.string.language_chinese),
            "en" to getString(R.string.language_english)
        )
        
        val languageNames = languageOptions.map { it.second }
        val languageCodes = languageOptions.map { it.first }
        
        val adapter = ArrayAdapter(this, R.layout.spinner_item_small, languageNames)
        adapter.setDropDownViewResource(R.layout.spinner_item_small)
        
        binding.languageSpinner.adapter = adapter
        
        // 设置当前选择
        val currentLanguageCode = app.preferenceManager.getAppLanguage()
        val currentIndex = languageCodes.indexOf(currentLanguageCode)
        if (currentIndex >= 0) {
            binding.languageSpinner.setSelection(currentIndex)
        }
        
        android.util.Log.e("SettingsActivity", "🌐 Current language: $currentLanguageCode (index: $currentIndex)")
        
        // 设置选择监听器
        binding.languageSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedLanguageCode = languageCodes[position]
                val currentLanguageCode = app.preferenceManager.getAppLanguage()
                
                if (selectedLanguageCode != currentLanguageCode) {
                    android.util.Log.e("SettingsActivity", "🔄 Language change requested: $currentLanguageCode -> $selectedLanguageCode")
                    
                    // 显示确认对话框
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle(getString(R.string.language_title))
                        .setMessage(getString(R.string.language_changed))
                        .setPositiveButton(getString(R.string.ok)) { _, _ ->
                            try {
                                android.util.Log.e("SettingsActivity", "✅ User confirmed language change")
                                
                                // 调试：保存前先检查当前值
                                val beforeSave = app.preferenceManager.getAppLanguage()
                                android.util.Log.e("SettingsActivity", "🔍 Language before save: '$beforeSave'")
                                
                                // 直接保存语言设置到SharedPreferences
                                app.preferenceManager.setAppLanguage(selectedLanguageCode)
                                
                                // 调试：保存后立即验证
                                val afterSave = app.preferenceManager.getAppLanguage()
                                android.util.Log.e("SettingsActivity", "✅ Language setting saved: '$selectedLanguageCode'")
                                android.util.Log.e("SettingsActivity", "🔍 Language after save: '$afterSave'")
                                
                                // 额外验证：直接读取SharedPreferences
                                val prefs = getSharedPreferences("readassist_prefs", Context.MODE_PRIVATE)
                                val directRead = prefs.getString("app_language", "system") ?: "system"
                                android.util.Log.e("SettingsActivity", "🔍 Direct SharedPreferences read: '$directRead'")
                                
                                // 显示提示信息
                                showMessage(getString(R.string.language_saved_restarting))
                                
                                // 简化的重启方式
                                binding.languageSpinner.postDelayed({
                                    simpleRestartApp()
                                }, 1000)
                                
                            } catch (e: Exception) {
                                android.util.Log.e("SettingsActivity", "❌ Failed to save language setting", e)
                                showMessage("语言设置失败: ${e.message}")
                                
                                // 恢复到原来的选择
                                val originalIndex = languageCodes.indexOf(currentLanguageCode)
                                if (originalIndex >= 0) {
                                    binding.languageSpinner.setSelection(originalIndex)
                                }
                            }
                        }
                        .setNegativeButton(getString(R.string.cancel)) { _, _ ->
                            android.util.Log.e("SettingsActivity", "❌ User cancelled language change")
                            
                            // 恢复到原来的选择
                            val originalIndex = languageCodes.indexOf(currentLanguageCode)
                            if (originalIndex >= 0) {
                                binding.languageSpinner.setSelection(originalIndex)
                            }
                        }
                        .setCancelable(false) // 防止意外取消
                        .show()
                }
            }
            
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    /**
     * 设置Supernote专用设置
     */
    private fun setupSupernoteSettings() {
        // 只有Supernote设备才显示相关设置
        if (com.readassist.utils.DeviceUtils.isSupernoteDevice()) {
            binding.layoutSupernoteSettings.visibility = View.VISIBLE
            
            // 加载当前设置状态
            binding.switchSupernoteScreenshot.isChecked = app.preferenceManager.getSupernoteScreenshotEnabled()
        } else {
            binding.layoutSupernoteSettings.visibility = View.GONE
        }
    }
    
    /**
     * 设置监听器
     */
    private fun setupListeners() {
        // 返回主界面按钮
        binding.btnBackToMain.setOnClickListener {
            finish()
        }
        
        // 保存提示模板
        binding.btnSaveTemplate.setOnClickListener {
            savePromptTemplate()
        }
        
        // 自动分析开关
        binding.switchAutoAnalyze.setOnCheckedChangeListener { _, isChecked ->
            app.preferenceManager.setAutoAnalyzeEnabled(isChecked)
            showMessage(getString(R.string.auto_analyze_status, if (isChecked) getString(R.string.enabled) else getString(R.string.disabled)))
        }
        
        // Supernote截屏开关
        binding.switchSupernoteScreenshot?.setOnCheckedChangeListener { _, isChecked ->
            app.preferenceManager.setSupernoteScreenshotEnabled(isChecked)
            showMessage("Supernote截屏功能已${if (isChecked) "启用" else "禁用"}")
        }
        
        // 配置API Key
        binding.btnConfigureApiKey.setOnClickListener {
            showApiKeyConfigDialog()
        }
        
        // 清除 API Key
        binding.btnClearApiKey.setOnClickListener {
            clearApiKey()
        }
        
        // 重置设置
        binding.btnResetSettings.setOnClickListener {
            resetSettings()
        }
    }
    
    /**
     * 显示API Key配置对话框
     */
    private fun showApiKeyConfigDialog() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        val currentKey = app.preferenceManager.getApiKey(currentPlatform) ?: ""
        
        val input = android.widget.EditText(this).apply {
                            hint = when (currentPlatform) {
                    AiPlatform.GEMINI -> getString(R.string.gemini_api_key_hint)
                    AiPlatform.SILICONFLOW -> getString(R.string.siliconflow_api_key_hint)
                }
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(currentKey)
        }
        
                    val keyHint = when (currentPlatform) {
                AiPlatform.GEMINI -> getString(R.string.gemini_api_key_hint)
                AiPlatform.SILICONFLOW -> getString(R.string.siliconflow_api_key_hint)
            }
            val message = getString(R.string.configure_platform_title, currentPlatform.displayName) + "\n\n${keyHint}\n\n" + getString(R.string.api_signup_url_label, currentPlatform.signupUrl)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.configure_api_key_title))
            .setMessage(message)
            .setView(input)
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    if (apiKey.matches(currentPlatform.keyValidationPattern.toRegex())) {
                        app.preferenceManager.setApiKey(currentPlatform, apiKey)
                        app.preferenceManager.setAiSetupCompleted(true)
                        updateConfigurationStatus()
                        showMessage(getString(R.string.api_key_config_success))
                    } else {
                        showMessage(getString(R.string.api_key_invalid_format))
                    }
                } else {
                    showMessage(getString(R.string.please_enter_api_key))
                }
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .setNeutralButton(getString(R.string.open_signup_page)) { _, _ ->
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, 
                        android.net.Uri.parse(currentPlatform.signupUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    showMessage(getString(R.string.cannot_open_browser))
                }
            }
            .show()
    }
    
    /**
     * 保存提示模板
     */
    private fun savePromptTemplate() {
        val template = binding.etPromptTemplate.text.toString().trim()
        
        if (template.isEmpty()) {
            showMessage(getString(R.string.prompt_template_empty_error))
            return
        }
        
        if (!template.contains("[TEXT]")) {
            showMessage(getString(R.string.prompt_template_placeholder_error))
            return
        }
        
        app.preferenceManager.setPromptTemplate(template)
        showMessage(getString(R.string.prompt_template_saved))
    }
    
    /**
     * 清除 API Key
     */
    private fun clearApiKey() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_api_key_title))
            .setMessage(getString(R.string.clear_api_key_message, currentPlatform.displayName))
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                app.preferenceManager.clearApiKey(currentPlatform)
                updateConfigurationStatus()
                showMessage(getString(R.string.api_key_cleared_message, currentPlatform.displayName))
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }
    
    /**
     * 重置设置
     */
    private fun resetSettings() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.reset_settings_title))
            .setMessage(getString(R.string.reset_settings_message))
            .setPositiveButton(getString(R.string.confirm_button)) { _, _ ->
                performReset()
            }
            .setNegativeButton(getString(R.string.cancel_button), null)
            .show()
    }
    
    /**
     * 执行重置
     */
    private fun performReset() {
        // 重置偏好设置
        app.preferenceManager.clearAllPreferences()
        
        // 重新初始化设置
        initializeSettings()
        
        showMessage(getString(R.string.settings_reset_complete))
    }
    
    /**
     * 显示消息
     */
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 重启应用
     */
    private fun restartApp() {
        android.util.Log.e("SettingsActivity", "🔄 Restarting app...")
        
        try {
            // 方法1：使用Intent重启
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                
                android.util.Log.e("SettingsActivity", "🚀 Starting new app instance")
                startActivity(intent)
                
                // 确保当前Activity完全结束
                finishAffinity()
                
                // 延迟杀死进程
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    android.util.Log.e("SettingsActivity", "💀 Killing current process")
                    android.os.Process.killProcess(android.os.Process.myPid())
                }, 500)
                
            } else {
                android.util.Log.e("SettingsActivity", "❌ Cannot get launch intent")
                showMessage("重启失败，请手动重启应用")
            }
            
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "❌ Failed to restart app", e)
            showMessage("重启失败: ${e.message}")
        }
    }
    
    /**
     * 简化的重启方式
     */
    private fun simpleRestartApp() {
        android.util.Log.e("SettingsActivity", "🔄 Simple restart app...")
        
        try {
            // 创建新的Intent启动主Activity
            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            
            android.util.Log.e("SettingsActivity", "🚀 Starting MainActivity with language change")
            startActivity(intent)
            
            // 直接finish当前Activity
            finish()
            
        } catch (e: Exception) {
            android.util.Log.e("SettingsActivity", "❌ Failed to simple restart", e)
            showMessage("重启失败: ${e.message}")
        }
    }
} 