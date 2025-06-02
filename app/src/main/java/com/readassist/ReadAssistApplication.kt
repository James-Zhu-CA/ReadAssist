package com.readassist

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.multidex.MultiDex
import com.readassist.database.AppDatabase
import com.readassist.repository.ChatRepository
import com.readassist.repository.GeminiRepository
import com.readassist.utils.PreferenceManager

class ReadAssistApplication : Application() {

    companion object {
        private const val TAG = "ReadAssistApplication"
    }

    // 全局可访问的依赖组件
    lateinit var preferenceManager: PreferenceManager
    lateinit var database: AppDatabase
    lateinit var chatRepository: ChatRepository
    
    // 全局仓库实例
    lateinit var geminiRepository: GeminiRepository

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 初始化 MultiDex
        MultiDex.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        
        // 初始化偏好设置管理器
        preferenceManager = PreferenceManager(applicationContext)
        
        // 初始化数据库
        database = AppDatabase.getDatabase(applicationContext)
        
        // 初始化仓库
        geminiRepository = GeminiRepository(preferenceManager)
        chatRepository = ChatRepository(database.chatDao(), geminiRepository)
        
        // 这里可以放置其他全局初始化逻辑
        // 比如配置日志、崩溃报告等
    }

    /**
     * 处理截屏权限授予事件
     * 这是一个应用级别的处理，确保权限状态可以被正确传递
     */
    fun onScreenshotPermissionGranted(resultCode: Int, data: Intent) {
        Log.d(TAG, "✅ 应用级别接收到截屏权限授予")
        
        try {
            // 先清除旧的权限数据
            preferenceManager.clearScreenshotPermission()
            
            // 转换Intent为Uri格式存储
            val resultDataUri = data.toUri(Intent.URI_INTENT_SCHEME)
            Log.d(TAG, "权限数据Uri: $resultDataUri")
            
            // 保存权限状态
            preferenceManager.setScreenshotPermissionGranted(true)
            preferenceManager.setScreenshotResultCode(resultCode)
            preferenceManager.setScreenshotResultDataUri(resultDataUri)
            
            // 启动截屏服务
            val serviceIntent = Intent(this, com.readassist.service.ScreenshotService::class.java).apply {
                action = com.readassist.service.ScreenshotService.ACTION_START_SCREENSHOT
                putExtra(com.readassist.service.ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                putExtra(com.readassist.service.ScreenshotService.EXTRA_RESULT_DATA, data)
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            
            Log.d(TAG, "✅ 权限状态已保存，截屏服务已启动")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 应用级别处理权限失败", e)
            // 发生错误时清除权限数据
            preferenceManager.clearScreenshotPermission()
        }
    }
} 