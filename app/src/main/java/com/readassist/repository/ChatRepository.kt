package com.readassist.repository

import com.readassist.database.ChatDao
import com.readassist.database.ChatEntity
import com.readassist.database.ChatSessionEntity
import com.readassist.network.ApiResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.*
import android.util.Log

class ChatRepository(
    private val chatDao: ChatDao,
    private val geminiRepository: GeminiRepository
) {
    
    companion object {
        private const val MAX_CONTEXT_MESSAGES = 10
    }
    
    /**
     * 发送消息并保存到数据库
     */
    suspend fun sendMessage(
        sessionId: String,
        userMessage: String,
        bookName: String,
        appPackage: String,
        promptTemplate: String
    ): ApiResult<ChatEntity> {
        
        try {
            // 获取上下文消息
            val contextMessages = chatDao.getRecentMessagesForContext(sessionId, MAX_CONTEXT_MESSAGES)
            val context = contextMessages.map { 
                ChatContext(it.userMessage, it.aiResponse) 
            }.reversed()
            
            // 调用 Gemini API
            val apiResult = geminiRepository.sendMessage(userMessage, context)
            
            return when (apiResult) {
                is ApiResult.Success -> {
                    // 创建聊天记录
                    val chatEntity = ChatEntity(
                        sessionId = sessionId,
                        bookName = bookName,
                        appPackage = appPackage,
                        userMessage = userMessage,
                        aiResponse = apiResult.data,
                        promptTemplate = promptTemplate,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    // 保存消息
                    val messageId = chatDao.insertChatMessage(chatEntity)
                    val savedMessage = chatEntity.copy(id = messageId)
                    
                    // 更新或创建会话
                    updateSession(sessionId, bookName, appPackage)
                    
                    ApiResult.Success(savedMessage)
                }
                is ApiResult.Error -> ApiResult.Error(apiResult.exception)
                is ApiResult.NetworkError -> ApiResult.NetworkError(apiResult.message)
            }
        } catch (e: Exception) {
            return ApiResult.Error(e)
        }
    }
    
    /**
     * 直接保存聊天实体
     */
    suspend fun saveChatEntity(chatEntity: ChatEntity): Long {
        return chatDao.insertChatMessage(chatEntity)
    }
    
    /**
     * 公开更新会话方法
     */
    suspend fun updateSession(sessionId: String, bookName: String, appPackage: String) {
        val existingSession = chatDao.getSession(sessionId)
        val currentTime = System.currentTimeMillis()
        
        if (existingSession != null) {
            // 更新现有会话
            val updatedSession = existingSession.copy(
                lastMessageTime = currentTime,
                messageCount = existingSession.messageCount + 1,
                // 确保使用最新的书籍和应用信息
                bookName = bookName,
                appPackage = appPackage
            )
            chatDao.insertOrUpdateSession(updatedSession)
        } else {
            // 创建新会话
            val newSession = ChatSessionEntity(
                sessionId = sessionId,
                bookName = bookName,
                appPackage = appPackage,
                firstMessageTime = currentTime,
                lastMessageTime = currentTime,
                messageCount = 1
            )
            chatDao.insertOrUpdateSession(newSession)
        }
    }
    
    /**
     * 获取会话的聊天消息
     */
    fun getChatMessages(sessionId: String): Flow<List<ChatEntity>> {
        return chatDao.getChatMessagesBySession(sessionId)
    }
    
    /**
     * 获取所有活跃会话
     */
    fun getActiveSessions(): Flow<List<ChatSessionEntity>> {
        return chatDao.getActiveSessions()
    }
    
    /**
     * 获取所有会话
     */
    fun getAllSessions(): Flow<List<ChatSessionEntity>> {
        return chatDao.getAllSessions()
    }
    
    /**
     * 获取收藏的消息
     */
    fun getBookmarkedMessages(): Flow<List<ChatEntity>> {
        return chatDao.getBookmarkedMessages()
    }
    
    /**
     * 搜索消息
     */
    fun searchMessages(query: String): Flow<List<ChatEntity>> {
        return chatDao.searchMessages(query)
    }
    
    /**
     * 切换消息收藏状态
     */
    suspend fun toggleBookmark(messageId: Long, isBookmarked: Boolean) {
        chatDao.updateBookmarkStatus(messageId, isBookmarked)
    }
    
    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) {
        chatDao.deleteSessionCompletely(sessionId)
    }
    
    /**
     * 归档会话
     */
    suspend fun archiveSession(sessionId: String, isArchived: Boolean) {
        chatDao.updateSessionArchiveStatus(sessionId, isArchived)
    }
    
    /**
     * 生成会话ID
     */
    fun generateSessionId(appPackage: String, bookName: String): String {
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().take(8)
        
        // 为了防止混淆，不替换应用包名中的点号
        val sanitizedAppPackage = appPackage.replace("[^a-zA-Z0-9.]".toRegex(), "_")
        val sanitizedBookName = bookName.replace("[^a-zA-Z0-9 ]".toRegex(), "_").trim()
        
        return "${sanitizedAppPackage}__${sanitizedBookName}__${timestamp}_${random}"
    }
    
    /**
     * 获取统计信息
     */
    suspend fun getStatistics(): ChatStatistics {
        return ChatStatistics(
            totalMessages = chatDao.getTotalMessageCount(),
            totalSessions = chatDao.getTotalSessionCount(),
            totalTokens = chatDao.getTotalTokenUsage() ?: 0
        )
    }
    
    /**
     * 清除所有数据
     */
    suspend fun clearAllData() {
        chatDao.deleteAllMessages()
        chatDao.deleteAllSessions()
        geminiRepository.clearCache()
    }
    
    /**
     * 添加消息到会话
     */
    suspend fun addMessageToSession(sessionId: String, chatItem: com.readassist.service.ChatItem) {
        try {
            // 获取会话信息以补充缺失字段
            val session = chatDao.getSession(sessionId)
            
            // 从会话ID中尝试提取应用和书籍信息（如果没有找到现有会话）
            val (extractedApp, extractedBook) = if (session == null) {
                extractAppAndBookFromSessionId(sessionId)
            } else {
                Pair(session.appPackage, session.bookName)
            }
            
            // 创建聊天记录实体
            val chatEntity = ChatEntity(
                sessionId = sessionId,
                bookName = extractedBook,
                appPackage = extractedApp,
                userMessage = chatItem.userMessage,
                aiResponse = chatItem.aiMessage,
                promptTemplate = "", // 可以设置默认值或从偏好设置中获取
                timestamp = System.currentTimeMillis()
            )
            
            // 保存消息
            chatDao.insertChatMessage(chatEntity)
            
            // 更新会话
            updateSession(sessionId, extractedBook, extractedApp)
            
        } catch (e: Exception) {
            Log.e("ChatRepository", "保存消息失败", e)
            throw e
        }
    }
    
    /**
     * 从会话ID中提取应用和书籍信息
     * 会话ID格式: appPackage__bookName__timestamp_random
     * 或旧格式: appPackage_bookName_timestamp_random
     */
    private fun extractAppAndBookFromSessionId(sessionId: String): Pair<String, String> {
        // 处理新格式 (使用双下划线分隔主要部分)
        if (sessionId.contains("__")) {
            val mainParts = sessionId.split("__")
            
            val extractedApp = if (mainParts.isNotEmpty() && mainParts[0].isNotEmpty()) {
                mainParts[0]
            } else {
                "com.readassist"
            }
            
            val extractedBook = if (mainParts.size > 1 && mainParts[1].isNotEmpty()) {
                mainParts[1]
            } else {
                "阅读笔记"
            }
            
            return Pair(extractedApp, extractedBook)
        }
        
        // 处理旧格式 (使用单下划线分隔)
        val parts = sessionId.split("_").filter { it.isNotEmpty() }
        
        // 检查是否包含"com"作为第一部分，尝试重建完整的应用包名
        val extractedApp = if (parts.isNotEmpty()) {
            if (parts[0] == "com" && parts.size > 1) {
                // 重建类似"com.readassist"的完整包名
                "com.${parts[1]}"
            } else if (parts[0].startsWith("com")) {
                parts[0]
            } else {
                "com.readassist"
            }
        } else {
            "com.readassist"
        }
        
        // 确定书籍名称
        val bookNameIndex = if (parts[0] == "com") 2 else 1
        val extractedBook = if (parts.size > bookNameIndex && parts[bookNameIndex].isNotEmpty() && 
                               !parts[bookNameIndex].contains("android.") && 
                               !parts[bookNameIndex].contains(".")) {
            parts[bookNameIndex]
        } else {
            "阅读笔记"
        }
        
        return Pair(extractedApp, extractedBook)
    }
    
    /**
     * 导出聊天记录
     */
    suspend fun exportChatHistory(sessionId: String? = null): String {
        val messages = if (sessionId != null) {
            chatDao.getChatMessagesBySession(sessionId).first()
        } else {
            // 导出所有消息（按会话分组）
            val sessions = chatDao.getAllSessions().first()
            sessions.flatMap { session ->
                chatDao.getChatMessagesBySession(session.sessionId).first()
            }
        }
        
        return buildString {
            appendLine("ReadAssist 聊天记录导出")
            appendLine("导出时间: ${Date()}")
            appendLine("-".repeat(50))
            
            messages.groupBy { it.sessionId }.forEach { (sessionId, sessionMessages) ->
                val session = sessionMessages.firstOrNull()
                appendLine("\n会话: ${session?.bookName ?: "未知"}")
                appendLine("应用: ${session?.appPackage ?: "未知"}")
                appendLine("时间: ${Date(session?.timestamp ?: 0)}")
                appendLine("-".repeat(30))
                
                sessionMessages.forEach { message ->
                    appendLine("\n用户: ${message.userMessage}")
                    appendLine("AI: ${message.aiResponse}")
                    if (message.isBookmarked) {
                        appendLine("★ 已收藏")
                    }
                }
                appendLine("-".repeat(50))
            }
        }
    }
    
    /**
     * 记录会话ID分解结果（用于调试）
     */
    fun logSessionIdParts(sessionId: String) {
        val (app, book) = extractAppAndBookFromSessionId(sessionId)
        Log.d("ChatRepository", "🔍 会话ID分解 - ID: $sessionId")
        Log.d("ChatRepository", "🔍 提取结果 - 应用: '$app', 书籍: '$book'")
        
        // 记录分解过程
        if (sessionId.contains("__")) {
            val mainParts = sessionId.split("__")
            Log.d("ChatRepository", "🔍 双下划线分隔 - 部分数量: ${mainParts.size}")
            mainParts.forEachIndexed { index, part ->
                Log.d("ChatRepository", "🔍   部分[$index]: '$part'")
            }
        } else {
            val parts = sessionId.split("_").filter { it.isNotEmpty() }
            Log.d("ChatRepository", "🔍 单下划线分隔 - 非空部分数量: ${parts.size}")
            parts.forEachIndexed { index, part ->
                Log.d("ChatRepository", "🔍   部分[$index]: '$part'")
            }
        }
    }
}

/**
 * 聊天统计信息
 */
data class ChatStatistics(
    val totalMessages: Int,
    val totalSessions: Int,
    val totalTokens: Int
) 