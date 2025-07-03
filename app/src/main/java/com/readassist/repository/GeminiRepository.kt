package com.readassist.repository

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.readassist.network.*
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class GeminiRepository(private val preferenceManager: PreferenceManager) {
    
    private val apiService = NetworkModule.geminiApiService
    private val requestCache = mutableMapOf<String, CachedResponse>()
    
    companion object {
        private const val TAG = "GeminiRepository"
        private const val MAX_TEXT_LENGTH = 2000
        private const val CACHE_DURATION_MS = 3600_000L // 1小时
        private const val MAX_CACHE_SIZE = 5
        private const val SYSTEM_PROMPT = "你是一个专业的阅读助手，请用用户指定的语言回答问题，回答完后根据之前的问题猜测用户可能会有的问题，请用户问问题。"
        private const val MAX_RETRY_COUNT = 3
    }
    
    /**
     * 发送文本消息到 Gemini API
     */
    suspend fun sendMessage(
        userText: String,
        context: List<ChatContext> = emptyList()
    ): ApiResult<String> {
        
        Log.d(TAG, "发送文本消息: $userText")
        
        // 输入验证
        if (userText.isBlank()) {
            return ApiResult.Error(IllegalArgumentException("文本内容不能为空"))
        }
        
        if (userText.length > MAX_TEXT_LENGTH) {
            return ApiResult.Error(IllegalArgumentException("文本过长，请选择较短内容"))
        }
        
        val apiKey = preferenceManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            return ApiResult.Error(IllegalArgumentException("API Key 未设置"))
        }
        
        // 检查缓存
        val cacheKey = generateCacheKey(userText, context)
        val cachedResponse = checkCache(cacheKey)
        if (cachedResponse != null) {
            Log.d(TAG, "返回缓存结果")
            return ApiResult.Success(cachedResponse.response)
        }
        
        // 构建请求
        val request = buildTextRequest(userText, context)
        Log.d(TAG, "发送给API的请求内容: $request")
        
        return executeRequest(apiKey, request, cacheKey)
    }
    
    /**
     * 发送图片到 Gemini API
     */
    suspend fun sendImage(
        bitmap: Bitmap,
        prompt: String,
        context: List<ChatContext> = emptyList()
    ): ApiResult<String> {
        
        Log.d(TAG, "🖼️ === sendImage 开始 ===")
        Log.d(TAG, "📝 提示文本: $prompt")
        Log.d(TAG, "📐 原始图片尺寸: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "🔍 图片配置: ${bitmap.config}")
        Log.d(TAG, "📊 图片字节数: ${bitmap.byteCount}")
        Log.d(TAG, "♻️ 图片是否回收: ${bitmap.isRecycled}")
        
        // 检查bitmap有效性
        if (bitmap.isRecycled) {
            Log.e(TAG, "❌ Bitmap已被回收")
            return ApiResult.Error(IllegalArgumentException("图片已被回收"))
        }
        
        // 🔍 增强图片内容质量检查
        val contentQualityResult = checkImageContentQuality(bitmap)
        if (!contentQualityResult.isValid) {
            Log.w(TAG, "⚠️ 图片内容质量检查失败: ${contentQualityResult.reason}")
            return ApiResult.Error(Exception("图片内容问题：${contentQualityResult.reason}"))
        }
        Log.d(TAG, "✅ 图片内容质量检查通过: ${contentQualityResult.description}")
        
        val apiKey = preferenceManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "❌ API Key未设置")
            return ApiResult.Error(IllegalArgumentException("API Key 未设置"))
        }
        Log.d(TAG, "✅ API Key已验证，长度: ${apiKey.length}")
        
        // 检查图片大小，如果太大则压缩 - 但不创建新的Bitmap对象
        val pixelCount = bitmap.width * bitmap.height
        Log.d(TAG, "📊 图片像素总数: $pixelCount")
        
        // 重要：不再在这里创建新的Bitmap对象，而是直接使用传入的bitmap
        // 注释掉创建压缩Bitmap的代码，即使图片较大也直接使用
        Log.d(TAG, "📦 直接使用原始图片，由系统内部处理压缩")
        
        // 将图片转换为Base64
        Log.d(TAG, "🔄 开始Base64转换...")
        val base64Image = try {
            val result = bitmapToBase64(bitmap)
            Log.d(TAG, "✅ Base64转换成功")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ 图片转换失败", e)
            return ApiResult.Error(Exception("图片处理失败：${e.message}"))
        }
        
        Log.d(TAG, "📏 Base64图片大小: ${base64Image.length} 字符")
        
        // 验证Base64内容
        if (base64Image.isBlank()) {
            Log.e(TAG, "❌ Base64转换结果为空")
            return ApiResult.Error(Exception("Base64转换结果为空"))
        }
        Log.d(TAG, "🔍 Base64内容预览: ${base64Image.take(100)}...")
        
        // 构建请求
        Log.d(TAG, "🔧 构建API请求...")
        val request = buildImageRequest(base64Image, prompt, context)
        val cacheKey = generateCacheKey("image_$prompt", context)
        Log.d(TAG, "🔑 缓存键: $cacheKey")
        
        // 再次检查图片是否还有效（可能在Base64转换过程中被回收）
        if (bitmap.isRecycled) {
            Log.e(TAG, "❌ 请求前图片已被回收")
            return ApiResult.Error(Exception("图片在处理过程中被回收"))
        }
        
        Log.d(TAG, "🚀 执行API请求...")
        val result = executeRequest(apiKey, request, cacheKey)
        
        Log.d(TAG, "📥 API请求完成，结果类型: ${result::class.simpleName}")
        when (result) {
            is ApiResult.Success -> {
                Log.d(TAG, "✅ API调用成功")
                Log.d(TAG, "📝 响应长度: ${result.data.length}")
                Log.d(TAG, "📝 响应预览: ${result.data.take(200)}...")
            }
            is ApiResult.Error -> {
                Log.e(TAG, "❌ API调用失败: ${result.exception.message}")
            }
            is ApiResult.NetworkError -> {
                Log.e(TAG, "🌐 网络错误: ${result.message}")
            }
        }
        
        // 注意：不在这里回收bitmap，由调用方负责
        Log.d(TAG, "♻️ 图片使用完毕，但不回收，由调用方负责")
        
        Log.d(TAG, "🖼️ === sendImage 结束 ===")
        return result
    }
    
    /**
     * 执行API请求
     */
    private suspend fun executeRequest(
        apiKey: String,
        request: GeminiRequest,
        cacheKey: String
    ): ApiResult<String> {
        
        // 重试机制
        var lastException: Throwable? = null
        repeat(MAX_RETRY_COUNT) { attempt ->
            try {
                Log.d(TAG, "🚀 尝试API请求，第${attempt + 1}次")
                Log.d(TAG, "🔑 API Key长度: ${apiKey.length}")
                Log.d(TAG, "📦 请求内容数量: ${request.contents.size}")
                
                // 获取当前选择的模型
                val currentModel = preferenceManager.getCurrentAiModel()
                val modelId = currentModel?.id ?: "gemini-2.5-flash"
                
                Log.d(TAG, "🎯 使用模型: $modelId")
                val response = apiService.generateContent(modelId, apiKey, request)
                
                Log.d(TAG, "📡 响应状态码: ${response.code()}")
                Log.d(TAG, "📡 响应消息: ${response.message()}")
                
                if (response.isSuccessful) {
                    val geminiResponse = response.body()
                    Log.d(TAG, "✅ API响应成功，开始解析...")
                    Log.d(TAG, "📦 响应体是否为空: ${geminiResponse == null}")
                    
                    val aiReply = parseResponse(geminiResponse)
                    
                    if (aiReply != null && aiReply.isNotBlank()) {
                        Log.d(TAG, "✅ 解析成功，响应长度: ${aiReply.length}")
                        // 缓存响应
                        cacheResponse(cacheKey, aiReply)
                        return ApiResult.Success(aiReply)
                    } else {
                        Log.e(TAG, "❌ AI响应为空或解析失败")
                        Log.e(TAG, "❌ aiReply值: '$aiReply'")
                        return ApiResult.Error(Exception("AI 响应解析失败。这可能是由于图片内容不清晰、包含不支持的内容，或网络问题导致的。请尝试重新截屏或检查网络连接。"))
                    }
                } else {
                    // 处理HTTP错误
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "HTTP错误: ${response.code()}, 错误内容: $errorBody")
                    
                    // 根据状态码返回更友好的错误信息
                    return when (response.code()) {
                        400 -> ApiResult.Error(Exception("请求格式错误。可能是图片格式不支持或内容有问题。"))
                        401 -> ApiResult.Error(Exception("API Key 无效，请检查设置"))
                        403 -> ApiResult.Error(Exception("访问被拒绝。可能是API配额不足或地区限制。"))
                        429 -> ApiResult.Error(Exception("请求频率过高，请稍后重试"))
                        500, 502, 503 -> ApiResult.NetworkError("Gemini服务暂时不可用，请稍后重试")
                        else -> ApiResult.NetworkError("网络请求失败 (错误码: ${response.code()})")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "API请求异常，尝试${attempt + 1}", e)
                lastException = e
                if (attempt < MAX_RETRY_COUNT - 1) {
                    // 指数退避延迟
                    delay(1000L * (attempt + 1))
                }
            }
        }
        
        val errorMessage = when (lastException) {
            is java.net.UnknownHostException -> "网络连接失败，请检查网络设置"
            is java.net.SocketTimeoutException -> "请求超时，请检查网络连接"
            is javax.net.ssl.SSLException -> "SSL连接失败，请检查网络安全设置"
            else -> "网络请求失败：${lastException?.message ?: "未知错误"}"
        }
        
        return ApiResult.Error(Exception(errorMessage))
    }
    
    /**
     * 构建文本请求
     */
    private fun buildTextRequest(userText: String, context: List<ChatContext>): GeminiRequest {
        val contents = mutableListOf<Content>()
        
        // 添加系统提示
        contents.add(Content(
            parts = listOf(Part(text = SYSTEM_PROMPT)),
            role = "user"
        ))
        
        // 添加上下文对话（最近10轮）
        context.takeLast(10).forEach { chatContext ->
            contents.add(Content(
                parts = listOf(Part(text = chatContext.userMessage)),
                role = "user"
            ))
            contents.add(Content(
                parts = listOf(Part(text = chatContext.aiResponse)),
                role = "model"
            ))
        }
        
        // 添加当前用户消息
        val promptTemplate = preferenceManager.getPromptTemplate()
        val formattedText = promptTemplate.replace("[TEXT]", userText)
        
        contents.add(Content(
            parts = listOf(Part(text = formattedText)),
            role = "user"
        ))
        
        // 📤 输出发送给AI的完整文本信息
        Log.d(TAG, "📤📤📤 发送给AI的完整文本信息:")
        Log.d(TAG, "📝 原始用户输入: $userText")
        Log.d(TAG, "📝 使用的提示模板: $promptTemplate")
        Log.d(TAG, "📝 最终发送内容: $formattedText")
        Log.d(TAG, "📋 上下文对话数量: ${context.size}")
        context.forEachIndexed { index, chatContext ->
            Log.d(TAG, "   对话${index + 1} - 用户: ${chatContext.userMessage.take(50)}...")
            Log.d(TAG, "   对话${index + 1} - AI: ${chatContext.aiResponse.take(50)}...")
        }
        
        return GeminiRequest(
            contents = contents,
            generationConfig = GenerationConfig(
                temperature = 0.7f,
                topK = 40,
                topP = 0.95f,
                maxOutputTokens = 65000
            ),
            safetySettings = listOf(
                SafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_MEDIUM_AND_ABOVE")
            )
        )
    }
    
    /**
     * 构建图片请求
     */
    private fun buildImageRequest(
        base64Image: String,
        prompt: String,
        context: List<ChatContext>
    ): GeminiRequest {
        val contents = mutableListOf<Content>()
        
        // 添加系统提示
        contents.add(Content(
            parts = listOf(Part(text = SYSTEM_PROMPT)),
            role = "user"
        ))
        
        // 添加上下文对话（最近3轮，图片请求上下文较少）
        context.takeLast(3).forEach { chatContext ->
            contents.add(Content(
                parts = listOf(Part(text = chatContext.userMessage)),
                role = "user"
            ))
            contents.add(Content(
                parts = listOf(Part(text = chatContext.aiResponse)),
                role = "model"
            ))
        }
        
        // 使用设置中的提示词模板，如果用户没有输入具体问题则使用默认提示
        val promptTemplate = preferenceManager.getPromptTemplate()
        val basePrompt = if (prompt.isBlank() || prompt.contains("请分析") || prompt.contains("Please analyze")) {
            // 如果是默认提示或空白，使用模板中的图片分析提示
            promptTemplate.replace("[TEXT]", "图片中的文字内容")
        } else {
            // 如果用户有具体问题，使用用户的问题
            promptTemplate.replace("[TEXT]", prompt)
        }
        
        // 添加图片分析的专门指引
        val imageAnalysisGuidance = """

如果发给你信息里有图片，请按以下要求分析截图：
1. 如果截图里有高亮，画圈，下划线和手写文字，请优先输出相关部分的文字然后进行解读，如果都没有则小结图片文字的核心观点，并给出你的洞见，针对你认为重要的句子同时进行原文输出。
2. 请不要解读截图上操作界面/阅读界面上的功能按钮。
3. 为避免触发版权保护机制，请不要一整段原文输出。
4. 整体回答要简要明了，不要超过500字。"""

        val finalPrompt = basePrompt + imageAnalysisGuidance
        
        // 添加图片和提示
        contents.add(Content(
            parts = listOf(
                Part(text = finalPrompt),
                Part(inlineData = InlineData(
                    mimeType = "image/jpeg",
                    data = base64Image
                ))
            ),
            role = "user"
        ))
        
        Log.d(TAG, "构建图片请求，contents数量: ${contents.size}")
        
        // 📤 输出发送给AI的完整提示信息
        Log.d(TAG, "📤📤📤 发送给AI的完整提示信息:")
        Log.d(TAG, "📝 提示内容: $finalPrompt")
        Log.d(TAG, "📷 图片信息: JPEG格式, Base64长度=${base64Image.length}")
        Log.d(TAG, "📋 上下文对话数量: ${context.size}")
        context.forEachIndexed { index, chatContext ->
            Log.d(TAG, "   对话${index + 1} - 用户: ${chatContext.userMessage.take(50)}...")
            Log.d(TAG, "   对话${index + 1} - AI: ${chatContext.aiResponse.take(50)}...")
        }
        
        return GeminiRequest(
            contents = contents,
            generationConfig = GenerationConfig(
                temperature = 0.1f, // 进一步降低温度，获得更简洁的响应
                topK = 20,
                topP = 0.8f,
                maxOutputTokens = 65000 // 增加到65000，但通过提示语限制实际长度
            ),
            safetySettings = listOf(
                SafetySetting("HARM_CATEGORY_HARASSMENT", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_HATE_SPEECH", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_SEXUALLY_EXPLICIT", "BLOCK_MEDIUM_AND_ABOVE"),
                SafetySetting("HARM_CATEGORY_DANGEROUS_CONTENT", "BLOCK_MEDIUM_AND_ABOVE")
            )
        )
    }
    
    /**
     * 将Bitmap转换为Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        Log.d(TAG, "🎨 === bitmapToBase64 开始 ===")
        Log.d(TAG, "📐 输入Bitmap尺寸: ${bitmap.width}x${bitmap.height}")
        Log.d(TAG, "🔍 Bitmap配置: ${bitmap.config}")
        Log.d(TAG, "📊 Bitmap字节数: ${bitmap.byteCount}")
        
        // 检查bitmap有效性
        if (bitmap.isRecycled) {
            Log.e(TAG, "❌ Bitmap已被回收")
            throw IllegalArgumentException("图片已被回收，无法转换为Base64")
        }
        
        val outputStream = ByteArrayOutputStream()
        
        // 使用JPEG格式以获得更好的压缩比，同时保持质量
        Log.d(TAG, "📦 开始JPEG压缩，质量: 85%")
        val success = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        
        if (!success) {
            Log.e(TAG, "❌ 图片压缩失败")
            throw Exception("图片压缩失败")
        }
        Log.d(TAG, "✅ JPEG压缩成功")
        
        val byteArray = outputStream.toByteArray()
        Log.d(TAG, "📏 压缩后图片大小: ${byteArray.size} 字节")
        
        // 验证压缩后的数据
        if (byteArray.isEmpty()) {
            Log.e(TAG, "❌ 压缩后数据为空")
            throw Exception("压缩后数据为空")
        }
        
        // 检查JPEG文件头
        if (byteArray.size >= 2) {
            val header = String.format("%02X%02X", byteArray[0], byteArray[1])
            Log.d(TAG, "🔍 JPEG文件头: $header")
            if (header != "FFD8") {
                Log.w(TAG, "⚠️ 可能不是有效的JPEG文件头")
            }
        }
        
        // 释放输出流资源
        try {
            outputStream.close()
        } catch (e: Exception) {
            Log.w(TAG, "关闭输出流失败", e)
        }
        
        Log.d(TAG, "🔄 开始Base64编码...")
        val base64Result = Base64.encodeToString(byteArray, Base64.NO_WRAP)
        
        if (base64Result.isBlank()) {
            Log.e(TAG, "❌ Base64编码结果为空")
            throw Exception("Base64编码失败")
        }
        
        // 再次检查bitmap是否仍然有效（确保未被回收）
        if (bitmap.isRecycled) {
            Log.e(TAG, "❌ Base64编码后，发现Bitmap已被回收")
            // 这可能是内存压力导致的垃圾收集，但我们仍继续处理
        } else {
            Log.d(TAG, "✅ Bitmap在Base64编码后仍然有效")
        }
        
        Log.d(TAG, "✅ Base64编码成功")
        Log.d(TAG, "📏 Base64字符串长度: ${base64Result.length}")
        Log.d(TAG, "🔍 Base64开头: ${base64Result.take(50)}...")
        Log.d(TAG, "🎨 === bitmapToBase64 完成 ===")
        
        return base64Result
    }
    
    /**
     * 解析 Gemini 响应
     */
    private fun parseResponse(response: GeminiResponse?): String? {
        Log.d(TAG, "开始解析响应")
        
        if (response == null) {
            Log.e(TAG, "响应为空")
            return null
        }
        
        Log.d(TAG, "响应详情: $response")
        
        if (response.error != null) {
            Log.e(TAG, "API返回错误: ${response.error}")
            throw Exception("API错误: ${response.error.message}")
        }
        
        val candidates = response.candidates
        Log.d(TAG, "candidates数量: ${candidates?.size ?: 0}")
        
        if (candidates.isNullOrEmpty()) {
            Log.e(TAG, "candidates为空")
            // 尝试从promptFeedback获取更多信息
            if (response.promptFeedback != null) {
                Log.e(TAG, "promptFeedback: ${response.promptFeedback}")
                if (response.promptFeedback.blockReason != null) {
                    throw Exception("内容被阻止: ${response.promptFeedback.blockReason}")
                }
            }
            return "抱歉，AI无法分析这张图片。可能是图片内容不清晰或包含不适宜的内容。"
        }
        
        val firstCandidate = candidates.firstOrNull()
        if (firstCandidate == null) {
            Log.e(TAG, "第一个candidate为空")
            return "抱歉，AI响应为空。"
        }
        
        Log.d(TAG, "candidate详情: $firstCandidate")
        
        // 🔍 检查finishReason，特别处理RECITATION
        val finishReason = firstCandidate.finishReason
        Log.d(TAG, "🔍 finishReason: $finishReason")
        
        when (finishReason) {
            "RECITATION" -> {
                Log.w(TAG, "⚠️ 内容被Gemini识别为可能涉及版权保护")
                return """📚 检测到受保护内容，可尝试重复提交或切换模型再提交。"""
            }
            "SAFETY" -> {
                Log.w(TAG, "⚠️ 内容被安全过滤器阻止")
                return "⚠️ 图片内容被安全过滤器阻止，无法进行分析。请尝试其他内容。"
            }
            "MAX_TOKENS" -> {
                Log.w(TAG, "⚠️ 响应长度超出限制")
                return """📄 内容过于复杂

这张图片包含的信息量很大，AI需要很长的回答来完整分析，超出了系统限制。

💡 建议：
1. 截取更小的区域，专注于特定段落
2. 分别截取不同部分进行分析
3. 手动输入关键文字进行讨论

如果是学习资料，建议按章节或段落分别截取。"""
            }
        }
        
        val content = firstCandidate.content
        if (content == null) {
            Log.e(TAG, "content为空，finishReason: $finishReason")
            
            // 根据finishReason提供更具体的错误信息
            return when (finishReason) {
                "RECITATION" -> "📚 这张图片可能包含受版权保护的内容，AI无法进行分析。请尝试截取其他内容或手动输入文字。"
                "SAFETY" -> "⚠️ 图片内容被安全过滤器阻止。"
                "MAX_TOKENS" -> "⚠️ 内容过于复杂，请尝试截取更小的区域。"
                else -> "抱歉，AI响应内容为空。可能是图片不清晰或包含特殊内容。"
            }
        }
        
        val parts = content.parts
        if (parts.isNullOrEmpty()) {
            Log.e(TAG, "parts为空")
            return "抱歉，AI响应部分为空。"
        }
        
        Log.d(TAG, "parts数量: ${parts.size}")
        
        val textParts = parts.mapNotNull { it.text }.filter { it.isNotBlank() }
        
        if (textParts.isEmpty()) {
            Log.e(TAG, "没有找到有效的文本部分")
            return "抱歉，AI没有生成文本响应。"
        }
        
        val result = textParts.joinToString("\n")
        Log.d(TAG, "解析成功，文本长度: ${result.length}")
        Log.d(TAG, "解析结果预览: ${result.take(100)}...")
        
        // 📥 输出AI返回的完整信息
        Log.d(TAG, "📥📥📥 AI返回的完整信息:")
        Log.d(TAG, "📝 完整回答: $result")
        Log.d(TAG, "🔍 finishReason: $finishReason")
        Log.d(TAG, "📊 回答字数: ${result.length}")
        
        return result
    }
    
    /**
     * 生成缓存键
     */
    private fun generateCacheKey(text: String, context: List<ChatContext>): String {
        val combined = "$text${context.joinToString { "${it.userMessage}${it.aiResponse}" }}"
        return MessageDigest.getInstance("MD5")
            .digest(combined.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 检查缓存
     */
    private fun checkCache(key: String): CachedResponse? {
        val cached = requestCache[key]
        return if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_DURATION_MS) {
            cached
        } else {
            requestCache.remove(key)
            null
        }
    }
    
    /**
     * 缓存响应
     */
    private fun cacheResponse(key: String, response: String) {
        // 限制缓存大小
        if (requestCache.size >= MAX_CACHE_SIZE) {
            val oldestKey = requestCache.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { requestCache.remove(it) }
        }
        
        requestCache[key] = CachedResponse(response, System.currentTimeMillis())
    }
    
    /**
     * 清除缓存
     */
    fun clearCache() {
        requestCache.clear()
    }
    
    /**
     * 验证 API Key
     */
    suspend fun validateApiKey(apiKey: String): Boolean {
        return try {
            val testRequest = GeminiRequest(
                contents = listOf(
                    Content(parts = listOf(Part("测试连接")), role = "user")
                )
            )
            // 使用默认模型进行测试
            val response = apiService.generateContent("gemini-2.5-flash", apiKey, testRequest)
            response.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 检查图片内容质量
     */
    private fun checkImageContentQuality(bitmap: Bitmap): ImageQualityResult {
        Log.d(TAG, "🔍 === 图片内容质量检查开始 ===")
        
        // 检查图片尺寸
        if (bitmap.width < 50 || bitmap.height < 50) {
            return ImageQualityResult(false, "图片尺寸过小（${bitmap.width}x${bitmap.height}），可能无法识别内容")
        }
        
        try {
            // 改进采样策略：从多个区域采样，而不是只从中心区域
            val regions = listOf(
                "左上角" to Pair(50, 50),
                "右上角" to Pair(bitmap.width - 150, 50),
                "左下角" to Pair(50, bitmap.height - 150),
                "右下角" to Pair(bitmap.width - 150, bitmap.height - 150),
                "中心" to Pair(bitmap.width / 2 - 50, bitmap.height / 2 - 50),
                "上中" to Pair(bitmap.width / 2 - 50, 100),
                "下中" to Pair(bitmap.width / 2 - 50, bitmap.height - 150),
                "左中" to Pair(100, bitmap.height / 2 - 50),
                "右中" to Pair(bitmap.width - 150, bitmap.height / 2 - 50)
            )
            
            var totalPixels = 0
            var totalNonZeroPixels = 0
            var totalNonBlackPixels = 0
            var totalNonWhitePixels = 0
            val allColors = mutableSetOf<Int>()
            
            Log.d(TAG, "🎨 开始多区域像素分析...")
            
            for ((regionName, pos) in regions) {
                try {
                    val (x, y) = pos
                    val regionSize = 100 // 100x100区域
                    
                    // 确保采样区域在图片范围内
                    val safeX = x.coerceIn(0, bitmap.width - regionSize)
                    val safeY = y.coerceIn(0, bitmap.height - regionSize)
                    val actualWidth = minOf(regionSize, bitmap.width - safeX)
                    val actualHeight = minOf(regionSize, bitmap.height - safeY)
                    
                    if (actualWidth <= 0 || actualHeight <= 0) {
                        Log.w(TAG, "   跳过 $regionName: 区域无效")
                        continue
                    }
                    
                    val regionPixels = IntArray(actualWidth * actualHeight)
                    bitmap.getPixels(regionPixels, 0, actualWidth, safeX, safeY, actualWidth, actualHeight)
                    
                    val nonZero = regionPixels.count { it != 0 }
                    val nonBlack = regionPixels.count { it != 0xFF000000.toInt() }
                    val nonWhite = regionPixels.count { it != 0xFFFFFFFF.toInt() }
                    val uniqueColors = regionPixels.toSet()
                    
                    totalPixels += regionPixels.size
                    totalNonZeroPixels += nonZero
                    totalNonBlackPixels += nonBlack
                    totalNonWhitePixels += nonWhite
                    allColors.addAll(uniqueColors)
                    
                    Log.d(TAG, "   $regionName: 非零=$nonZero, 非黑=$nonBlack, 非白=$nonWhite, 颜色=${uniqueColors.size}")
                    
                } catch (e: Exception) {
                    Log.w(TAG, "   检查 $regionName 区域失败", e)
                }
            }
            
            Log.d(TAG, "🎨 像素分析结果:")
            Log.d(TAG, "   - 总采样像素数: $totalPixels")
            Log.d(TAG, "   - 非零像素: $totalNonZeroPixels")
            Log.d(TAG, "   - 非黑像素: $totalNonBlackPixels") 
            Log.d(TAG, "   - 非白像素: $totalNonWhitePixels")
            Log.d(TAG, "   - 总颜色种类: ${allColors.size}")
            
            // 计算内容比例
            val nonZeroPercentage = if (totalPixels > 0) (totalNonZeroPixels * 100 / totalPixels) else 0
            val nonWhitePercentage = if (totalPixels > 0) (totalNonWhitePixels * 100 / totalPixels) else 0
            
            Log.d(TAG, "📊 内容统计:")
            Log.d(TAG, "   - 非零像素比例: $nonZeroPercentage%")
            Log.d(TAG, "   - 非白像素比例: $nonWhitePercentage%")
            
            // 放宽判断标准，避免误判
            when {
                totalPixels == 0 -> {
                    return ImageQualityResult(false, "无法采样到有效像素")
                }
                
                allColors.size == 1 -> {
                    val color = allColors.first()
                    val colorName = when (color) {
                        0 -> "透明"
                        0xFF000000.toInt() -> "纯黑色"
                        0xFFFFFFFF.toInt() -> "纯白色"
                        else -> "纯色(${String.format("#%08X", color)})"
                    }
                    
                    // 如果是纯白色但采样区域较小，可能是采样到了空白区域，给一次机会
                    if (color == 0xFFFFFFFF.toInt() && totalPixels < bitmap.width * bitmap.height / 4) {
                        Log.w(TAG, "⚠️ 检测到纯白色，但采样区域较小，可能是局部空白，允许通过")
                        return ImageQualityResult(true, "检测到纯白色但采样有限，允许AI分析")
                    }
                    
                    return ImageQualityResult(false, "图片为${colorName}，没有可识别的内容。请确保截取包含文字或图像的区域。")
                }
                
                // 放宽颜色种类要求：从5种降低到3种
                allColors.size < 3 -> {
                    Log.w(TAG, "⚠️ 颜色种类较少(${allColors.size})，但仍允许AI分析")
                    return ImageQualityResult(true, "颜色种类较少但允许AI分析，颜色数: ${allColors.size}")
                }
                
                // 放宽内容要求：从50%降低到20%
                nonZeroPercentage < 20 -> {
                    Log.w(TAG, "⚠️ 内容较少($nonZeroPercentage%)，但仍允许AI分析")
                    return ImageQualityResult(true, "内容较少但允许AI分析，内容比例: $nonZeroPercentage%")
                }
                
                else -> {
                    val contentScore = (allColors.size * 100 / totalPixels).coerceAtMost(100)
                    Log.d(TAG, "✅ 图片质量检查通过")
                    return ImageQualityResult(true, "图片内容丰富度: $contentScore%，颜色种类: ${allColors.size}，非零像素: $nonZeroPercentage%")
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ 像素分析异常，允许通过", e)
            return ImageQualityResult(true, "分析异常但允许通过: ${e.message}")
        }
    }
}

/**
 * 聊天上下文数据类
 */
data class ChatContext(
    val userMessage: String,
    val aiResponse: String
)

/**
 * 图片质量检查结果
 */
data class ImageQualityResult(
    val isValid: Boolean,
    val reason: String,
    val description: String = reason
)

/**
 * 缓存响应数据类
 */
private data class CachedResponse(
    val response: String,
    val timestamp: Long
) 