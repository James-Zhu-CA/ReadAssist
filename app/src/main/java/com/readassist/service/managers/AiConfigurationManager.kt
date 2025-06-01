package com.readassist.service.managers

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import com.readassist.model.AiModel
import com.readassist.model.AiPlatform
import com.readassist.utils.PreferenceManager

/**
 * 管理AI配置
 */
class AiConfigurationManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager
) {
    companion object {
        private const val TAG = "AiConfigurationManager"
    }
    
    /**
     * 检查配置是否有效
     */
    fun isConfigurationValid(): Boolean {
        return preferenceManager.isCurrentConfigurationValid()
    }
    
    /**
     * 检查是否有API Key
     */
    fun hasApiKey(platform: AiPlatform): Boolean {
        return preferenceManager.hasApiKey(platform)
    }
    
    /**
     * 获取当前AI平台
     */
    fun getCurrentPlatform(): AiPlatform {
        return preferenceManager.getCurrentAiPlatform()
    }
    
    /**
     * 获取当前AI模型
     */
    fun getCurrentModel(): AiModel? {
        return preferenceManager.getCurrentAiModel()
    }
    
    /**
     * 设置AI平台
     */
    fun setAiPlatform(platform: AiPlatform) {
        preferenceManager.setCurrentAiPlatform(platform)
        
        // 如果切换平台，可能需要设置默认模型
        val defaultModel = AiModel.getDefaultModelForPlatform(platform)
        if (defaultModel != null) {
            preferenceManager.setCurrentAiModel(defaultModel.id)
        }
    }
    
    /**
     * 设置AI模型
     */
    fun setAiModel(modelId: String) {
        preferenceManager.setCurrentAiModel(modelId)
    }
    
    /**
     * 设置API Key
     */
    fun setApiKey(platform: AiPlatform, apiKey: String): Boolean {
        if (apiKey.isBlank()) {
            return false
        }
        
        // 验证API Key格式
        if (!apiKey.matches(platform.keyValidationPattern.toRegex())) {
            return false
        }
        
        preferenceManager.setApiKey(platform, apiKey)
        preferenceManager.setAiSetupCompleted(true)
        return true
    }
    
    /**
     * 获取指定平台的可用模型
     */
    fun getAvailableModels(platform: AiPlatform): List<AiModel> {
        return AiModel.getDefaultModels()
            .filter { it.platform == platform }
    }
    
    /**
     * 当前模型是否支持视觉
     */
    fun currentModelSupportsVision(): Boolean {
        return preferenceManager.getCurrentAiModel()?.supportsVision == true
    }
    
    /**
     * 显示配置必需对话框
     */
    fun showConfigurationRequiredDialog(
        onOpenMainApp: () -> Unit,
        onQuickConfig: () -> Unit
    ) {
        try {
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("需要配置AI服务")
                .setMessage("使用AI助手前需要先配置API Key。\n\n请选择：\n• 在主应用中完成配置\n• 或在聊天窗口中快速配置")
                .setPositiveButton("打开主应用") { _, _ ->
                    onOpenMainApp.invoke()
                }
                .setNeutralButton("快速配置") { _, _ ->
                    onQuickConfig.invoke()
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示配置对话框失败", e)
        }
    }
    
    /**
     * 显示平台选择对话框
     */
    fun showPlatformSelectionDialog(onPlatformSelected: (AiPlatform) -> Unit) {
        try {
            val platforms = AiPlatform.values()
            val platformNames = platforms.map { it.displayName }.toTypedArray()
            
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("选择AI平台")
                .setItems(platformNames) { _, which ->
                    val selectedPlatform = platforms[which]
                    onPlatformSelected.invoke(selectedPlatform)
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示平台选择对话框失败", e)
        }
    }
    
    /**
     * 显示API Key输入对话框
     */
    fun showApiKeyInputDialog(platform: AiPlatform, onApiKeySet: (Boolean) -> Unit) {
        try {
            val input = EditText(context).apply {
                hint = platform.keyHint
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            
            val message = "配置 ${platform.displayName}\n\n${platform.keyHint}\n\n申请地址：${platform.signupUrl}"
            
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("输入API Key")
                .setMessage(message)
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val apiKey = input.text.toString().trim()
                    val success = setApiKey(platform, apiKey)
                    
                    if (success) {
                        // 设置当前平台
                        setAiPlatform(platform)
                        
                        // 设置默认模型
                        val defaultModel = AiModel.getDefaultModelForPlatform(platform)
                        if (defaultModel != null) {
                            setAiModel(defaultModel.id)
                        }
                        
                        // 配置完成
                        preferenceManager.setAiSetupCompleted(true)
                        
                        Toast.makeText(context, "✅ ${platform.displayName} 配置成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "❌ API Key 格式不正确", Toast.LENGTH_SHORT).show()
                    }
                    
                    onApiKeySet.invoke(success)
                }
                .setNegativeButton("取消", null)
                .setCancelable(true)
                .create()
                .apply {
                    window?.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_PHONE
                        }
                    )
                }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "显示API Key输入对话框失败", e)
            onApiKeySet.invoke(false)
        }
    }
} 