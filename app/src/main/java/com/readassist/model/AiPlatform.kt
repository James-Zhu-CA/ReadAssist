package com.readassist.model

/**
 * AI平台枚举
 */
enum class AiPlatform(
    val displayName: String,
    val baseUrl: String,
    val keyHint: String,
    val keyValidationPattern: String,
    val signupUrl: String
) {
    GEMINI(
        displayName = "Google Gemini",
        baseUrl = "https://generativelanguage.googleapis.com/",
        keyHint = "请输入 Gemini API Key (以 AIza 开头)",
        keyValidationPattern = "^AIza[A-Za-z0-9_-]{35,}$",
        signupUrl = "https://aistudio.google.com/apikey"
    ),
    SILICONFLOW(
        displayName = "SiliconFlow",
        baseUrl = "https://api.siliconflow.cn/",
        keyHint = "请输入 SiliconFlow API Key (以 sk- 开头)",
        keyValidationPattern = "^sk-[A-Za-z0-9]{32,}$",
        signupUrl = "https://siliconflow.com/en/home"
    )
}

/**
 * AI模型配置
 */
data class AiModel(
    val id: String,
    val displayName: String,
    val platform: AiPlatform,
    val supportsVision: Boolean = false,
    val isCustom: Boolean = false,
    val description: String = ""
) {
    companion object {
        /**
         * 获取默认模型列表
         */
        fun getDefaultModels(): List<AiModel> {
            return listOf(
                // Gemini 模型
                AiModel(
                    id = "gemini-2.0-flash",
                    displayName = "Gemini 2.0 Flash",
                    platform = AiPlatform.GEMINI,
                    supportsVision = true,
                    description = "最新的Gemini模型，支持图像分析"
                ),
                AiModel(
                    id = "gemini-2.5-flash-preview-05-20",
                    displayName = "Gemini 2.5 Flash Preview",
                    platform = AiPlatform.GEMINI,
                    supportsVision = true,
                    description = "Gemini 2.5预览版，性能优秀"
                ),
                AiModel(
                    id = "gemini-2.5-pro-preview-05-06",
                    displayName = "Gemini 2.5 Pro Preview",
                    platform = AiPlatform.GEMINI,
                    supportsVision = true,
                    description = "Gemini 2.5专业版，功能最强"
                ),
                
                // SiliconFlow 模型
                AiModel(
                    id = "deepseek-ai/DeepSeek-V3",
                    displayName = "DeepSeek V3",
                    platform = AiPlatform.SILICONFLOW,
                    supportsVision = false,
                    description = "⚠️ 不支持截屏分析，仅支持文本对话"
                ),
                AiModel(
                    id = "Qwen/Qwen2.5-VL-72B-Instruct",
                    displayName = "Qwen2.5-VL-72B",
                    platform = AiPlatform.SILICONFLOW,
                    supportsVision = true,
                    description = "支持视觉理解的大型模型"
                ),
                AiModel(
                    id = "deepseek-ai/deepseek-vl2",
                    displayName = "DeepSeek VL2",
                    platform = AiPlatform.SILICONFLOW,
                    supportsVision = false,
                    description = "⚠️ 不支持截屏分析，仅支持文本对话"
                )
            )
        }
        
        /**
         * 根据平台获取默认模型
         */
        fun getDefaultModelForPlatform(platform: AiPlatform): AiModel? {
            return when (platform) {
                AiPlatform.GEMINI -> getDefaultModels().find { it.id == "gemini-2.0-flash" }
                AiPlatform.SILICONFLOW -> getDefaultModels().find { it.id == "Qwen/Qwen2.5-VL-72B-Instruct" }
            }
        }
    }
}

/**
 * AI配置状态
 */
data class AiConfiguration(
    val platform: AiPlatform,
    val model: AiModel,
    val apiKey: String,
    val isConfigured: Boolean = false
) {
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return apiKey.isNotBlank() && 
               apiKey.matches(platform.keyValidationPattern.toRegex()) &&
               model.platform == platform
    }
    
    /**
     * 检查是否支持截屏分析
     */
    fun supportsScreenshot(): Boolean {
        return model.supportsVision
    }
} 