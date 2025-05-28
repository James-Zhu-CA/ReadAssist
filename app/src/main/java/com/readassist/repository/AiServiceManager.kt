package com.readassist.repository

import android.graphics.Bitmap
import android.util.Log
import com.readassist.model.AiPlatform
import com.readassist.model.AiModel
import com.readassist.network.ApiResult
import com.readassist.utils.PreferenceManager

/**
 * 统一的AI服务管理器
 * 根据当前配置选择合适的AI服务
 */
class AiServiceManager(
    private val preferenceManager: PreferenceManager,
    private val geminiRepository: GeminiRepository,
    private val siliconFlowRepository: SiliconFlowRepository
) {
    
    companion object {
        private const val TAG = "AiServiceManager"
    }
    
    /**
     * 发送文本消息
     */
    suspend fun sendMessage(
        userText: String,
        context: List<ChatContext> = emptyList()
    ): ApiResult<String> {
        
        val platform = preferenceManager.getCurrentAiPlatform()
        val model = preferenceManager.getCurrentAiModel()
        
        if (model == null) {
            return ApiResult.Error(IllegalStateException("未配置AI模型"))
        }
        
        if (!preferenceManager.hasApiKey(platform)) {
            return ApiResult.Error(IllegalStateException("${platform.displayName} API Key 未设置"))
        }
        
        Log.d(TAG, "使用 ${platform.displayName} - ${model.displayName} 发送消息")
        
        return when (platform) {
            AiPlatform.GEMINI -> {
                geminiRepository.sendMessage(userText, context)
            }
            AiPlatform.SILICONFLOW -> {
                siliconFlowRepository.sendMessage(userText, context, model.id)
            }
        }
    }
    
    /**
     * 发送图片消息（仅支持Gemini和支持视觉的模型）
     */
    suspend fun sendImage(
        bitmap: Bitmap,
        prompt: String,
        context: List<ChatContext> = emptyList()
    ): ApiResult<String> {
        
        val platform = preferenceManager.getCurrentAiPlatform()
        val model = preferenceManager.getCurrentAiModel()
        
        if (model == null) {
            return ApiResult.Error(IllegalStateException("未配置AI模型"))
        }
        
        if (!model.supportsVision) {
            return ApiResult.Error(IllegalStateException("当前模型 ${model.displayName} 不支持图像分析"))
        }
        
        if (!preferenceManager.hasApiKey(platform)) {
            return ApiResult.Error(IllegalStateException("${platform.displayName} API Key 未设置"))
        }
        
        Log.d(TAG, "使用 ${platform.displayName} - ${model.displayName} 发送图片")
        
        return when (platform) {
            AiPlatform.GEMINI -> {
                geminiRepository.sendImage(bitmap, prompt, context)
            }
            AiPlatform.SILICONFLOW -> {
                // SiliconFlow 目前不支持图片分析，返回错误
                ApiResult.Error(IllegalStateException("SiliconFlow 暂不支持图片分析功能"))
            }
        }
    }
    
    /**
     * 检查当前配置是否支持截屏分析
     */
    fun supportsScreenshotAnalysis(): Boolean {
        val model = preferenceManager.getCurrentAiModel()
        return model?.supportsVision == true
    }
    
    /**
     * 获取当前配置状态
     */
    fun getCurrentConfiguration(): ConfigurationStatus {
        val platform = preferenceManager.getCurrentAiPlatform()
        val model = preferenceManager.getCurrentAiModel()
        val hasApiKey = preferenceManager.hasApiKey(platform)
        
        return ConfigurationStatus(
            platform = platform,
            model = model,
            hasApiKey = hasApiKey,
            isValid = model != null && hasApiKey,
            supportsVision = model?.supportsVision == true
        )
    }
    
    /**
     * 验证指定平台的配置
     */
    fun validateConfiguration(platform: AiPlatform, apiKey: String, modelId: String): ValidationResult {
        // 验证API Key格式
        if (!apiKey.matches(platform.keyValidationPattern.toRegex())) {
            return ValidationResult(
                isValid = false,
                error = "API Key 格式不正确"
            )
        }
        
        // 验证模型是否存在
        val availableModels = AiModel.getDefaultModels().filter { it.platform == platform }
        val model = availableModels.find { it.id == modelId }
        
        if (model == null) {
            return ValidationResult(
                isValid = false,
                error = "模型不存在或不支持"
            )
        }
        
        return ValidationResult(
            isValid = true,
            model = model
        )
    }
}

/**
 * 配置状态
 */
data class ConfigurationStatus(
    val platform: AiPlatform,
    val model: AiModel?,
    val hasApiKey: Boolean,
    val isValid: Boolean,
    val supportsVision: Boolean
)

/**
 * 验证结果
 */
data class ValidationResult(
    val isValid: Boolean,
    val error: String? = null,
    val model: AiModel? = null
) 