package com.readassist.network

import retrofit2.Response
import retrofit2.http.*

/**
 * Gemini API 服务接口
 */
interface GeminiApiService {
    
    @POST("v1beta/models/gemini-2.5-flash-preview-05-20:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
    
    @POST("v1beta/models/gemini-2.5-flash-preview-05-20:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): Response<GeminiResponse>
}

/**
 * Gemini API 请求数据类
 */
data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val safetySettings: List<SafetySetting>? = null
)

data class Content(
    val parts: List<Part>,
    val role: String = "user"
)

data class Part(
    val text: String? = null,
    val inlineData: InlineData? = null
)

data class InlineData(
    val mimeType: String,
    val data: String  // Base64 编码的图片数据
)

data class GenerationConfig(
    val temperature: Float = 0.7f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val maxOutputTokens: Int = 1024,
    val stopSequences: List<String>? = null
)

data class SafetySetting(
    val category: String,
    val threshold: String
)

/**
 * Gemini API 响应数据类
 */
data class GeminiResponse(
    val candidates: List<Candidate>?,
    val promptFeedback: PromptFeedback? = null,
    val error: GeminiError? = null
)

data class Candidate(
    val content: Content?,
    val finishReason: String? = null,
    val index: Int = 0,
    val safetyRatings: List<SafetyRating>? = null
)

data class SafetyRating(
    val category: String,
    val probability: String
)

data class PromptFeedback(
    val safetyRatings: List<SafetyRating>? = null,
    val blockReason: String? = null
)

data class GeminiError(
    val code: Int,
    val message: String,
    val status: String
)

/**
 * API 结果封装类
 */
sealed class ApiResult<T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error<T>(val exception: Throwable) : ApiResult<T>()
    data class NetworkError<T>(val message: String) : ApiResult<T>()
} 