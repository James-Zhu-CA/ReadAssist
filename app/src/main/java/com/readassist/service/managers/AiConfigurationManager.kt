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
import com.readassist.R

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
                .setTitle(context.getString(R.string.ai_config_required_title))
                .setMessage(context.getString(R.string.ai_config_required_message))
                .setPositiveButton(context.getString(R.string.open_main_app)) { _, _ ->
                    onOpenMainApp.invoke()
                }
                .setNeutralButton(context.getString(R.string.quick_setup)) { _, _ ->
                    onQuickConfig.invoke()
                }
                .setNegativeButton(context.getString(R.string.cancel_button), null)
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
                .setTitle(context.getString(R.string.select_ai_platform_dialog_title))
                .setItems(platformNames) { _, which ->
                    val selectedPlatform = platforms[which]
                    onPlatformSelected.invoke(selectedPlatform)
                }
                .setNegativeButton(context.getString(R.string.cancel_button), null)
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
                hint = when (platform) {
                AiPlatform.GEMINI -> context.getString(R.string.gemini_api_key_hint)
                AiPlatform.SILICONFLOW -> context.getString(R.string.siliconflow_api_key_hint)
            }
                inputType = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            
            val keyHint = when (platform) {
            AiPlatform.GEMINI -> context.getString(R.string.gemini_api_key_hint)
            AiPlatform.SILICONFLOW -> context.getString(R.string.siliconflow_api_key_hint)
        }
        val message = context.getString(R.string.configure_platform_title, platform.displayName) + "\n\n${keyHint}\n\n" + context.getString(R.string.api_signup_url_label, platform.signupUrl)
            
            AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(context.getString(R.string.configure_api_key_title))
                .setMessage(message)
                .setView(input)
                .setPositiveButton(context.getString(R.string.confirm)) { _, _ ->
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
                        
                        Toast.makeText(context, context.getString(R.string.platform_config_success, platform.displayName), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, context.getString(R.string.api_key_invalid_format), Toast.LENGTH_SHORT).show()
                    }
                    
                    onApiKeySet.invoke(success)
                }
                .setNegativeButton(context.getString(R.string.cancel_button), null)
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