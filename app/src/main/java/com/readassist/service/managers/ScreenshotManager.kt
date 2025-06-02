package com.readassist.service.managers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.readassist.ReadAssistApplication
import com.readassist.service.ScreenshotService
import com.readassist.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 管理截屏功能
 */
class ScreenshotManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager,
    private val coroutineScope: CoroutineScope,
    private val callbacks: ScreenshotCallbacks
) {
    companion object {
        private const val TAG = "ScreenshotManager"
        private const val PERMISSION_REQUEST_COOLDOWN = 3000L // 权限请求冷却时间，3秒
    }
    
    // 截屏服务相关
    private var screenshotService: ScreenshotService? = null
    private var screenshotServiceConnection: ServiceConnection? = null
    
    // 状态变量
    private var isScreenshotPermissionGranted = false
    private var isRequestingPermission = false
    private var lastPermissionRequestTime = 0L
    private var permissionDialog: android.app.AlertDialog? = null
    
    // 文本选择位置信息
    private var textSelectionBounds: Rect? = null
    private var lastSelectionPosition: Pair<Int, Int>? = null
    
    // 存储待处理的截图
    private var pendingScreenshotBitmap: Bitmap? = null
    
    // 添加缺失的类成员变量
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: android.media.ImageReader? = null
    
    /**
     * 初始化截屏服务
     */
    fun initialize() {
        bindScreenshotService()
        initializeScreenshotPermission()
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        dismissPermissionDialog()
        
        // 清理待发送的截屏图片
        pendingScreenshotBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
        pendingScreenshotBitmap = null
        
        // 解绑服务
        screenshotServiceConnection?.let {
            try {
                context.unbindService(it)
                screenshotServiceConnection = null
            } catch (e: Exception) {
                Log.e(TAG, "解绑截屏服务失败", e)
            }
        }
    }
    
    /**
     * 绑定截屏服务
     */
    private fun bindScreenshotService() {
        screenshotServiceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? ScreenshotService.ScreenshotBinder
                screenshotService = binder?.getService()
                screenshotService?.setScreenshotCallback(screenshotCallback)
                Log.d(TAG, "ScreenshotService连接成功")
                
                // 初始化截屏权限（如果需要）
                initializeScreenshotPermission()
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                screenshotService = null
                Log.d(TAG, "ScreenshotService连接断开")
            }
        }
        
        val intent = Intent(context, ScreenshotService::class.java)
        // 先启动服务
        context.startService(intent)
        // 然后绑定服务
        context.bindService(intent, screenshotServiceConnection!!, Context.BIND_AUTO_CREATE)
    }
    
    /**
     * 初始化截屏权限
     */
    private fun initializeScreenshotPermission() {
        // 检查是否已经有保存的截屏权限
        val hasPermission = preferenceManager.isScreenshotPermissionGranted()
        isScreenshotPermissionGranted = hasPermission
        
        if (hasPermission) {
            Log.d(TAG, "截屏权限已存在，无需重新请求")
            
            // 如果有保存的权限数据，尝试启动截屏服务
            val resultCode = preferenceManager.getScreenshotResultCode()
            val resultDataUri = preferenceManager.getScreenshotResultDataUri()
            
            if (resultCode != -1 && resultDataUri != null) {
                try {
                    val resultData = Intent.parseUri(resultDataUri, 0)
                    
                    // 启动截屏服务
                    val intent = Intent(context, ScreenshotService::class.java).apply {
                        action = ScreenshotService.ACTION_START_SCREENSHOT
                        putExtra(ScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ScreenshotService.EXTRA_RESULT_DATA, resultData)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                    
                    Log.d(TAG, "截屏服务已启动")
                } catch (e: Exception) {
                    Log.e(TAG, "启动截屏服务失败", e)
                    // 清除无效的权限数据
                    preferenceManager.clearScreenshotPermission()
                    isScreenshotPermissionGranted = false
                }
            }
        } else {
            Log.d(TAG, "截屏权限未授予，需要用户手动授权")
        }
    }
    
    /**
     * 请求截屏权限
     */
    fun requestScreenshotPermission() {
        Log.d(TAG, "=== 请求截屏权限 ===")
        
        // 如果已经在请求权限，不重复请求
        if (isRequestingPermission) {
            Log.d(TAG, "已有权限请求正在进行，跳过此次请求")
            return
        }
        
        // 设置请求标志
        isRequestingPermission = true
        
        // 通知回调
        callbacks.onPermissionRequesting()
        
        try {
            // 创建Intent打开权限Activity
            val intent = Intent(context, com.readassist.ui.ScreenshotPermissionActivity::class.java).apply {
                // 设置明确的action
                action = com.readassist.ui.ScreenshotPermissionActivity.ACTION_REQUEST_SCREENSHOT_PERMISSION
                
                // 添加标志确保Activity可以正确启动
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                
                // 添加额外数据
                putExtra("fromService", true)
                putExtra("timestamp", System.currentTimeMillis())
            }
            
            Log.d(TAG, "启动ScreenshotPermissionActivity: action=${intent.action}, flags=${intent.flags}")
            context.startActivity(intent)
            
            // 简单的震动反馈
            if (context.checkSelfPermission(android.Manifest.permission.VIBRATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动权限Activity失败", e)
            isRequestingPermission = false
            callbacks.onPermissionDenied()
        }
    }
    
    /**
     * 执行截屏
     */
    fun performScreenshot() {
        Log.d(TAG, "=== 执行截屏 ===")
        
        // 检查权限状态
        if (!preferenceManager.isScreenshotPermissionGranted()) {
            Log.e(TAG, "❌ 截屏权限未授予，直接请求权限")
            callbacks.onScreenshotFailed("需要获取截屏权限")
            
            // 重置状态标志并请求权限
            isRequestingPermission = false
            requestScreenshotPermission()
            return
        }
        
        // 开始截屏流程
        coroutineScope.launch {
            try {
                // 通知截屏开始
                withContext(Dispatchers.Main) {
                    callbacks.onScreenshotStarted()
                }
                
                // 短暂延迟，让UI更新
                delay(200)
                
                // 获取截屏服务
                val service = screenshotService
                if (service == null) {
                    Log.e(TAG, "❌ 截屏服务未连接")
                    withContext(Dispatchers.Main) {
                        callbacks.onScreenshotFailed("截屏服务未准备就绪")
                        
                        // 尝试绑定服务
                        bindScreenshotService()
                    }
                    return@launch
                }
                
                Log.d(TAG, "开始执行实际截屏操作...")
                
                // 在后台线程中执行截屏
                withContext(Dispatchers.IO) {
                    // 使用新的快速截屏方法
                    service.captureScreenFast()
                }
            } catch (e: Exception) {
                // 捕获截屏过程中的异常
                Log.e(TAG, "❌ 截屏过程异常", e)
                withContext(Dispatchers.Main) {
                    callbacks.onScreenshotFailed("截屏出错: ${e.message}")
                }
            }
        }
    }
    
    /**
     * 检查截屏服务是否可用
     */
    fun isScreenshotServiceReady(): Boolean {
        // 如果正在请求权限，不要重复检查
        if (isRequestingPermission) {
            Log.d(TAG, "正在请求权限中，跳过检查")
            return false
        }
        
        val service = screenshotService
        if (service == null) {
            Log.w(TAG, "ScreenshotService not connected")
            return false
        }
        
        if (!isScreenshotPermissionGranted) {
            Log.w(TAG, "Screenshot permission not granted in preferences")
            return false
        }
        
        // 简化权限检查，只检查MediaProjection是否存在
        // 不再进行复杂的验证，避免误判和循环
        if (!service.isMediaProjectionValid()) {
            Log.w(TAG, "MediaProjection is invalid, requesting permission")
            // 权限实际已失效，更新状态
            isScreenshotPermissionGranted = false
            preferenceManager.setScreenshotPermissionGranted(false)
            return false
        }
        
        return true
    }
    
    /**
     * 显示截屏权限引导对话框
     */
    private fun showScreenshotPermissionGuideDialog() {
        // 强制关闭现有对话框
        dismissPermissionDialog()
        
        // 防止重复显示对话框
        if (isRequestingPermission) {
            Log.d(TAG, "权限请求已在进行中，跳过对话框显示")
            return
        }
        
        // 检查冷却时间
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastPermissionRequestTime < PERMISSION_REQUEST_COOLDOWN) {
            Log.d(TAG, "权限请求冷却中，跳过请求")
            return
        }
        
        lastPermissionRequestTime = currentTime
        Log.d(TAG, "显示截屏权限对话框")
        
        try {
            permissionDialog = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("需要重新授权截屏权限")
                .setMessage("检测到截屏权限已失效或过期。\n\n点击\"立即授权\"重新获取权限，系统将弹出授权对话框。\n\n请在弹窗中选择\"立即开始\"。")
                .setPositiveButton("立即授权") { dialog, _ ->
                    Log.d(TAG, "用户点击立即授权")
                    dialog.dismiss()
                    
                    // 尝试直接请求系统级别权限
                    requestDirectSystemPermission()
                }
                .setNegativeButton("取消") { dialog, _ ->
                    Log.d(TAG, "用户取消权限请求")
                    dialog.dismiss()
                    // 取消时恢复UI
                    callbacks.onScreenshotCancelled()
                }
                .setOnDismissListener { dialog ->
                    Log.d(TAG, "权限对话框被dismiss")
                    permissionDialog = null
                    // 对话框消失时，如果没有在请求权限，则恢复UI
                    if (!isRequestingPermission) {
                        callbacks.onScreenshotCancelled()
                    }
                }
                .setCancelable(true)
                .create()
            
            permissionDialog?.window?.setType(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            
            permissionDialog?.show()
            Log.d(TAG, "权限对话框已显示")
            
        } catch (e: Exception) {
            Log.e(TAG, "显示权限对话框失败", e)
            permissionDialog = null
            callbacks.onScreenshotCancelled()
        }
    }
    
    /**
     * 强制关闭权限对话框
     */
    private fun dismissPermissionDialog() {
        try {
            permissionDialog?.let { dialog ->
                if (dialog.isShowing) {
                    Log.d(TAG, "强制关闭现有权限对话框")
                    dialog.dismiss()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "关闭权限对话框失败", e)
        } finally {
            permissionDialog = null
        }
    }
    
    /**
     * 尝试直接请求系统级别的截屏权限
     * 这是一个备用方案，在标准Activity方式失败时使用
     */
    private fun requestDirectSystemPermission() {
        Log.d(TAG, "=== requestDirectSystemPermission() 开始 ===")
        
        if (isRequestingPermission) {
            Log.w(TAG, "已在请求权限中，跳过")
            return
        }
        
        isRequestingPermission = true
        
        try {
            // 获取结果接收类
            val resultActivityClass = com.readassist.ui.ScreenshotResultActivity::class.java
            
            // 创建Intent启动结果接收Activity
            val resultIntent = Intent(context, resultActivityClass).apply {
                // 设置明确的action
                action = com.readassist.ui.ScreenshotResultActivity.ACTION_PROCESS_SCREENSHOT_RESULT
                
                // 添加标志确保Activity可以正确启动
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                
                // 添加额外数据
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("useDirectMethod", true)
            }
            
            Log.d(TAG, "启动ScreenshotResultActivity: action=${resultIntent.action}")
            context.startActivity(resultIntent)
            
            Log.d(TAG, "✅ 已启动ScreenshotResultActivity")
            
            // 通知处理中
            callbacks.onPermissionRequesting()
            
            // 设置超时检查
            coroutineScope.launch {
                delay(30000) // 30秒超时
                if (isRequestingPermission) {
                    Log.w(TAG, "⚠️ 直接权限请求超时")
                    isRequestingPermission = false
                    callbacks.onScreenshotFailed("权限请求超时，请重试")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 直接请求系统权限失败", e)
            Log.e(TAG, "异常详情: ${e.javaClass.name}: ${e.message}")
            isRequestingPermission = false
            callbacks.onScreenshotFailed("权限请求出错: ${e.message}")
        }
        
        Log.d(TAG, "=== requestDirectSystemPermission() 结束 ===")
    }
    
    /**
     * 直接请求截屏权限
     */
    private fun requestScreenshotPermissionDirectly() {
        Log.d(TAG, "=== requestScreenshotPermissionDirectly() 开始 ===")
        Log.d(TAG, "当前状态检查: isRequestingPermission=$isRequestingPermission")
        
        if (isRequestingPermission) {
            Log.w(TAG, "❌ 权限请求已在进行中，跳过")
            return
        }
        
        Log.d(TAG, "开始权限请求流程...")
        
        try {
            isRequestingPermission = true
            Log.d(TAG, "✅ 权限请求状态已设置为true")
            
            // 强制关闭对话框
            Log.d(TAG, "强制关闭权限对话框...")
            dismissPermissionDialog()
            Log.d(TAG, "权限对话框已关闭")
            
            Log.d(TAG, "通知回调权限请求开始...")
            callbacks.onPermissionRequesting()
            Log.d(TAG, "回调通知完成")
            
            Log.d(TAG, "创建权限请求Intent...")
            val intent = Intent(context, com.readassist.ui.ScreenshotPermissionActivity::class.java).apply {
                action = com.readassist.ui.ScreenshotPermissionActivity.ACTION_REQUEST_SCREENSHOT_PERMISSION
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // 添加时间戳参数，确保每次请求都是新的Intent
                putExtra("timestamp", System.currentTimeMillis())
            }
            Log.d(TAG, "Intent创建完成: $intent")
            Log.d(TAG, "Intent flags: ${intent.flags}")
            
            // 直接启动Activity请求权限
            try {
                Log.d(TAG, "启动权限请求Activity...")
                context.startActivity(intent)
                Log.d(TAG, "✅ 权限请求Activity已启动")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 启动权限请求Activity失败，尝试备用方法", e)
                
                // 备用方法：使用新的Intent
                val backupIntent = Intent(context, com.readassist.ui.ScreenshotPermissionActivity::class.java).apply {
                    action = com.readassist.ui.ScreenshotPermissionActivity.ACTION_REQUEST_SCREENSHOT_PERMISSION
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("timestamp", System.currentTimeMillis())
                    putExtra("is_backup", true)
                }
                
                try {
                    context.startActivity(backupIntent)
                    Log.d(TAG, "✅ 备用权限请求Activity已启动")
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ 备用启动方法也失败", e2)
                    isRequestingPermission = false
                    callbacks.onScreenshotFailed("无法启动权限请求，请重试或重启应用")
                    return
                }
            }
            
            // 设置更长的超时保护（60秒）
            Log.d(TAG, "设置60秒超时保护...")
            coroutineScope.launch {
                try {
                    // 超时前每10秒发送一次状态检查广播
                    repeat(6) { i ->
                        delay(10000) // 10秒
                        if (isRequestingPermission) {
                            Log.d(TAG, "⏰ 权限请求进行中 (${(i+1)*10}秒)，发送状态检查...")
                            // 发送状态检查广播
                            val checkIntent = Intent("com.readassist.RECHECK_SCREENSHOT_PERMISSION")
                            context.sendBroadcast(checkIntent)
                        } else {
                            Log.d(TAG, "✅ 权限请求已完成，取消超时检查")
                            return@launch
                        }
                    }
                    
                    // 60秒后仍在请求，触发紧急重置
                    if (isRequestingPermission) {
                        Log.w(TAG, "⏰ 权限请求超时（60秒），显示紧急重置选项")
                        isRequestingPermission = false
                        
                        // 显示紧急重置选项
                        withContext(Dispatchers.Main) {
                            showEmergencyResetDialog()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "超时保护异常", e)
                }
            }
            Log.d(TAG, "超时保护已设置")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 启动截屏权限请求流程失败", e)
            Log.e(TAG, "异常详情: ${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "堆栈: ${e.stackTrace.take(3).joinToString("\n") { "  at $it" }}")
            
            isRequestingPermission = false
            Log.d(TAG, "权限请求状态已重置为false")
            
            callbacks.onScreenshotFailed("无法启动权限请求，请重试或重启应用")
        }
        Log.d(TAG, "=== requestScreenshotPermissionDirectly() 结束 ===")
    }
    
    /**
     * 显示紧急重置对话框
     */
    private fun showEmergencyResetDialog() {
        try {
            val dialogBuilder = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            dialogBuilder.setTitle("权限请求超时")
            dialogBuilder.setMessage("截屏权限请求超过了60秒无响应。\n\n可能的原因：\n• 系统对话框未正确显示\n• 权限请求被系统阻止\n• 设备运行缓慢\n\n建议操作：\n1. 点击「强制重置」\n2. 再次尝试截屏功能")
            dialogBuilder.setPositiveButton("强制重置") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(context, "正在重置权限状态...", Toast.LENGTH_SHORT).show()
                emergencyResetPermissionState()
            }
            dialogBuilder.setNegativeButton("关闭") { dialog, _ ->
                dialog.dismiss()
                callbacks.onScreenshotCancelled()
            }
            dialogBuilder.setCancelable(true)
            
            val dialog = dialogBuilder.create()
            dialog.window?.setType(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示紧急重置对话框失败", e)
            Toast.makeText(context, "权限请求超时，正在自动重置...", Toast.LENGTH_LONG).show()
            emergencyResetPermissionState()
        }
    }
    
    /**
     * 紧急重置权限状态
     */
    private fun emergencyResetPermissionState() {
        Log.d(TAG, "=== 紧急重置权限状态 ===")
        try {
            // 重置所有状态标志
            isRequestingPermission = false
            isScreenshotPermissionGranted = false
            
            // 清除首选项中的权限数据
            preferenceManager.clearScreenshotPermission()
            preferenceManager.setScreenshotPermissionGranted(false)
            
            // 停止并重新启动截屏服务
            try {
                val stopIntent = Intent(context, ScreenshotService::class.java)
                stopIntent.action = ScreenshotService.ACTION_STOP_SCREENSHOT
                context.startService(stopIntent)
                Log.d(TAG, "✅ 停止截屏服务命令已发送")
            } catch (e: Exception) {
                Log.e(TAG, "停止截屏服务失败", e)
            }
            
            // 释放绑定
            try {
                screenshotServiceConnection?.let {
                    context.unbindService(it)
                    Log.d(TAG, "✅ 截屏服务连接已解绑")
                }
            } catch (e: Exception) {
                Log.e(TAG, "解绑服务失败", e)
            }
            
            screenshotService = null
            screenshotServiceConnection = null
            
            // 重新创建服务连接
            coroutineScope.launch {
                try {
                    delay(1500) // 等待1.5秒，确保服务完全停止
                    
                    withContext(Dispatchers.Main) {
                        Log.d(TAG, "重新绑定截屏服务...")
                        bindScreenshotService()
                    }
                    
                    // 延迟通知用户
                    delay(500)
                    
                    // 通知用户
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "截屏权限已重置，请重新尝试", Toast.LENGTH_LONG).show()
                        callbacks.onScreenshotCancelled()
                        
                        // 额外发送广播，通知其他组件权限已重置
                        val intent = Intent("com.readassist.SCREENSHOT_PERMISSION_RESET")
                        context.sendBroadcast(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重新创建服务连接失败", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "重置过程出错，请重启应用", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            
            Log.d(TAG, "✅ 权限状态重置完成")
        } catch (e: Exception) {
            Log.e(TAG, "重置权限状态失败", e)
            Toast.makeText(context, "重置失败，请重启应用", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 显示截屏服务错误对话框
     */
    private fun showScreenshotServiceErrorDialog() {
        try {
            val dialogBuilder = android.app.AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            dialogBuilder.setTitle("截屏服务异常")
            dialogBuilder.setMessage("截屏服务出现问题，可能的原因：\n\n• 截屏权限已失效\n• 服务连接中断\n• 设备性能限制\n\n建议操作：\n1. 重新授予截屏权限\n2. 重启应用\n3. 检查设备内存")
            dialogBuilder.setPositiveButton("重新授权") { _, _ ->
                // 清除权限状态，强制重新授权
                preferenceManager.clearScreenshotPermission()
                isScreenshotPermissionGranted = false
                
                // 引导用户到主界面授权
                val intent = Intent(context, com.readassist.ui.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
            }
            dialogBuilder.setNegativeButton("稍后重试", null)
            dialogBuilder.setCancelable(true)
            
            val dialog = dialogBuilder.create()
            dialog.window?.setType(
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    android.view.WindowManager.LayoutParams.TYPE_PHONE
                }
            )
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示服务错误对话框失败", e)
            Toast.makeText(context, "截屏服务异常，请重启应用", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 设置文本选择位置
     */
    fun setTextSelectionBounds(bounds: Rect?) {
        textSelectionBounds = bounds
    }
    
    /**
     * 设置文本选择位置
     */
    fun setTextSelectionPosition(position: Pair<Int, Int>?) {
        lastSelectionPosition = position
    }
    
    /**
     * 获取文本选择位置
     */
    fun getTextSelectionPosition(): Pair<Int, Int>? {
        return lastSelectionPosition
    }
    
    /**
     * 重新检查截屏权限状态
     */
    fun recheckScreenshotPermission() {
        Log.d(TAG, "重新检查截屏权限状态")
        val hasPermission = preferenceManager.isScreenshotPermissionGranted()
        
        // 如果首选项中的权限状态与当前状态不一致，更新
        if (hasPermission != isScreenshotPermissionGranted) {
            Log.d(TAG, "权限状态已变更: $isScreenshotPermissionGranted -> $hasPermission")
            isScreenshotPermissionGranted = hasPermission
            
            // 如果获得了权限，重新初始化服务
            if (hasPermission) {
                initializeScreenshotPermission()
            }
        }
        
        // 如果正在请求权限但实际上已经有权限了，重置请求状态
        if (isRequestingPermission && hasPermission) {
            Log.d(TAG, "检测到权限已获取，重置请求状态")
            isRequestingPermission = false
        }
    }
    
    /**
     * 处理权限授予
     */
    fun handlePermissionGranted() {
        Log.d(TAG, "✅ 收到截屏权限授予通知")
        
        // 重置请求状态
        isRequestingPermission = false
        
        // 更新权限状态
        isScreenshotPermissionGranted = true
        
        // 通知回调
        callbacks.onPermissionGranted()
        
        // 延迟重新绑定服务，确保状态正确
        Handler(Looper.getMainLooper()).postDelayed({
            bindScreenshotService()
        }, 500)
    }
    
    /**
     * 处理权限拒绝
     */
    fun handlePermissionDenied() {
        Log.d(TAG, "❌ 收到截屏权限拒绝通知")
        
        // 重置请求状态
        isRequestingPermission = false
        
        // 更新权限状态
        isScreenshotPermissionGranted = false
        
        // 确保偏好设置也被更新
        preferenceManager.setScreenshotPermissionGranted(false)
        
        // 通知回调
        callbacks.onPermissionDenied()
    }
    
    /**
     * 处理权限错误
     */
    fun handlePermissionError(message: String) {
        Log.d(TAG, "⚠️ 收到截屏权限错误通知: $message")
        
        // 重置请求状态
        isRequestingPermission = false
        
        // 确保偏好设置也被更新
        preferenceManager.setScreenshotPermissionGranted(false)
        
        // 通知回调
        callbacks.onScreenshotFailed("权限请求失败: $message")
    }
    
    /**
     * 获取待处理的截图
     */
    fun getPendingScreenshot(): Bitmap? {
        return pendingScreenshotBitmap
    }
    
    /**
     * 清除待处理的截图
     */
    fun clearPendingScreenshot() {
        try {
            Log.d(TAG, "清除待处理的截图")
            pendingScreenshotBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d(TAG, "✅ 待处理截图已回收")
                } else {
                    Log.d(TAG, "⚠️ 待处理截图已经被回收")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 回收待处理截图失败", e)
        } finally {
            pendingScreenshotBitmap = null
        }
    }
    
    /**
     * 是否有截屏权限
     */
    fun hasScreenshotPermission(): Boolean {
        return isScreenshotPermissionGranted
    }
    
    /**
     * 是否正在请求权限
     */
    fun isRequestingPermission(): Boolean {
        return isRequestingPermission
    }
    
    /**
     * 截屏回调
     */
    private val screenshotCallback = object : ScreenshotService.ScreenshotCallback {
        override fun onScreenshotSuccess(bitmap: Bitmap) {
            Log.d(TAG, "📸 截屏成功，尺寸: ${bitmap.width}x${bitmap.height}")
            
            coroutineScope.launch {
                try {
                    // 清除旧的截图（如果有）
                    pendingScreenshotBitmap?.let {
                        if (!it.isRecycled) {
                            it.recycle()
                            Log.d(TAG, "♻️ 旧截图已回收")
                        }
                    }
                    
                    // 处理截屏图片 - 简化局部截屏逻辑
                    val finalBitmap = if (textSelectionBounds != null) {
                        Log.d(TAG, "🎯 进行局部截屏")
                        val croppedBitmap = cropBitmapToSelection(bitmap, textSelectionBounds!!)
                        // 如果裁剪后返回的不是原始bitmap，回收原始bitmap
                        if (croppedBitmap !== bitmap) {
                            bitmap.recycle()
                            Log.d(TAG, "♻️ 原始截图已回收（使用裁剪版本）")
                        }
                        croppedBitmap
                    } else {
                        bitmap
                    }
                    
                    // 保存截屏图片（创建一个深拷贝以确保安全）
                    try {
                        pendingScreenshotBitmap = finalBitmap.copy(finalBitmap.config ?: Bitmap.Config.ARGB_8888, true)
                        Log.d(TAG, "✅ 截图已保存到pendingScreenshotBitmap，尺寸: ${pendingScreenshotBitmap?.width}x${pendingScreenshotBitmap?.height}")
                        
                        // 如果finalBitmap不是原始bitmap，回收它
                        if (finalBitmap !== bitmap) {
                            finalBitmap.recycle()
                            Log.d(TAG, "♻️ 中间截图已回收")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 创建截图副本失败，使用原图", e)
                        pendingScreenshotBitmap = finalBitmap
                    }
                    
                    // 清除选择位置信息
                    textSelectionBounds = null
                    lastSelectionPosition = null
                    
                    // 通知回调
                    callbacks.onScreenshotSuccess(pendingScreenshotBitmap!!)
                    
                    Log.d(TAG, "📸 截屏处理完成")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "💥 处理截屏失败", e)
                    callbacks.onScreenshotFailed("截屏处理失败：${e.message}")
                }
            }
        }
        
        override fun onScreenshotFailed(error: String) {
            Log.e(TAG, "截屏失败: $error")
            callbacks.onScreenshotFailed(error)
        }
    }
    
    /**
     * 根据文本选择位置裁剪图片
     */
    private fun cropBitmapToSelection(originalBitmap: Bitmap, selectionBounds: Rect): Bitmap {
        return try {
            Log.d(TAG, "🎯 开始局部截屏裁剪")
            
            // 确保裁剪区域在图片范围内
            val cropX = maxOf(0, selectionBounds.left - 50) // 左边留50像素边距
            val cropY = maxOf(0, selectionBounds.top - 50)  // 上边留50像素边距
            val cropWidth = minOf(
                originalBitmap.width - cropX,
                selectionBounds.width() + 100 // 左右各留50像素边距
            )
            val cropHeight = minOf(
                originalBitmap.height - cropY,
                selectionBounds.height() + 100 // 上下各留50像素边距
            )
            
            // 确保裁剪尺寸有效
            if (cropWidth <= 0 || cropHeight <= 0) {
                Log.w(TAG, "⚠️ 裁剪尺寸无效，使用原始图片")
                return originalBitmap
            }
            
            val croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                cropX,
                cropY,
                cropWidth,
                cropHeight
            )
            
            Log.d(TAG, "✅ 局部截屏完成: ${croppedBitmap.width}x${croppedBitmap.height}")
            croppedBitmap
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 局部截屏失败，使用原始图片", e)
            originalBitmap
        }
    }
    
    /**
     * 截屏回调接口
     */
    interface ScreenshotCallbacks {
        fun onScreenshotStarted()
        fun onScreenshotSuccess(bitmap: Bitmap)
        fun onScreenshotFailed(error: String)
        fun onScreenshotCancelled()
        fun onPermissionRequesting()
        fun onPermissionGranted()
        fun onPermissionDenied()
        fun onScreenshotMessage(message: String)
    }
    
    /**
     * 强制重置权限（公共方法，供外部调用）
     */
    fun forceResetPermission() {
        Log.d(TAG, "外部调用强制重置权限")
        emergencyResetPermissionState()
    }
    
    /**
     * 尝试恢复媒体投影数据
     */
    private fun attemptRecoverMediaProjection(): Boolean {
        Log.d(TAG, "尝试恢复媒体投影数据...")
        
        try {
            // 从偏好设置中获取权限数据
            val resultCode = preferenceManager.getScreenshotResultCode()
            val resultDataUri = preferenceManager.getScreenshotResultDataUri()
            
            if (resultCode != 0 && resultDataUri != null) {
                try {
                    // 解析URI为Intent
                    val resultData = Intent.parseUri(resultDataUri, 0)
                    
                    // 创建媒体投影管理器
                    val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    
                    // 尝试重新创建媒体投影
                    val mp = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    
                    if (mp != null) {
                        // 先释放旧的资源
                        mediaProjection?.stop()
                        releaseVirtualDisplay()
                        
                        // 设置新的媒体投影
                        mediaProjection = mp
                        
                        // 注册回调并创建虚拟显示
                        setupVirtualDisplay()
                        
                        Log.d(TAG, "✅ 成功恢复媒体投影")
                        return virtualDisplay != null
                    } else {
                        Log.e(TAG, "❌ 媒体投影恢复失败: getMediaProjection返回null")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 解析媒体投影数据失败", e)
                }
            } else {
                Log.e(TAG, "❌ 没有有效的权限数据可恢复")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 恢复媒体投影异常", e)
        }
        
        return false
    }
    
    /**
     * 设置虚拟显示
     */
    private fun setupVirtualDisplay() {
        Log.d(TAG, "设置虚拟显示...")
        
        try {
            // 释放旧的资源
            releaseVirtualDisplay()
            
            // 获取屏幕尺寸
            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val screenDensity = metrics.densityDpi
            
            Log.d(TAG, "屏幕尺寸: ${screenWidth}x${screenHeight}, 密度: $screenDensity")
            
            // 创建ImageReader
            imageReader = android.media.ImageReader.newInstance(
                screenWidth, screenHeight, android.graphics.PixelFormat.RGBA_8888, 2
            )
            
            if (mediaProjection == null) {
                Log.e(TAG, "❌ mediaProjection为空，无法创建虚拟显示")
                return
            }
            
            // 创建虚拟显示
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            
            if (virtualDisplay == null) {
                Log.e(TAG, "❌ 创建虚拟显示失败")
            } else {
                Log.d(TAG, "✅ 虚拟显示设置完成: ${virtualDisplay?.display?.displayId}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 设置虚拟显示异常", e)
        }
    }
    
    /**
     * 释放虚拟显示资源
     */
    private fun releaseVirtualDisplay() {
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            
            imageReader?.close()
            imageReader = null
            
            Log.d(TAG, "✅ 虚拟显示资源已释放")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 释放虚拟显示资源异常", e)
        }
    }
    
    /**
     * 执行截屏操作
     */
    private fun takeScreenshot(): Bitmap? {
        Log.d(TAG, "开始执行截屏...")
        
        try {
            if (virtualDisplay == null || imageReader == null) {
                Log.e(TAG, "❌ 虚拟显示或图像读取器未初始化")
                return null
            }
            
            // 从imageReader获取最新的图像
            val image = imageReader?.acquireLatestImage()
            
            if (image == null) {
                Log.e(TAG, "❌ 无法获取图像")
                return null
            }
            
            // 将Image转换为Bitmap
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            
            bitmap.copyPixelsFromBuffer(buffer)
            
            // 裁剪到屏幕实际大小
            val croppedBitmap = Bitmap.createBitmap(
                bitmap, 0, 0, image.width, image.height
            )
            
            // 释放资源
            bitmap.recycle()
            image.close()
            
            return croppedBitmap
        } catch (e: Exception) {
            Log.e(TAG, "❌ 截屏过程异常", e)
            return null
        }
    }
    
    /**
     * 压缩图片以减小内存占用
     */
    private fun compressBitmap(original: Bitmap): Bitmap {
        try {
            // 计算新的尺寸
            val maxDimension = 2000
            val width = original.width
            val height = original.height
            
            val ratio = Math.max(width, height).toFloat() / maxDimension
            
            if (ratio <= 1) {
                // 不需要压缩
                return original
            }
            
            val newWidth = (width / ratio).toInt()
            val newHeight = (height / ratio).toInt()
            
            Log.d(TAG, "压缩图片: $width x $height -> $newWidth x $newHeight")
            
            // 创建压缩后的图片
            return Bitmap.createScaledBitmap(original, newWidth, newHeight, true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 压缩图片异常", e)
            return original
        }
    }
} 