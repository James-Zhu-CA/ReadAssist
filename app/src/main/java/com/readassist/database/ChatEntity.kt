package com.readassist.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@Parcelize
@Entity(tableName = "chat_messages")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // 会话信息
    val sessionId: String,          // 会话 ID（用于分组对话）
    val bookName: String,           // 书名或应用名
    val appPackage: String,         // 来源应用包名
    
    // 消息内容
    val userMessage: String,        // 用户输入/选中的文本
    val aiResponse: String,         // AI 回复内容
    val promptTemplate: String,     // 使用的提示模板
    
    // 元数据
    val timestamp: Long,            // 创建时间戳
    val isBookmarked: Boolean = false,  // 是否收藏
    val tokenCount: Int = 0,        // 消耗的 token 数量（用于统计）
    
    // 上下文信息
    val contextBefore: String = "", // 前文上下文
    val contextAfter: String = ""   // 后文上下文
) : Parcelable

/**
 * 聊天会话摘要实体
 */
@Parcelize
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val sessionId: String,
    
    val bookName: String,
    val appPackage: String,
    val firstMessageTime: Long,
    val lastMessageTime: Long,
    val messageCount: Int,
    val totalTokens: Int = 0,
    val isArchived: Boolean = false
) : Parcelable 