package com.readassist.repository

import android.util.Log
import com.readassist.network.*
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.delay
import java.security.MessageDigest

class SiliconFlowRepository(private val preferenceManager: PreferenceManager) {
    
    private val apiService = NetworkModule.siliconFlowApiService
    private val requestCache = mutableMapOf<String, SiliconFlowCachedResponse>()
    
    companion object {
        private const val TAG = "SiliconFlowRepository"
        private const val MAX_TEXT_LENGTH = 2000
        private const val CACHE_DURATION_MS = 3600_000L // 1小时
        private const val MAX_CACHE_SIZE = 5
        private const val SYSTEM_PROMPT = "你是一个专业的阅读助手，请用中文回答问题"
        private const val MAX_RETRY_COUNT = 3
    }
    
    /**
     * 发送文本消息到 SiliconFlow API
     */
    suspend fun sendMessage(
        userText: String,
        context: List<ChatContext> = emptyList(),
        modelId: String
    ): ApiResult<String> {
        
        Log.d(TAG, "发送文本消息: $userText")
        
        // 输入验证
        if (userText.isBlank()) {
            return ApiResult.Error(IllegalArgumentException("文本内容不能为空"))
        }
        
        if (userText.length > MAX_TEXT_LENGTH) {
            return ApiResult.Error(IllegalArgumentException("文本过长，请选择较短内容"))
        }
        
        val apiKey = preferenceManager.getApiKey(com.readassist.model.AiPlatform.SILICONFLOW)
        if (apiKey.isNullOrBlank()) {
            return ApiResult.Error(IllegalArgumentException("SiliconFlow API Key 未设置"))
        }
        
        // 检查缓存
        val cacheKey = generateCacheKey(userText, context)
        val cachedResponse = checkCache(cacheKey)
        if (cachedResponse != null) {
            Log.d(TAG, "返回缓存结果")
            return ApiResult.Success(cachedResponse.response)
        }
        
        // 构建请求
        val request = buildTextRequest(userText, context, modelId)
        
        return executeRequest(apiKey, request, cacheKey)
    }
    
    /**
     * 构建文本请求
     */
    private fun buildTextRequest(
        userText: String,
        context: List<ChatContext>,
        modelId: String
    ): SiliconFlowRequest {
        val messages = mutableListOf<SiliconFlowMessage>()
        
        // 添加系统提示
        messages.add(SiliconFlowMessage("system", SYSTEM_PROMPT))
        
        // 添加上下文对话
        context.forEach { chatContext ->
            messages.add(SiliconFlowMessage("user", chatContext.userMessage))
            messages.add(SiliconFlowMessage("assistant", chatContext.aiResponse))
        }
        
        // 添加当前用户消息
        messages.add(SiliconFlowMessage("user", userText))
        
        return SiliconFlowRequest(
            model = modelId,
            messages = messages,
            temperature = 0.7f,
            max_tokens = 1024
        )
    }
    
    /**
     * 执行API请求
     */
    private suspend fun executeRequest(
        apiKey: String,
        request: SiliconFlowRequest,
        cacheKey: String
    ): ApiResult<String> {
        
        var lastException: Exception? = null
        
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                Log.d(TAG, "尝试第 ${attempt + 1} 次请求")
                
                val response = apiService.chatCompletions(
                    authorization = "Bearer $apiKey",
                    request = request
                )
                
                if (response.isSuccessful) {
                    val responseBody = response.body()
                    if (responseBody?.error != null) {
                        return ApiResult.Error(Exception("API错误: ${responseBody.error.message}"))
                    }
                    
                    val content = responseBody?.choices?.firstOrNull()?.message?.content
                    if (content.isNullOrBlank()) {
                        return ApiResult.Error(Exception("API返回空内容"))
                    }
                    
                    // 缓存结果
                    cacheResponse(cacheKey, content)
                    
                    return ApiResult.Success(content)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "API请求失败: ${response.code()} - $errorBody")
                    
                    when (response.code()) {
                        401 -> return ApiResult.Error(Exception("API Key 无效，请检查设置"))
                        429 -> {
                            Log.w(TAG, "请求频率限制，等待重试...")
                            delay(1000L * (attempt + 1)) // 指数退避
                            lastException = Exception("请求频率限制")
                        }
                        else -> return ApiResult.NetworkError("网络错误: ${response.code()}")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "请求异常: ${e.message}", e)
                lastException = e
                
                if (attempt < MAX_RETRY_COUNT - 1) {
                    delay(1000L * (attempt + 1))
                }
            }
        }
        
        return ApiResult.Error(lastException ?: Exception("未知错误"))
    }
    
    /**
     * 生成缓存键
     */
    private fun generateCacheKey(userText: String, context: List<ChatContext>): String {
        val contextString = context.joinToString("|") { "${it.userMessage}:${it.aiResponse}" }
        val fullText = "$contextString|$userText"
        
        return MessageDigest.getInstance("MD5")
            .digest(fullText.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 检查缓存
     */
    private fun checkCache(cacheKey: String): SiliconFlowCachedResponse? {
        val cached = requestCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_DURATION_MS) {
            return cached
        }
        
        // 清理过期缓存
        requestCache.remove(cacheKey)
        return null
    }
    
    /**
     * 缓存响应
     */
    private fun cacheResponse(cacheKey: String, response: String) {
        // 限制缓存大小
        if (requestCache.size >= MAX_CACHE_SIZE) {
            val oldestKey = requestCache.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { requestCache.remove(it) }
        }
        
        requestCache[cacheKey] = SiliconFlowCachedResponse(response, System.currentTimeMillis())
    }
}

/**
 * 缓存响应数据类
 */
private data class SiliconFlowCachedResponse(
    val response: String,
    val timestamp: Long
) 