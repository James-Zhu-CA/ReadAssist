package com.readassist.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.readassist.R
import com.readassist.ReadAssistApplication
import com.readassist.service.ScreenshotService

/**
 * 截屏权限请求Activity
 */
class ScreenshotPermissionActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ScreenshotPermissionActivity"
        const val ACTION_REQUEST_SCREENSHOT_PERMISSION = "com.readassist.REQUEST_SCREENSHOT_PERMISSION"
        private const val REQUEST_SCREENSHOT_PERMISSION_CODE = 1001
        private const val AUTO_CLOSE_DELAY = 2000L // 2秒后自动关闭
    }
    
    private var isPermissionRequested = false
    private val autoCloseHandler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "=== ScreenshotPermissionActivity.onCreate() 开始 ===")
        Log.d(TAG, "Activity实例: ${this.hashCode()}")
        Log.d(TAG, "任务栈ID: $taskId")
        Log.d(TAG, "Intent: action=${intent?.action}, extras=${intent?.extras?.keySet()?.joinToString()}")
        
        // 使用普通主题而不是透明主题
        setContentView(R.layout.activity_screenshot_permission)
        
        setupUI()
        
        // 简单延迟请求权限，确保UI已完全加载
        Handler(Looper.getMainLooper()).postDelayed({
            requestScreenshotPermission()
        }, 300)
        
        Log.d(TAG, "=== ScreenshotPermissionActivity.onCreate() 结束 ===")
    }
    
    private fun setupUI() {
        findViewById<TextView>(R.id.titleText)?.text = "截屏权限授权"
        findViewById<TextView>(R.id.messageText)?.text = "ReadAssist需要截屏权限来分析屏幕内容。\n\n点击下方按钮将打开系统权限对话框，请选择\"立即开始\"。"
        
        findViewById<Button>(R.id.requestButton)?.setOnClickListener {
            Log.d(TAG, "用户点击权限请求按钮")
            isPermissionRequested = false
            requestScreenshotPermission()
        }
        
        findViewById<Button>(R.id.cancelButton)?.setOnClickListener {
            Log.d(TAG, "用户点击取消按钮")
            finishWithError("用户取消权限请求")
        }
    }
    
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "=== onNewIntent() 收到新Intent ===")
        Log.d(TAG, "新Intent: ${intent?.action}")
        
        // 如果已经在请求权限，则不处理新Intent
        if (isPermissionRequested) {
            Log.d(TAG, "已经在请求权限，忽略新Intent")
            return
        }
        
        // 设置新的Intent
        setIntent(intent)
        
        // 延迟请求权限
        Handler(Looper.getMainLooper()).postDelayed({
            requestScreenshotPermission()
        }, 500)
    }
    
    override fun onPause() {
        super.onPause()
        Log.d(TAG, "=== ScreenshotPermissionActivity.onPause() ===")
        Log.d(TAG, "Activity暂停，可能是系统权限对话框显示")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== ScreenshotPermissionActivity.onDestroy() ===")
        autoCloseHandler.removeCallbacksAndMessages(null)
    }
    
    private fun requestScreenshotPermission() {
        Log.d(TAG, "=== requestScreenshotPermission() 开始 ===")
        
        if (isPermissionRequested) {
            Log.w(TAG, "❌ 权限已经请求过，跳过")
            return
        }
        
        isPermissionRequested = true
        
        try {
            Log.d(TAG, "获取MediaProjectionManager...")
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 更新UI
            findViewById<TextView>(R.id.messageText)?.text = "正在打开系统权限对话框...\n\n请在系统弹窗中选择「立即开始」。"
            findViewById<Button>(R.id.requestButton)?.isEnabled = false
            
            // 创建权限请求Intent
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            
            Log.d(TAG, "准备startActivityForResult...")
            
            // 使用延迟启动权限请求
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    Log.d(TAG, "⏱️ 延迟500ms后启动权限请求...")
                    startActivityForResult(intent, REQUEST_SCREENSHOT_PERMISSION_CODE)
                    Log.d(TAG, "✅ 权限请求对话框已启动")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 启动权限请求失败", e)
                    isPermissionRequested = false
                    findViewById<TextView>(R.id.messageText)?.text = "启动系统权限对话框失败，请重试\n\n错误: ${e.message}"
                    findViewById<Button>(R.id.requestButton)?.isEnabled = true
                }
            }, 500)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求截屏权限失败", e)
            findViewById<TextView>(R.id.messageText)?.text = "截屏权限请求失败\n\n错误: ${e.message}"
            findViewById<Button>(R.id.requestButton)?.isEnabled = true
            finishWithError("权限请求失败: ${e.message}")
            isPermissionRequested = false
        }
        
        Log.d(TAG, "=== requestScreenshotPermission() 结束 ===")
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Log.d(TAG, "=== onActivityResult() 开始 ===")
        Log.d(TAG, "权限结果: requestCode=$requestCode, resultCode=$resultCode")
        Log.d(TAG, "结果数据: $data")
        
        if (requestCode == REQUEST_SCREENSHOT_PERMISSION_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "✅ 用户授予了截屏权限")
                
                try {
                    // 将Intent转换为Uri字符串以便存储
                    val resultDataUri = data.toUri(0)
                    
                    // 保存到Application级别
                    (applicationContext as? ReadAssistApplication)?.onScreenshotPermissionGranted(resultCode, data)
                    
                    // 更新UI
                    findViewById<TextView>(R.id.titleText)?.text = "截屏权限已授予"
                    findViewById<TextView>(R.id.messageText)?.text = "截屏权限授予成功，现在可以使用截屏功能了。"
                    findViewById<Button>(R.id.requestButton)?.visibility = View.GONE
                    findViewById<Button>(R.id.cancelButton)?.text = "确定"
                    
                    // 发送广播通知权限已授予
                    val permissionGrantedIntent = Intent("com.readassist.SCREENSHOT_PERMISSION_GRANTED")
                    sendBroadcast(permissionGrantedIntent)
                    
                    // 启动截屏服务
                    val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                        action = ScreenshotService.ACTION_START_SCREENSHOT
                        putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenshotService.EXTRA_RESULT_DATA, data)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    
                    Log.d(TAG, "✅ 成功完成: 权限授予成功")
                    
                    // 延迟关闭此Activity
                    autoCloseHandler.postDelayed({
                        finish()
                    }, AUTO_CLOSE_DELAY)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 处理权限结果异常", e)
                    finishWithError("处理权限结果出错: ${e.message}")
                }
                
            } else {
                Log.d(TAG, "❌ 用户拒绝了截屏权限")
                
                // 发送广播通知权限被拒绝
                val permissionDeniedIntent = Intent("com.readassist.SCREENSHOT_PERMISSION_DENIED")
                sendBroadcast(permissionDeniedIntent)
                
                // 更新UI
                findViewById<TextView>(R.id.titleText)?.text = "截屏权限被拒绝"
                findViewById<TextView>(R.id.messageText)?.text = "您拒绝了截屏权限，无法使用截屏功能。\n\n如果需要使用截屏功能，请点击下方按钮重新授权。"
                findViewById<Button>(R.id.requestButton)?.isEnabled = true
                findViewById<Button>(R.id.requestButton)?.text = "重新授权"
                
                // 重置请求标志
                isPermissionRequested = false
            }
        }
        
        Log.d(TAG, "=== onActivityResult() 结束 ===")
    }
    
    private fun finishWithError(errorMessage: String) {
        Log.e(TAG, "❌ 错误结束Activity: $errorMessage")
        
        // 发送广播通知权限请求失败
        val permissionErrorIntent = Intent("com.readassist.SCREENSHOT_PERMISSION_ERROR")
        permissionErrorIntent.putExtra("error_message", errorMessage)
        sendBroadcast(permissionErrorIntent)
        
        // 延迟关闭以显示错误信息
        autoCloseHandler.postDelayed({
            finish()
        }, AUTO_CLOSE_DELAY)
    }
} 