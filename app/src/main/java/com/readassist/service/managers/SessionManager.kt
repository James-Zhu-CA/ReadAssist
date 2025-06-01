package com.readassist.service.managers

import android.util.Log
import com.readassist.database.ChatSessionEntity
import com.readassist.repository.ChatRepository
import kotlinx.coroutines.flow.first

/**
 * 管理聊天会话
 */
class SessionManager(
    private val chatRepository: ChatRepository
) {
    companion object {
        private const val TAG = "SessionManager"
    }
    
    // 当前会话状态
    private var currentSessionId: String = ""
    private var currentAppPackage: String = ""
    private var currentBookName: String = ""
    private var isNewSessionRequested: Boolean = false
    
    /**
     * 确保会话ID已初始化
     */
    fun ensureSessionIdInitialized(): String {
        if (currentSessionId.isEmpty() || isNewSessionRequested) {
            Log.d(TAG, "⚠️ 会话ID为空或请求新会话，初始化新会话")
            
            // 使用当前上下文信息或默认值
            val appPackage = currentAppPackage.ifEmpty { "com.readassist" }
            val bookName = if (currentBookName.isEmpty() || 
                               currentBookName.startsWith("android.") || 
                               currentBookName.contains("Layout") || 
                               currentBookName.contains("View") || 
                               currentBookName.contains(".")) {
                "笔记"
            } else {
                currentBookName
            }
            
            // 确保不使用"unknown"作为包名
            val finalAppPackage = if (appPackage == "unknown") "com.readassist" else appPackage
            
            // 生成新会话ID
            currentSessionId = generateSessionId(finalAppPackage, bookName)
            Log.d(TAG, "✅ 初始化新会话ID: $currentSessionId, 应用=$finalAppPackage, 书籍=$bookName")
            
            // 重置新会话请求标志
            isNewSessionRequested = false
        } else {
            Log.d(TAG, "✅ 会话ID已存在: $currentSessionId")
        }
        
        return currentSessionId
    }
    
    /**
     * 生成会话ID
     */
    fun generateSessionId(appPackage: String, bookName: String): String {
        return chatRepository.generateSessionId(appPackage, bookName)
    }
    
    /**
     * 请求新会话
     */
    fun requestNewSession() {
        isNewSessionRequested = true
        Log.d(TAG, "📝 新会话请求已设置")
    }
    
    /**
     * 更新会话（如果需要）
     * @return 如果会话ID已更新则返回true
     */
    suspend fun updateSessionIfNeeded(appPackage: String, bookName: String): Boolean {
        // 保存当前值以供比较
        val oldSessionId = currentSessionId
        
        // 保存当前应用和书籍名称
        setCurrentApp(appPackage)
        setCurrentBook(bookName)
        
        // 确保使用实际应用包名和书籍名称，只在完全为空时才使用默认值
        val currentApp = currentAppPackage.ifEmpty { "com.readassist" } 
        
        // 检查书籍名称是否是有意义的值，避免使用空字符串或无意义的类名
        val currentBook = sanitizeBookName(currentBookName)
        
        // 打印诊断信息
        Log.d(TAG, "📚 当前书籍状态检查 - 原始: '$currentBookName', 处理后: '$currentBook'")

        if (isNewSessionRequested) {
            Log.d(TAG, "开始新对话会话")
            currentSessionId = chatRepository.generateSessionId(currentApp, currentBook)
            isNewSessionRequested = false // 重置标志
            Log.d(TAG, "新会话ID: $currentSessionId")
            return true // 会话ID已更改
        }

        // 检查会话ID是否与当前上下文匹配
        // 会话ID格式: appPackage_bookName_timestamp
        val sessionPrefix = "${currentApp}_${currentBook}_"
        val currentContextMatchesSession = currentSessionId.startsWith(sessionPrefix)

        if (currentSessionId.isEmpty() || !currentContextMatchesSession) {
            Log.d(TAG, "🔄 会话ID为空('$currentSessionId')或上下文已变更。新上下文: $currentApp / $currentBook。正在查找/创建会话。")
            val recentSession = findRecentSessionForApp(currentApp, currentBook)
            
            if (recentSession != null) {
                currentSessionId = recentSession.sessionId
                Log.d(TAG, "🔄 恢复已存在的会话: $currentSessionId")
            } else {
                currentSessionId = chatRepository.generateSessionId(currentApp, currentBook)
                Log.d(TAG, "🔄 创建新会话(未找到最近的): $currentSessionId")
            }
            return oldSessionId != currentSessionId // 如果会话ID确实改变了则返回true
        }
        
        Log.d(TAG, "🔄 会话ID $currentSessionId 对 $currentApp / $currentBook 仍然有效。")
        return false // 会话ID未改变
    }
    
    /**
     * 查找最近的会话
     */
    private suspend fun findRecentSessionForApp(appPackage: String, bookName: String): ChatSessionEntity? {
        Log.d(TAG, "🔍🔍🔍 findRecentSessionForApp 开始")
        Log.d(TAG, "🔍 查找参数 - 应用: '$appPackage', 书籍: '$bookName'")
        
        return try {
            Log.d(TAG, "🔍 获取活跃会话列表...")
            val sessionsFlow = chatRepository.getActiveSessions()
            val sessionsList = sessionsFlow.first()
            
            Log.d(TAG, "🔍 找到 ${sessionsList.size} 个活跃会话")
            sessionsList.forEachIndexed { index, session ->
                Log.d(TAG, "🔍 会话 $index: ID=${session.sessionId}, 应用='${session.appPackage}', 书籍='${session.bookName}', 首次=${session.firstMessageTime}, 最后=${session.lastMessageTime}")
            }
            
            val matchedSession = sessionsList.find { session ->
                val appMatch = session.appPackage == appPackage
                val bookMatch = session.bookName == bookName
                Log.d(TAG, "🔍 匹配检查: 应用匹配=$appMatch ('${session.appPackage}' == '$appPackage'), 书籍匹配=$bookMatch ('${session.bookName}' == '$bookName')")
                appMatch && bookMatch
            }
            
            if (matchedSession != null) {
                Log.d(TAG, "✅ 找到匹配会话: ${matchedSession.sessionId}")
            } else {
                Log.d(TAG, "❌ 未找到匹配会话")
            }
            
            matchedSession
        } catch (e: Exception) {
            Log.e(TAG, "❌ 查找最近会话失败", e)
            null
        }
    }
    
    /**
     * 尝试恢复现有会话
     */
    suspend fun restoreRecentSession(): Boolean {
        try {
            Log.d(TAG, "🔍 尝试恢复最近会话...")
            
            // 如果当前应用和书籍信息为空，无法恢复
            if (currentAppPackage.isEmpty()) {
                Log.d(TAG, "应用包名为空，无法恢复会话")
                return false
            }
            
            // 获取所有活跃会话
            val sessions = chatRepository.getActiveSessions().first()
            Log.d(TAG, "🔍 找到 ${sessions.size} 个活跃会话")
            
            // 记录所有会话信息以便调试
            sessions.forEachIndexed { index, session ->
                Log.d(TAG, "🔍 会话 $index: ID=${session.sessionId}, 应用='${session.appPackage}', 书籍='${session.bookName}', 首次=${session.firstMessageTime}, 最后=${session.lastMessageTime}")
            }
            
            // 过滤掉可能有问题的会话
            val validSessions = sessions.filter { session -> 
                !session.sessionId.startsWith("unknown_") && 
                !session.bookName.contains("android.widget") &&
                session.appPackage.isNotEmpty() &&
                session.bookName.isNotEmpty()
            }
            
            Log.d(TAG, "🔍 过滤后有效会话数量: ${validSessions.size}")
            
            // 首先尝试恢复当前应用的会话
            var recentSession: ChatSessionEntity? = null
            
            if (currentAppPackage.isNotEmpty()) {
                Log.d(TAG, "🔍 尝试查找当前应用的会话: $currentAppPackage")
                
                // 查找与当前应用匹配的最新会话
                recentSession = validSessions
                    .filter { it.appPackage == currentAppPackage }
                    .maxByOrNull { it.lastMessageTime }
            }
            
            // 如果找不到当前应用的会话，选择任何最近的有效会话
            if (recentSession == null && validSessions.isNotEmpty()) {
                Log.d(TAG, "📚 未找到当前应用的会话，尝试获取最近有效会话")
                recentSession = validSessions.maxByOrNull { it.lastMessageTime }
            }
            
            // 如果找到会话，使用它
            if (recentSession != null) {
                currentSessionId = recentSession.sessionId
                currentAppPackage = recentSession.appPackage
                currentBookName = recentSession.bookName
                
                Log.d(TAG, "✅ 恢复会话成功: $currentSessionId (${recentSession.bookName})")
                return true
            } else {
                // 如果没有找到任何有效会话，创建新会话
                Log.d(TAG, "📝 没有找到有效会话，需要创建新会话")
                isNewSessionRequested = true // 强制创建新会话
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复会话失败", e)
            
            // 失败时确保有一个会话ID
            isNewSessionRequested = true // 强制创建新会话
            return false
        }
    }
    
    /**
     * 设置当前应用包名
     */
    fun setCurrentApp(appPackage: String) {
        if (appPackage.isNotEmpty() && appPackage != "unknown") {
            currentAppPackage = appPackage
        }
    }
    
    /**
     * 设置当前书籍名称
     */
    fun setCurrentBook(bookName: String) {
        currentBookName = bookName
    }
    
    /**
     * 获取当前会话ID
     */
    fun getCurrentSessionId(): String {
        return currentSessionId
    }
    
    /**
     * 获取当前应用包名
     */
    fun getCurrentAppPackage(): String {
        return currentAppPackage
    }
    
    /**
     * 获取当前书籍名称
     */
    fun getCurrentBookName(): String {
        return currentBookName
    }
    
    /**
     * 获取处理后的书籍名称
     */
    fun getSanitizedBookName(): String {
        return sanitizeBookName(currentBookName)
    }
    
    /**
     * 获取处理后的应用包名
     */
    fun getSanitizedAppPackage(): String {
        return if (currentAppPackage.isEmpty() || currentAppPackage == "unknown") {
            "com.readassist"
        } else {
            currentAppPackage
        }
    }
    
    /**
     * 处理书籍名称
     */
    private fun sanitizeBookName(bookName: String): String {
        return when {
            bookName.isEmpty() -> "笔记"
            bookName.startsWith("android.") -> "笔记" // 过滤掉Android类名
            bookName.contains("Layout") -> "笔记" // 过滤掉布局类名
            bookName.contains("View") -> "笔记" // 过滤掉视图类名
            bookName.contains(".") -> "笔记" // 过滤掉包含点号的类名
            else -> bookName // 使用有意义的书籍名称
        }
    }
} 