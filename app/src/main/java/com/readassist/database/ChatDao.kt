package com.readassist.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    
    // === 聊天消息操作 ===
    
    @Insert
    suspend fun insertChatMessage(message: ChatEntity): Long
    
    @Update
    suspend fun updateChatMessage(message: ChatEntity)
    
    @Delete
    suspend fun deleteChatMessage(message: ChatEntity)
    
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getChatMessagesBySession(sessionId: String): Flow<List<ChatEntity>>
    
    @Query("SELECT * FROM chat_messages WHERE isBookmarked = 1 ORDER BY timestamp DESC")
    fun getBookmarkedMessages(): Flow<List<ChatEntity>>
    
    @Query("SELECT * FROM chat_messages WHERE userMessage LIKE '%' || :query || '%' OR aiResponse LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchMessages(query: String): Flow<List<ChatEntity>>
    
    @Query("UPDATE chat_messages SET isBookmarked = :isBookmarked WHERE id = :messageId")
    suspend fun updateBookmarkStatus(messageId: Long, isBookmarked: Boolean)
    
    // === 会话操作 ===
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSession(session: ChatSessionEntity)
    
    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)
    
    @Query("SELECT * FROM chat_sessions ORDER BY lastMessageTime DESC")
    fun getAllSessions(): Flow<List<ChatSessionEntity>>
    
    @Query("SELECT * FROM chat_sessions WHERE isArchived = 0 ORDER BY lastMessageTime DESC")
    fun getActiveSessions(): Flow<List<ChatSessionEntity>>
    
    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun getSession(sessionId: String): ChatSessionEntity?
    
    @Query("UPDATE chat_sessions SET isArchived = :isArchived WHERE sessionId = :sessionId")
    suspend fun updateSessionArchiveStatus(sessionId: String, isArchived: Boolean)
    
    // === 统计查询 ===
    
    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getTotalMessageCount(): Int
    
    @Query("SELECT COUNT(*) FROM chat_sessions")
    suspend fun getTotalSessionCount(): Int
    
    @Query("SELECT SUM(totalTokens) FROM chat_sessions")
    suspend fun getTotalTokenUsage(): Int?
    
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForContext(sessionId: String, limit: Int = 10): List<ChatEntity>
    
    // === 数据清理 ===
    
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteAllMessagesInSession(sessionId: String)
    
    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteSessionById(sessionId: String)
    
    @Query("DELETE FROM chat_messages")
    suspend fun deleteAllMessages()
    
    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAllSessions()
    
    // 组合操作：删除会话及其所有消息
    @Transaction
    suspend fun deleteSessionCompletely(sessionId: String) {
        deleteAllMessagesInSession(sessionId)
        deleteSessionById(sessionId)
    }
} 