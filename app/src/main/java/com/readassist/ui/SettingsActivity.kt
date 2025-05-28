package com.readassist.ui

import android.os.Bundle
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.databinding.ActivitySettingsBinding
import com.readassist.model.AiPlatform
import com.readassist.model.AiModel

class SettingsActivity : AppCompatActivity() {
    
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
        
        // 加载当前设置
        loadCurrentSettings()
    }
    
    /**
     * 设置AI平台选择器
     */
    private fun setupPlatformSpinner() {
        val platforms = AiPlatform.values()
        val platformNames = platforms.map { it.displayName }
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, platformNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
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
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
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
            binding.tvApiKeyStatus.text = "已配置: $maskedKey"
            binding.tvApiKeyStatus.setTextColor(0xFF4CAF50.toInt())
        } else {
            binding.tvApiKeyStatus.text = "未配置 ${platform.displayName} API Key"
            binding.tvApiKeyStatus.setTextColor(0xFFF44336.toInt())
        }
    }
    
    /**
     * 设置监听器
     */
    private fun setupListeners() {
        // 保存提示模板
        binding.btnSaveTemplate.setOnClickListener {
            savePromptTemplate()
        }
        
        // 自动分析开关
        binding.switchAutoAnalyze.setOnCheckedChangeListener { _, isChecked ->
            app.preferenceManager.setAutoAnalyzeEnabled(isChecked)
            showMessage("自动分析已${if (isChecked) "启用" else "禁用"}")
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
            hint = currentPlatform.keyHint
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(currentKey)
        }
        
        val message = "配置 ${currentPlatform.displayName}\n\n${currentPlatform.keyHint}\n\n申请地址：${currentPlatform.signupUrl}"
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("配置API Key")
            .setMessage(message)
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val apiKey = input.text.toString().trim()
                if (apiKey.isNotEmpty()) {
                    if (apiKey.matches(currentPlatform.keyValidationPattern.toRegex())) {
                        app.preferenceManager.setApiKey(currentPlatform, apiKey)
                        app.preferenceManager.setAiSetupCompleted(true)
                        updateConfigurationStatus()
                        showMessage("✅ API Key 配置成功")
                    } else {
                        showMessage("❌ API Key 格式不正确")
                    }
                } else {
                    showMessage("请输入API Key")
                }
            }
            .setNegativeButton("取消", null)
            .setNeutralButton("打开申请页面") { _, _ ->
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, 
                        android.net.Uri.parse(currentPlatform.signupUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    showMessage("无法打开浏览器")
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
            showMessage("提示模板不能为空")
            return
        }
        
        if (!template.contains("[TEXT]")) {
            showMessage("提示模板必须包含 [TEXT] 占位符")
            return
        }
        
        app.preferenceManager.setPromptTemplate(template)
        showMessage("提示模板已保存")
    }
    
    /**
     * 清除 API Key
     */
    private fun clearApiKey() {
        val currentPlatform = app.preferenceManager.getCurrentAiPlatform()
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("清除API Key")
            .setMessage("确定要清除 ${currentPlatform.displayName} 的API Key吗？")
            .setPositiveButton("确定") { _, _ ->
                app.preferenceManager.clearApiKey(currentPlatform)
                updateConfigurationStatus()
                showMessage("已清除 ${currentPlatform.displayName} API Key")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 重置设置
     */
    private fun resetSettings() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("重置设置")
            .setMessage("确定要重置所有设置到默认值吗？这将清除所有AI配置和聊天记录。")
            .setPositiveButton("确定") { _, _ ->
                performReset()
            }
            .setNegativeButton("取消", null)
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
        
        showMessage("设置已重置")
    }
    
    /**
     * 显示消息
     */
    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
} 