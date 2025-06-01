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
     * 更新会话信息
     */
    private suspend fun updateSession(sessionId: String, bookName: String, appPackage: String) {
        val existingSession = chatDao.getSession(sessionId)
        val currentTime = System.currentTimeMillis()
        
        if (existingSession != null) {
            // 更新现有会话
            val updatedSession = existingSession.copy(
                lastMessageTime = currentTime,
                messageCount = existingSession.messageCount + 1
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
        return "${appPackage}_${bookName}_${timestamp}_${random}"
            .replace("[^a-zA-Z0-9_]".toRegex(), "_")
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
            // 创建聊天记录实体
            val chatEntity = ChatEntity(
                sessionId = sessionId,
                bookName = "笔记", // 使用更友好的默认值
                appPackage = "com.readassist", // 使用本应用包名作为默认值
                userMessage = chatItem.userMessage,
                aiResponse = chatItem.aiMessage,
                promptTemplate = "", // 可以设置默认值或从偏好设置中获取
                timestamp = System.currentTimeMillis()
            )
            
            // 尝试获取会话信息以补充缺失字段
            val session = chatDao.getSession(sessionId)
            if (session != null) {
                val updatedEntity = chatEntity.copy(
                    bookName = session.bookName,
                    appPackage = session.appPackage
                )
                // 保存消息
                chatDao.insertChatMessage(updatedEntity)
                
                // 更新会话信息
                updateSession(sessionId, session.bookName, session.appPackage)
            } else {
                // 无法找到会话信息，使用默认值创建会话
                Log.d("ChatRepository", "无法找到会话 $sessionId，使用默认值创建")
                
                // 从会话ID中尝试提取应用和书籍信息
                val parts = sessionId.split("_")
                val extractedApp = if (parts.size > 0) parts[0] else "com.readassist"
                val extractedBook = if (parts.size > 1) parts[1] else "笔记"
                
                // 保存消息
                val finalEntity = chatEntity.copy(
                    bookName = extractedBook,
                    appPackage = extractedApp
                )
                chatDao.insertChatMessage(finalEntity)
                
                // 创建会话
                updateSession(sessionId, extractedBook, extractedApp)
            }
        } catch (e: Exception) {
            Log.e("ChatRepository", "保存消息失败", e)
            throw e
        }
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
            appendLine("=".repeat(50))
            
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
                appendLine("=".repeat(50))
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