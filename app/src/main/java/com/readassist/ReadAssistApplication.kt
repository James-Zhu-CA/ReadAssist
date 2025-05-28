package com.readassist

import android.app.Application
import com.readassist.database.AppDatabase
import com.readassist.repository.ChatRepository
import com.readassist.repository.GeminiRepository
import com.readassist.utils.PreferenceManager

class ReadAssistApplication : Application() {

    // 全局数据库实例
    val database by lazy { AppDatabase.getDatabase(this) }
    
    // 全局偏好设置管理器
    val preferenceManager by lazy { PreferenceManager(this) }
    
    // 全局仓库实例
    val geminiRepository by lazy { GeminiRepository(preferenceManager) }
    val chatRepository by lazy { ChatRepository(database.chatDao(), geminiRepository) }

    override fun onCreate() {
        super.onCreate()
        
        // 初始化全局配置
        initializeApp()
    }

    private fun initializeApp() {
        // 这里可以添加全局初始化逻辑
        // 比如配置日志、崩溃报告等
    }
} 