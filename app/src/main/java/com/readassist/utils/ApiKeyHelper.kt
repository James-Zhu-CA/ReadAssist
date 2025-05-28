package com.readassist.utils

import com.readassist.model.AiPlatform

/**
 * API Key 助手类
 * @deprecated 使用新的 AiPlatform 和 AiModel 系统替代
 */
@Deprecated("使用新的多平台配置系统")
object ApiKeyHelper {
    
    // 移除默认API Key，强制用户配置
    @Deprecated("不再提供默认API Key")
    const val DEFAULT_API_KEY = ""
    
    // 保持向后兼容
    @Deprecated("使用 AiModel 系统替代")
    const val MODEL_NAME = "gemini-2.0-flash"
    
    /**
     * 验证 API Key 格式
     * @deprecated 使用 AiPlatform.keyValidationPattern 替代
     */
    @Deprecated("使用 AiPlatform.keyValidationPattern 替代")
    fun isValidApiKeyFormat(apiKey: String): Boolean {
        return AiPlatform.GEMINI.keyValidationPattern.toRegex().matches(apiKey)
    }
    
    /**
     * 获取显示用的 API Key（隐藏部分字符）
     */
    fun getMaskedApiKey(apiKey: String): String {
        return if (apiKey.length > 8) {
            "${apiKey.take(8)}${"*".repeat(apiKey.length - 12)}${apiKey.takeLast(4)}"
        } else {
            "*".repeat(apiKey.length)
        }
    }
} 