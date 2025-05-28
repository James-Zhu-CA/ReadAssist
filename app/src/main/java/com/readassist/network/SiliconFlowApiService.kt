package com.readassist.network

import retrofit2.Response
import retrofit2.http.*

/**
 * SiliconFlow API 服务接口
 */
interface SiliconFlowApiService {
    
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") authorization: String,
        @Body request: SiliconFlowRequest
    ): Response<SiliconFlowResponse>
}

/**
 * SiliconFlow API 请求数据类
 */
data class SiliconFlowRequest(
    val model: String,
    val messages: List<SiliconFlowMessage>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 1024,
    val stream: Boolean = false
)

data class SiliconFlowMessage(
    val role: String, // "system", "user", "assistant"
    val content: String
)

/**
 * SiliconFlow API 响应数据类
 */
data class SiliconFlowResponse(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<SiliconFlowChoice>?,
    val usage: SiliconFlowUsage?,
    val error: SiliconFlowError?
)

data class SiliconFlowChoice(
    val index: Int,
    val message: SiliconFlowMessage?,
    val finish_reason: String?
)

data class SiliconFlowUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

data class SiliconFlowError(
    val message: String,
    val type: String?,
    val code: String?
) 