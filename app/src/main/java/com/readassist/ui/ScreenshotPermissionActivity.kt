package com.readassist.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
 * 修改为非透明Activity，确保权限回调正常工作
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
        Log.d(TAG, "Intent: ${intent?.action}")
        
        // 使用普通主题而不是透明主题
        setContentView(R.layout.activity_screenshot_permission)
        
        setupUI()
        
        when (intent?.action) {
            ACTION_REQUEST_SCREENSHOT_PERMISSION -> {
                Log.d(TAG, "开始处理截屏权限请求...")
                requestScreenshotPermission()
            }
            else -> {
                Log.w(TAG, "❌ 未知的Intent action: ${intent?.action}")
                finishWithError("未知的请求类型")
            }
        }
        Log.d(TAG, "=== ScreenshotPermissionActivity.onCreate() 结束 ===")
    }
    
    private fun setupUI() {
        findViewById<TextView>(R.id.titleText)?.text = "截屏权限授权"
        findViewById<TextView>(R.id.messageText)?.text = "ReadAssist需要截屏权限来分析屏幕内容。\n\n点击下方按钮将打开系统权限对话框，请选择\"立即开始\"。"
        
        findViewById<Button>(R.id.requestButton)?.setOnClickListener {
            Log.d(TAG, "用户点击权限请求按钮")
            requestScreenshotPermission()
        }
        
        findViewById<Button>(R.id.cancelButton)?.setOnClickListener {
            Log.d(TAG, "用户点击取消按钮")
            finishWithError("用户取消权限请求")
        }
    }
    
    override fun onResume() {
        super.onResume()
        Log.d(TAG, "=== ScreenshotPermissionActivity.onResume() ===")
        Log.d(TAG, "Activity在前台，权限请求状态: $isPermissionRequested")
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
        
        try {
            Log.d(TAG, "获取MediaProjectionManager...")
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            
            isPermissionRequested = true
            Log.d(TAG, "✅ 启动权限请求，requestCode=$REQUEST_SCREENSHOT_PERMISSION_CODE")
            
            // 更新UI
            findViewById<TextView>(R.id.messageText)?.text = "正在打开系统权限对话框..."
            findViewById<Button>(R.id.requestButton)?.isEnabled = false
            
            startActivityForResult(intent, REQUEST_SCREENSHOT_PERMISSION_CODE)
            Log.d(TAG, "✅ startActivityForResult已调用")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 请求截屏权限失败", e)
            finishWithError("权限请求失败: ${e.message}")
        }
        
        Log.d(TAG, "=== requestScreenshotPermission() 结束 ===")
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        Log.d(TAG, "=== onActivityResult() 开始 ===")
        Log.d(TAG, "权限结果: requestCode=$requestCode, resultCode=$resultCode")
        Log.d(TAG, "结果数据: $data")
        
        if (requestCode == REQUEST_SCREENSHOT_PERMISSION_CODE) {
            val app = application as ReadAssistApplication
            
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.d(TAG, "✅ 用户授予了截屏权限")
                
                try {
                    // 保存权限状态
                    app.preferenceManager.setScreenshotPermissionGranted(true)
                    app.preferenceManager.setScreenshotPermissionData(resultCode, data.toUri(0))
                    
                    // 启动截屏服务
                    val serviceIntent = Intent(this, ScreenshotService::class.java).apply {
                        action = ScreenshotService.ACTION_START_SCREENSHOT
                        putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenshotService.EXTRA_RESULT_DATA, data)
                    }
                    startForegroundService(serviceIntent)
                    
                    // 通知悬浮窗服务
                    sendBroadcast(Intent("com.readassist.SCREENSHOT_PERMISSION_GRANTED"))
                    
                    finishWithSuccess("权限授予成功")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 处理权限成功结果失败", e)
                    finishWithError("处理权限失败: ${e.message}")
                }
                
            } else {
                Log.w(TAG, "❌ 用户拒绝了截屏权限")
                app.preferenceManager.setScreenshotPermissionGranted(false)
                sendBroadcast(Intent("com.readassist.SCREENSHOT_PERMISSION_DENIED"))
                finishWithError("权限被拒绝")
            }
        }
        
        Log.d(TAG, "=== onActivityResult() 结束 ===")
    }
    
    private fun finishWithSuccess(message: String) {
        Log.d(TAG, "✅ 成功完成: $message")
        findViewById<TextView>(R.id.messageText)?.text = "✅ $message\n\n窗口将自动关闭..."
        
        autoCloseHandler.postDelayed({
            finish()
        }, AUTO_CLOSE_DELAY)
    }
    
    private fun finishWithError(message: String) {
        Log.e(TAG, "❌ 失败结束: $message")
        findViewById<TextView>(R.id.messageText)?.text = "❌ $message\n\n窗口将自动关闭..."
        
        sendBroadcast(Intent("com.readassist.SCREENSHOT_PERMISSION_ERROR"))
        
        autoCloseHandler.postDelayed({
            finish()
        }, AUTO_CLOSE_DELAY)
    }
    
    override fun onBackPressed() {
        Log.d(TAG, "用户按返回键")
        finishWithError("用户取消操作")
        super.onBackPressed()
    }
} 