package com.readassist.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.readassist.ReadAssistApplication
import com.readassist.service.ScreenshotService
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/**
 * 用于接收截屏权限请求结果的Activity
 * 此Activity不显示任何UI，只是作为接收权限请求结果的中介
 */
class ScreenshotResultActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ScreenshotResultActivity"
        const val ACTION_PROCESS_SCREENSHOT_RESULT = "com.readassist.PROCESS_SCREENSHOT_RESULT"
        private const val REQUEST_SCREENSHOT_PERMISSION_CODE = 1002
    }
    
    private var isResultProcessed = false
    private lateinit var app: ReadAssistApplication
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d(TAG, "=== ScreenshotResultActivity创建 ===")
        Log.d(TAG, "Intent: action=${intent?.action}, extras=${intent?.extras?.keySet()?.joinToString()}")
        
        // 设置无窗口背景主题，使活动看起来像对话框
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        
        // 初始化应用实例
        app = application as ReadAssistApplication
        
        // 检查是否已经处理过
        if (isResultProcessed) {
            Log.w(TAG, "⚠️ 结果已处理，避免重复处理")
            finish()
            return
        }
        
        // 检查是否是处理结果的情况
        val resultCode = intent?.getIntExtra("resultCode", -1) ?: -1
        val resultData = intent?.getParcelableExtra<Intent>("resultData")
        
        if (resultCode != -1 && resultData != null) {
            // 这是从系统权限请求返回的情况
            Log.d(TAG, "处理已有的权限结果: resultCode=$resultCode")
            processPermissionResult(resultCode, resultData)
        } else {
            // 这是新启动的情况，需要主动请求权限
            Log.d(TAG, "主动请求截屏权限，没有找到现有结果")
            requestScreenshotPermission()
        }
    }
    
    /**
     * 请求截屏权限
     */
    private fun requestScreenshotPermission() {
        Log.d(TAG, "开始请求截屏权限...")
        
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            
            // 请求截屏权限
            startActivityForResult(intent, REQUEST_SCREENSHOT_PERMISSION_CODE)
            Log.d(TAG, "截屏权限请求已发送")
            
            // 设置超时检查
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isResultProcessed && !isFinishing && !isDestroyed) {
                    Log.w(TAG, "权限请求超时，关闭Activity")
                    sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "权限请求超时")
                    finish()
                }
            }, 30000) // 30秒超时
            
        } catch (e: Exception) {
            Log.e(TAG, "请求截屏权限失败", e)
            sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "请求权限失败: ${e.message}")
            finish()
        }
    }
    
    /**
     * 处理权限请求结果
     */
    private fun processPermissionResult(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "处理权限请求结果: resultCode=$resultCode")
        
        try {
            if (resultCode == Activity.RESULT_OK) {
                // 权限授予成功
                Log.d(TAG, "✅ 截屏权限授予成功")
                
                // 保存权限数据到偏好设置
                app.onScreenshotPermissionGranted(resultCode, resultData)
                
                // 广播权限已授予
                sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
                
                // 发送振动反馈
                provideHapticFeedback()
                
                // 标记结果已处理
                isResultProcessed = true
                
                // 尝试立即初始化截屏服务
                initializeScreenshotService(resultCode, resultData)
            } else {
                // 权限授予失败
                Log.e(TAG, "❌ 截屏权限授予失败: resultCode=$resultCode")
                
                // 重置权限状态
                app.preferenceManager.setScreenshotPermissionGranted(false)
                
                // 广播权限被拒绝
                sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_DENIED")
                
                // 标记结果已处理
                isResultProcessed = true
            }
        } catch (e: Exception) {
            // 处理结果时出现异常
            Log.e(TAG, "❌ 处理权限结果异常", e)
            
            // 重置权限状态
            app.preferenceManager.setScreenshotPermissionGranted(false)
            
            // 广播权限错误
            sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "处理权限结果时出错: ${e.message}")
        }
        
        // 延迟关闭活动
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, 1000)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "收到onActivityResult: requestCode=$requestCode, resultCode=$resultCode")
        
        if (requestCode == REQUEST_SCREENSHOT_PERMISSION_CODE) {
            if (data != null) {
                processPermissionResult(resultCode, data)
            } else {
                Log.e(TAG, "权限请求返回数据为null")
                sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_DENIED")
                finish()
            }
        }
    }
    
    /**
     * 提供触觉反馈
     */
    private fun provideHapticFeedback() {
        try {
            if (checkSelfPermission(android.Manifest.permission.VIBRATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "提供触觉反馈失败", e)
        }
    }
    
    /**
     * 初始化截屏服务
     */
    private fun initializeScreenshotService(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "开始初始化截屏服务...")
        
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
            
            if (mediaProjection != null) {
                Log.d(TAG, "✅ 媒体投影创建成功")
                
                // 在服务中保存媒体投影实例 - 如果需要的话，可以通过服务绑定传递
                
                // 测试权限是否真的有效
                testScreenshotPermission(mediaProjection)
            } else {
                Log.e(TAG, "❌ 媒体投影创建失败: getMediaProjection返回null")
                
                // 权限可能无效，重置状态
                app.preferenceManager.setScreenshotPermissionGranted(false)
                sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "媒体投影创建失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 初始化截屏服务异常", e)
            app.preferenceManager.setScreenshotPermissionGranted(false)
            sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "初始化失败: ${e.message}")
        }
    }
    
    /**
     * 测试截屏权限是否真正有效
     */
    private fun testScreenshotPermission(mediaProjection: MediaProjection) {
        Log.d(TAG, "测试截屏权限...")
        
        try {
            // 创建一个最小的虚拟显示来测试权限
            val metrics = resources.displayMetrics
            val imageReader = android.media.ImageReader.newInstance(
                100, 100, android.graphics.PixelFormat.RGBA_8888, 1
            )
            
            val virtualDisplay = mediaProjection.createVirtualDisplay(
                "PermissionTest",
                100, 100, metrics.densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface, null, null
            )
            
            // 延迟清理资源
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    virtualDisplay?.release()
                    imageReader.close()
                    mediaProjection.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "清理测试资源异常", e)
                }
            }, 500)
            
            Log.d(TAG, "✅ 权限测试成功")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 权限测试失败", e)
            
            // 权限可能是无效的
            app.preferenceManager.setScreenshotPermissionGranted(false)
            sendLocalBroadcast("com.readassist.SCREENSHOT_PERMISSION_ERROR", "权限测试失败: ${e.message}")
        }
    }
    
    /**
     * 发送本地广播
     */
    private fun sendLocalBroadcast(action: String, errorMessage: String? = null) {
        val intent = Intent(action)
        if (errorMessage != null) {
            intent.putExtra("ERROR_MESSAGE", errorMessage)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== onDestroy() ===")
    }
} 