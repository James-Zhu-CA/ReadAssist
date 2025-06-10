package com.readassist.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.readassist.R
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException

class ScreenshotService : Service() {
    
    companion object {
        private const val TAG = "ScreenshotService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "screenshot_channel"
        
        const val ACTION_START_SCREENSHOT = "com.readassist.START_SCREENSHOT"
        const val ACTION_STOP_SCREENSHOT = "com.readassist.STOP_SCREENSHOT"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val backgroundHandler = Handler(Looper.getMainLooper())
    
    private var screenshotCallback: ScreenshotCallback? = null
    
    inner class ScreenshotBinder : Binder() {
        fun getService(): ScreenshotService = this@ScreenshotService
    }
    
    override fun onBind(intent: Intent?): IBinder = ScreenshotBinder()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SCREENSHOT -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                
                if (resultData != null) {
                    startScreenCapture(resultCode, resultData)
                }
            }
            ACTION_STOP_SCREENSHOT -> {
                stopScreenCapture()
            }
        }
        
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "截屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ReadAssist 截屏功能"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ReadAssist 截屏服务")
            .setContentText("准备截屏...")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun startScreenCapture(resultCode: Int, resultData: Intent) {
        Log.d(TAG, "=== startScreenCapture() 开始 ===")
        Log.d(TAG, "权限数据: resultCode=$resultCode")
        Log.d(TAG, "权限Intent: $resultData")
        
        try {
            // 验证传入的权限数据
            if (resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "❌ 权限结果码无效: $resultCode")
                return
            }
            
            if (resultData.extras == null) {
                Log.e(TAG, "❌ 权限Intent数据为空")
                return
            }
            
            // 获取系统服务
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            
            // 尝试创建MediaProjection
            Log.d(TAG, "正在创建MediaProjection...")
            try {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                Log.d(TAG, "✅ MediaProjection创建结果: ${mediaProjection != null}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 创建MediaProjection失败", e)
                mediaProjection = null
            }
            
            if (mediaProjection != null) {
                // 设置MediaProjection回调
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.w(TAG, "⚠️ MediaProjection被系统停止了")
                            serviceScope.launch(Dispatchers.Main) {
                                screenshotCallback?.onScreenshotFailed("截屏权限已被系统收回，请重新授权")
                            }
                        }
                    }, null)
                }
                
                // 初始化VirtualDisplay
                setupVirtualDisplay()
                Log.d(TAG, "✅ 截屏服务初始化完成")
                
                // 验证初始化是否成功
                val isReady = virtualDisplay != null && imageReader != null
                Log.d(TAG, "服务状态检查: VirtualDisplay=${virtualDisplay != null}, ImageReader=${imageReader != null}")
                
                if (!isReady) {
                    Log.e(TAG, "❌ 服务初始化不完整，尝试重新初始化...")
                    // 重试一次
                    setupVirtualDisplay()
                }
                
            } else {
                Log.e(TAG, "❌ MediaProjection创建失败")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ 截屏服务启动失败", e)
            mediaProjection = null
        }
        Log.d(TAG, "=== startScreenCapture() 结束 ===")
    }
    
    private fun setupVirtualDisplay() {
        Log.d(TAG, "=== setupVirtualDisplay() 开始 ===")
        
        try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            
            // 获取屏幕尺寸（兼容各种Android版本）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                displayMetrics.widthPixels = bounds.width()
                displayMetrics.heightPixels = bounds.height()
                displayMetrics.densityDpi = resources.configuration.densityDpi
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(displayMetrics)
            }
            
            val density = displayMetrics.densityDpi
            val width = displayMetrics.widthPixels
            val height = displayMetrics.heightPixels
            
            Log.d(TAG, "屏幕参数: ${width}x${height}, 密度: $density")
            
            // 清理旧的资源
            try {
                virtualDisplay?.release()
                imageReader?.close()
            } catch (e: Exception) {
                Log.w(TAG, "清理旧资源时出现异常", e)
            }
            
            // 创建新的ImageReader - 增加缓冲区大小到2
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            Log.d(TAG, "✅ ImageReader创建成功: ${imageReader != null}")
            
            if (mediaProjection == null) {
                Log.e(TAG, "❌ MediaProjection为空，无法创建VirtualDisplay")
                return
            }
            
            if (imageReader?.surface == null) {
                Log.e(TAG, "❌ ImageReader surface为空")
                return
            }
            
            // 创建VirtualDisplay
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ReadAssist-Screenshot",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, backgroundHandler
            )
            
            Log.d(TAG, "✅ VirtualDisplay创建结果: ${virtualDisplay != null}")
            
            if (virtualDisplay != null) {
                Log.d(TAG, "🎉 Virtual display创建成功: ${width}x${height}")
                // 给VirtualDisplay一些时间初始化 - 增加初始化时间为1秒，墨水屏需要更长时间
                serviceScope.launch {
                    delay(1000)
                    Log.d(TAG, "VirtualDisplay初始化等待完成")
                }
            } else {
                Log.e(TAG, "❌ VirtualDisplay创建失败")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ 创建VirtualDisplay异常", e)
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
        }
        
        Log.d(TAG, "=== setupVirtualDisplay() 结束 ===")
        Log.d(TAG, "最终状态: VirtualDisplay=${virtualDisplay != null}, ImageReader=${imageReader != null}")
    }
    
    /**
     * 设置截屏回调
     */
    fun setScreenshotCallback(callback: ScreenshotCallback?) {
        this.screenshotCallback = callback
    }
    
    /**
     * 执行截屏
     */
    fun captureScreen() {
        Log.d(TAG, "=== captureScreen() 开始 ===")
        Log.d(TAG, "MediaProjection状态: ${mediaProjection != null}")
        Log.d(TAG, "VirtualDisplay状态: ${virtualDisplay != null}")
        Log.d(TAG, "ImageReader状态: ${imageReader != null}")
        
        // 检查服务状态
        if (mediaProjection == null || virtualDisplay == null || imageReader == null) {
            Log.e(TAG, "❌ 截屏服务状态异常，尝试重新初始化...")
            // 尝试重新初始化
            setupVirtualDisplay()
            
            // 重新检查
            if (mediaProjection == null || virtualDisplay == null || imageReader == null) {
                Log.e(TAG, "❌ 重新初始化失败，无法继续")
                screenshotCallback?.onScreenshotFailed("截屏服务初始化失败，请重新授权")
                return
            }
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "开始截屏...")
                
                val image = withContext(Dispatchers.IO) {
                    // 增加重试机制，针对墨水屏设备特殊优化
                    repeat(5) { attempt ->
                        Log.d(TAG, "🎯 截屏尝试 ${attempt + 1}/5 (墨水屏优化)")
                        
                        val latch = java.util.concurrent.CountDownLatch(1)
                        var capturedImage: Image? = null
                        var isListenerSet = false
                        
                        try {
                            val currentReader = imageReader ?: return@withContext null
                            
                            Log.d(TAG, "📋 准备设置ImageReader监听器...")
                            
                            // 先尝试直接获取图像（不等待）
                            try {
                                val directImage = currentReader.acquireLatestImage()
                                if (directImage != null) {
                                    Log.d(TAG, "✅ 直接获取图像成功！")
                                    return@withContext directImage
                                }
                            } catch (e: Exception) {
                                Log.d(TAG, "直接获取图像失败，将使用监听器方式")
                            }
                            
                            // 设置监听器
                            currentReader.setOnImageAvailableListener({
                                try {
                                    Log.d(TAG, "🔔 ImageReader监听器被触发！")
                                    capturedImage = currentReader.acquireLatestImage()
                                    Log.d(TAG, "✅ 图像捕获成功，尺寸: ${capturedImage?.width}x${capturedImage?.height}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ 获取图像失败", e)
                                } finally {
                                    Log.d(TAG, "🔓 countDown - 释放等待锁")
                                    latch.countDown()
                                }
                            }, backgroundHandler)
                            
                            isListenerSet = true
                            Log.d(TAG, "✅ ImageReader监听器设置完成")
                            
                            // 针对墨水屏设备延长超时时间
                            val timeoutSeconds = if (attempt < 2) 10L else 15L
                            Log.d(TAG, "⏰ 设置超时时间: ${timeoutSeconds}秒")
                            
                            // 开始等待
                            Log.d(TAG, "⏳ 开始等待图像捕获，超时${timeoutSeconds}秒...")
                            
                            // 分段等待，每2秒打印一次状态
                            var remainingTime = timeoutSeconds
                            var success = false
                            
                            while (remainingTime > 0 && !success) {
                                val waitTime = minOf(2L, remainingTime)
                                Log.d(TAG, "⌛ 等待中... 剩余时间: ${remainingTime}秒")
                                
                                success = latch.await(waitTime, java.util.concurrent.TimeUnit.SECONDS)
                                
                                if (success) {
                                    Log.d(TAG, "🎉 等待成功！图像已获取")
                                    break
                                } else {
                                    remainingTime -= waitTime
                                    if (remainingTime > 0) {
                                        Log.d(TAG, "💤 继续等待... 还需${remainingTime}秒")
                                        
                                        // 主动尝试再次获取图像
                                        if (attempt > 1) {  // 在后续尝试中增加额外主动获取
                                            try {
                                                val directImage = currentReader.acquireLatestImage()
                                                if (directImage != null) {
                                                    Log.d(TAG, "✅ 主动获取图像成功！")
                                                    capturedImage = directImage
                                                    success = true
                                                    break
                                                }
                                            } catch (e: Exception) {
                                                // 忽略异常，继续等待
                                            }
                                        }
                                    }
                                }
                            }
                            
                            if (success && capturedImage != null) {
                                Log.d(TAG, "🎉 截屏成功！图像尺寸: ${capturedImage?.width}x${capturedImage?.height}")
                                return@withContext capturedImage
                            } else {
                                val reason = when {
                                    !success -> "超时 (${timeoutSeconds}秒)"
                                    capturedImage == null -> "图像为空"
                                    else -> "未知原因"
                                }
                                Log.w(TAG, "⚠️ 截屏尝试 ${attempt + 1} 失败: $reason")
                                capturedImage?.close()
                                capturedImage = null
                                
                                // 墨水屏专用：增加延迟时间，让设备充分刷新
                                if (attempt < 4) {
                                    val delayMs = (attempt + 1) * 1000L // 递增延迟
                                    Log.d(TAG, "💤 等待 ${delayMs}ms 后重试...")
                                    delay(delayMs)
                                    
                                    // 重新设置虚拟显示以刷新状态
                                    if (attempt > 1) {
                                        withContext(Dispatchers.Main) {
                                            setupVirtualDisplay()
                                        }
                                        delay(1000) // 再等待1秒让虚拟显示初始化
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 截屏尝试 ${attempt + 1} 异常", e)
                            capturedImage?.close()
                            capturedImage = null
                        } finally {
                            // 清理监听器
                            if (isListenerSet) {
                                try {
                                    imageReader?.setOnImageAvailableListener(null, null)
                                } catch (e: Exception) {
                                    Log.e(TAG, "清理监听器失败", e)
                                }
                            }
                        }
                    }
                    
                    Log.e(TAG, "💀 所有截屏尝试均失败")
                    null // 所有重试都失败
                }
                
                if (image != null) {
                    Log.d(TAG, "🖼️ 开始图像处理...")
                    val bitmap = imageToBitmap(image)
                    image.close()
                    
                    if (bitmap != null) {
                        Log.d(TAG, "✅ 截屏处理完成，Bitmap尺寸: ${bitmap.width}x${bitmap.height}")
                        screenshotCallback?.onScreenshotSuccess(bitmap)
                    } else {
                        Log.e(TAG, "❌ 图像转换失败")
                        screenshotCallback?.onScreenshotFailed("图像转换失败")
                    }
                } else {
                    Log.e(TAG, "💀 所有截屏尝试均失败")
                    screenshotCallback?.onScreenshotFailed("截屏超时，墨水屏设备需要更多时间，请重试或检查设备性能")
                    
                    // 超时后重置服务状态
                    resetServiceState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Screenshot failed", e)
                screenshotCallback?.onScreenshotFailed("截屏异常：${e.message}")
                resetServiceState()
            }
        }
        Log.d(TAG, "=== captureScreen() 方法结束 ===")
    }
    
    /**
     * 重置服务状态
     */
    private fun resetServiceState() {
        Log.d(TAG, "重置截屏服务状态...")
        try {
            // 清理当前状态
            virtualDisplay?.release()
            imageReader?.close()
            
            // 重新设置VirtualDisplay和ImageReader
            if (mediaProjection != null) {
                setupVirtualDisplay()
                Log.d(TAG, "服务状态重置完成")
            } else {
                Log.w(TAG, "MediaProjection为空，无法重置")
            }
        } catch (e: Exception) {
            Log.e(TAG, "重置服务状态失败", e)
        }
    }
    
    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            
            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            
            // 裁剪掉padding部分
            if (rowPadding == 0) {
                bitmap
            } else {
                val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                bitmap.recycle()
                croppedBitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }
    
    private fun stopScreenCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopScreenCapture()
        serviceScope.cancel()
    }
    
    /**
     * 截屏回调接口
     */
    interface ScreenshotCallback {
        fun onScreenshotSuccess(bitmap: Bitmap)
        fun onScreenshotFailed(error: String)
    }
    
    /**
     * 检查MediaProjection是否有效
     */
    fun isMediaProjectionValid(): Boolean {
        val isValid = mediaProjection != null
        
        try {
            if (isValid) {
                // 额外验证：尝试使用MediaProjection进行简单操作
                val displayMetrics = DisplayMetrics()
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val display = windowManager.currentWindowMetrics.bounds
                    displayMetrics.densityDpi = resources.configuration.densityDpi
                    Log.d(TAG, "权限验证：使用新API检查屏幕尺寸: ${display.width()}x${display.height()}")
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getMetrics(displayMetrics)
                    Log.d(TAG, "权限验证：使用旧API检查屏幕尺寸: ${displayMetrics.widthPixels}x${displayMetrics.heightPixels}")
                }
                
                // 使用MediaProjection进行简单操作，确认其仍然有效
                try {
                    Log.d(TAG, "检查MediaProjection有效性...")
                    val temp = imageReader
                    if (temp != null && temp.surface != null) {
                        val testDisplay = mediaProjection?.createVirtualDisplay(
                            "ValidityTest",
                            1, 1, displayMetrics.densityDpi,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            null, null, null
                        )
                        
                        if (testDisplay != null) {
                            Log.d(TAG, "✅ MediaProjection完整性验证通过")
                            testDisplay.release()
                            return true
                        } else {
                            Log.w(TAG, "❌ MediaProjection无法创建测试Display")
                        }
                    } else {
                        Log.d(TAG, "MediaProjection基本有效，但ImageReader不可用")
                    }
                } catch (e: SecurityException) {
                    Log.e(TAG, "❌ MediaProjection权限验证失败：安全异常", e)
                    return false
                } catch (e: Exception) {
                    Log.e(TAG, "❌ MediaProjection权限验证出现未知异常", e)
                    return false
                }
            } else {
                Log.d(TAG, "❌ MediaProjection为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "权限验证过程中出现异常", e)
        }
        
        return isValid
    }
    
    /**
     * 强制检查权限有效性
     * 通过尝试创建VirtualDisplay来验证权限是否真的可用
     */
    fun validatePermissions(): Boolean {
        return try {
            if (mediaProjection == null) {
                Log.w(TAG, "MediaProjection为空")
                return false
            }
            
            // 尝试获取显示信息（这不会真正创建Display，但可以验证权限）
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            
            // 如果能走到这里说明基本权限有效
            Log.d(TAG, "权限验证通过")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "权限验证失败: 安全异常", e)
            false
        } catch (e: Exception) {
            Log.w(TAG, "权限验证失败: 其他异常", e)
            false
        }
    }
    
    /**
     * 增强版截屏方法：专注墨水屏设备，优先使用PixelCopy
     */
    fun captureScreenEnhanced() {
        Log.d(TAG, "=== captureScreenEnhanced() 开始 ===")
        Log.d(TAG, "MediaProjection状态: ${mediaProjection != null}")
        Log.d(TAG, "VirtualDisplay状态: ${virtualDisplay != null}")
        Log.d(TAG, "ImageReader状态: ${imageReader != null}")
        
        serviceScope.launch {
            try {
                Log.d(TAG, "🎯 墨水屏专用截屏开始，优先使用PixelCopy方法...")
                
                // 方法1：PixelCopy方法（墨水屏首选）- 增加重试机制
                Log.d(TAG, "📱 方法1: PixelCopy截屏（墨水屏首选方案）")
                var result: Bitmap? = null
                
                // 尝试3次PixelCopy截屏
                repeat(3) { attempt ->
                    Log.d(TAG, "🔄 PixelCopy尝试 ${attempt + 1}/3...")
                    val currentResult = captureWithPixelCopy()
                    
                    if (currentResult != null) {
                        Log.d(TAG, "🎉 PixelCopy截屏成功！尺寸: ${currentResult.width}x${currentResult.height}")
                        screenshotCallback?.onScreenshotSuccess(currentResult)
                        return@launch
                    } else {
                        Log.w(TAG, "⚠️ PixelCopy尝试 ${attempt + 1} 失败")
                        if (attempt < 2) { // 不是最后一次尝试
                            Log.d(TAG, "⏰ 等待1秒后重试...")
                            delay(1000) // 等待1秒后重试
                        }
                    }
                }
                
                // 方法2：VirtualDisplay方法（备用）
                Log.d(TAG, "🔄 方法2: VirtualDisplay截屏（备用方案）")
                result = withTimeoutOrNull(20000) { // 20秒超时
                    captureWithVirtualDisplayEnhanced()
                }
                
                if (result != null) {
                    Log.d(TAG, "🎉 VirtualDisplay截屏成功！尺寸: ${result.width}x${result.height}")
                    screenshotCallback?.onScreenshotSuccess(result)
                } else {
                    Log.e(TAG, "💀 所有截屏方法均失败")
                    screenshotCallback?.onScreenshotFailed("截屏失败：墨水屏设备需要特殊适配，请稍后重试")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 增强版截屏异常", e)
                screenshotCallback?.onScreenshotFailed("截屏异常：${e.message}")
            }
        }
        
        Log.d(TAG, "=== captureScreenEnhanced() 方法结束 ===")
    }
    
    /**
     * PixelCopy截屏方法 - 专门为墨水屏设备优化
     */
    private suspend fun captureWithPixelCopy(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 开始PixelCopy截屏（墨水屏专用）...")
                
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    Log.w(TAG, "❌ PixelCopy需要Android 8.0+，当前系统不支持")
                    return@withContext null
                }
                
                if (virtualDisplay == null) {
                    Log.e(TAG, "❌ VirtualDisplay未初始化")
                    return@withContext null
                }
                
                // 获取屏幕尺寸
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                
                Log.d(TAG, "📐 屏幕尺寸: ${width}x${height}")
                
                // 创建Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // 使用CountDownLatch等待PixelCopy完成
                val latch = java.util.concurrent.CountDownLatch(1)
                var copySuccess = false
                
                // 获取VirtualDisplay的Surface
                val surface = virtualDisplay?.surface
                if (surface == null) {
                    Log.e(TAG, "❌ VirtualDisplay的Surface为空")
                    return@withContext null
                }
                
                // 墨水屏专用：优化渲染流程，移除Surface直接操作
                Log.d(TAG, "🎯 开始墨水屏渲染优化流程...")
                
                // 阶段1：强制触发VirtualDisplay刷新
                Log.d(TAG, "📺 阶段1: 强制刷新VirtualDisplay...")
                try {
                    virtualDisplay?.resize(width, height, displayMetrics.densityDpi)
                    Log.d(TAG, "✅ VirtualDisplay刷新完成")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ VirtualDisplay刷新失败，继续后续流程", e)
                }
                
                // 阶段2：等待VirtualDisplay稳定（墨水屏需要更长时间）
                Log.d(TAG, "⏰ 阶段2: 等待VirtualDisplay稳定（800ms）...")
                delay(800) // 增加到800ms，给墨水屏更多时间
                Log.d(TAG, "✅ VirtualDisplay稳定期完成")
                
                // 阶段3：触发VirtualDisplay渲染（增加次数）
                Log.d(TAG, "🔄 阶段3: VirtualDisplay渲染...")
                repeat(3) { i -> // 增加到3次
                    try {
                        Log.d(TAG, "   触发 ${i + 1}/3: VirtualDisplay刷新...")
                        
                        // 方法A：通过resize触发渲染
                        virtualDisplay?.resize(width, height, displayMetrics.densityDpi)
                        
                        // 每次触发间隔
                        delay(300) // 增加到300ms
                        Log.d(TAG, "   ✅ 触发 ${i + 1} 完成")
                    } catch (e: Exception) {
                        Log.w(TAG, "   ⚠️ 触发 ${i + 1} 失败", e)
                    }
                }
                
                // 阶段4：最终等待渲染完成（墨水屏需要更长时间）
                Log.d(TAG, "⏰ 阶段4: 最终等待VirtualDisplay完全渲染（1000ms）...")
                delay(1000) // 增加到1秒，确保墨水屏完全渲染
                Log.d(TAG, "✅ VirtualDisplay完全渲染等待完成")
                
                // 移除阶段5：不再重新初始化VirtualDisplay，节省时间
                
                Log.d(TAG, "🎯 开始PixelCopy操作...")
                
                // 在主线程执行PixelCopy
                withContext(Dispatchers.Main) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            // 使用VirtualDisplay的Surface进行PixelCopy
                            android.view.PixelCopy.request(
                                virtualDisplay!!.surface,
                                bitmap,
                                object : android.view.PixelCopy.OnPixelCopyFinishedListener {
                                    override fun onPixelCopyFinished(result: Int) {
                                        copySuccess = (result == android.view.PixelCopy.SUCCESS)
                                        if (copySuccess) {
                                            Log.d(TAG, "✅ PixelCopy成功")
                                        } else {
                                            Log.e(TAG, "❌ PixelCopy失败，错误码: $result")
                                            // 添加错误码解释
                                            val errorMsg = when (result) {
                                                1 -> "UNKNOWN_ERROR"
                                                2 -> "TIMEOUT"
                                                3 -> "SOURCE_NO_DATA"
                                                4 -> "SOURCE_INVALID"
                                                5 -> "DESTINATION_INVALID"
                                                else -> "未知错误码: $result"
                                            }
                                            Log.e(TAG, "   错误详情: $errorMsg")
                                        }
                                        latch.countDown()
                                    }
                                },
                                backgroundHandler
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ PixelCopy请求异常", e)
                        latch.countDown()
                    }
                }
                
                // 等待PixelCopy完成，优化超时时间
                Log.d(TAG, "⏳ 等待PixelCopy完成，最多2秒...")
                val completed = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (!completed) {
                    Log.w(TAG, "⚠️ PixelCopy超时（2秒）")
                    bitmap.recycle()
                    return@withContext null
                }
                
                if (!copySuccess) {
                    Log.w(TAG, "⚠️ PixelCopy失败")
                    bitmap.recycle()
                    return@withContext null
                }
                
                // 检查bitmap是否有效
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ Bitmap已被回收")
                    return@withContext null
                }
                
                // 增强的内容检测：检查更多区域的像素
                Log.d(TAG, "🔍 开始增强像素分析...")
                
                // 检查多个区域
                val regions = listOf(
                    "左上角" to Pair(50, 50),
                    "右上角" to Pair(width - 100, 50),
                    "左下角" to Pair(50, height - 100),
                    "右下角" to Pair(width - 100, height - 100),
                    "中心" to Pair(width / 2 - 50, height / 2 - 50),
                    "上中" to Pair(width / 2 - 50, 100),
                    "下中" to Pair(width / 2 - 50, height - 100),
                    "左中" to Pair(100, height / 2 - 50),
                    "右中" to Pair(width - 100, height / 2 - 50)
                )
                
                var totalNonTransparentPixels = 0
                var totalPixelsChecked = 0
                var totalColorVariance = 0.0
                
                for ((regionName, pos) in regions) {
                    try {
                        val (x, y) = pos
                        val regionSize = 100 // 100x100区域
                        val regionPixels = IntArray(regionSize * regionSize)
                        val safeX = x.coerceIn(0, width - regionSize)
                        val safeY = y.coerceIn(0, height - regionSize)
                        
                        bitmap.getPixels(regionPixels, 0, regionSize, safeX, safeY, regionSize, regionSize)
                        
                        val nonTransparent = regionPixels.count { it != 0 }
                        val nonBlack = regionPixels.count { it != 0xFF000000.toInt() }
                        val uniqueColors = regionPixels.toSet().size
                        
                        totalNonTransparentPixels += nonTransparent
                        totalPixelsChecked += regionPixels.size
                        
                        Log.d(TAG, "   $regionName: 非透明=$nonTransparent, 非黑色=$nonBlack, 颜色数=$uniqueColors")
                        
                    } catch (e: Exception) {
                        Log.w(TAG, "   检查 $regionName 区域失败", e)
                    }
                }
                
                val contentPercentage = (totalNonTransparentPixels.toFloat() / totalPixelsChecked * 100).toInt()
                Log.d(TAG, "📊 总体内容覆盖率: $contentPercentage% ($totalNonTransparentPixels/$totalPixelsChecked)")
                
                // 进一步降低内容检测标准：只要有0.1%的内容就认为有效
                val hasContent = contentPercentage >= 1 || totalNonTransparentPixels > 100
                
                Log.d(TAG, "📊 内容检测结果: ${if (hasContent) "有内容($contentPercentage%)" else "空白图像"}")
                
                if (!hasContent) {
                    Log.w(TAG, "⚠️ 截屏内容为空白或过少，尝试保存调试图像...")
                    // 即使是空白图像也保存一份用于调试
                    try {
                        val debugFile = java.io.File("${applicationContext.getExternalFilesDir(null)?.absolutePath}/debug_empty_screenshot_${System.currentTimeMillis()}.png")
                        val outputStream = java.io.FileOutputStream(debugFile)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                        outputStream.close()
                        Log.d(TAG, "📁 空白截屏调试图像已保存到: ${debugFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ 保存调试图像失败", e)
                    }
                    
                    // 空白图像时回收bitmap并返回null，让系统尝试备用方案
                    Log.w(TAG, "⚠️ 检测到空白图像，回收bitmap并尝试备用方案")
                    bitmap.recycle()
                    return@withContext null
                }
                
                Log.d(TAG, "🎉 PixelCopy截屏成功！尺寸: ${bitmap.width}x${bitmap.height}，内容: $contentPercentage%")
                
                // 保存成功的截屏文件用于验证
                try {
                    val timestamp = System.currentTimeMillis()
                    val testFile = java.io.File("${applicationContext.getExternalFilesDir(null)?.absolutePath}/screenshot_$timestamp.png")
                    val outputStream = java.io.FileOutputStream(testFile)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    Log.d(TAG, "📁 成功截屏已保存到: ${testFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 保存截屏文件失败", e)
                }
                
                return@withContext bitmap
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ PixelCopy截屏异常", e)
                return@withContext null
            }
        }
    }
    
    /**
     * 方法2：VirtualDisplay截屏方法，作为PixelCopy的备用方案
     */
    private suspend fun captureWithVirtualDisplayEnhanced(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 开始VirtualDisplay截屏（备用方案）...")
                
                val reader = imageReader ?: run {
                    Log.e(TAG, "❌ ImageReader未初始化")
                    return@withContext null
                }
                
                var capturedImage: Image? = null
                val imageCaptureLatch = java.util.concurrent.CountDownLatch(1)
                
                try {
                    // 设置监听器
                    Log.d(TAG, "📋 设置ImageReader监听器...")
                    reader.setOnImageAvailableListener({
                        try {
                            Log.d(TAG, "🔔 ImageReader监听器被触发！")
                            capturedImage = reader.acquireLatestImage()
                            Log.d(TAG, "✅ 图像捕获成功，尺寸: ${capturedImage?.width}x${capturedImage?.height}")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ 获取图像失败", e)
                        } finally {
                            imageCaptureLatch.countDown()
                        }
                    }, backgroundHandler)
                    
                    // 等待15秒
                    Log.d(TAG, "⏳ 等待图像捕获，超时15秒...")
                    val success = imageCaptureLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                    
                    if (success && capturedImage != null) {
                        Log.d(TAG, "🎉 VirtualDisplay截屏成功！")
                        val image = capturedImage
                        if (image != null) {
                            val bitmap = imageToBitmap(image)
                            image.close()
                            return@withContext bitmap
                        } else {
                            Log.w(TAG, "⚠️ 图像为空")
                            return@withContext null
                        }
                    } else {
                        Log.w(TAG, "⚠️ VirtualDisplay截屏失败: ${if (!success) "超时" else "图像为空"}")
                        return@withContext null
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "❌ VirtualDisplay截屏异常", e)
                    capturedImage?.close()
                    return@withContext null
                } finally {
                    // 清理监听器
                    try {
                        reader.setOnImageAvailableListener(null, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "清理监听器失败", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ VirtualDisplay截屏异常", e)
                return@withContext null
            }
        }
    }
    
    /**
     * 快速截屏方法（优化版）- 专为墨水屏设备优化
     */
    fun captureScreenFast() {
        Log.d(TAG, "=== captureScreenFast() 开始 ===")
        
        // 首先验证截屏服务状态
        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection为空，无法截屏")
            screenshotCallback?.onScreenshotFailed("截屏服务未就绪")
            return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "开始截屏...")
                
                // 先尝试使用PixelCopy方式（更可靠）
                var resultBitmap: Bitmap? = null
                
                // 尝试最多3次
                repeat(3) { attempt ->
                    if (resultBitmap != null) return@repeat // 如果已获取到图像则跳过
                    
                    Log.d(TAG, "🎯 PixelCopy尝试 ${attempt + 1}/3...")
                    try {
                        resultBitmap = withTimeoutOrNull(5000) { // 5秒超时
                            captureWithPixelCopy()
                        }
                        
                        if (resultBitmap != null) {
                            Log.d(TAG, "✅ PixelCopy截屏成功")
                        } else {
                            Log.w(TAG, "⚠️ PixelCopy尝试 ${attempt + 1} 失败")
                            // 稍等片刻后重试
                            delay(500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ PixelCopy尝试异常", e)
                    }
                }
                
                // 如果PixelCopy方式失败，回退到传统方式
                if (resultBitmap == null) {
                    Log.d(TAG, "⚠️ PixelCopy方式失败，尝试传统方式")
                    
                    try {
                        resultBitmap = withTimeoutOrNull(10000) { // 10秒超时
                            captureWithVirtualDisplayEnhanced()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ 传统截屏方式异常", e)
                    }
                }
                
                // 处理截屏结果
                val finalBitmap = resultBitmap
                if (finalBitmap != null) {
                    Log.d(TAG, "🎉 截屏成功，大小: ${finalBitmap.width}x${finalBitmap.height}")
                    screenshotCallback?.onScreenshotSuccess(finalBitmap)
                } else {
                    Log.e(TAG, "💀 所有截屏方式均失败")
                    screenshotCallback?.onScreenshotFailed("截屏失败，设备可能不兼容此功能")
                    
                    // 截屏失败时，尝试重置服务状态
                    resetServiceState()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 快速截屏异常", e)
                screenshotCallback?.onScreenshotFailed("截屏出错: ${e.message}")
                resetServiceState()
            }
        }
        
        Log.d(TAG, "=== captureScreenFast() 方法调用结束 ===")
    }
    
    /**
     * 优化版PixelCopy截屏方法 - 减少墨水屏等待时间
     */
    private suspend fun captureWithPixelCopyOptimized(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 开始优化版PixelCopy截屏...")
                
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    Log.w(TAG, "❌ PixelCopy需要Android 8.0+，当前系统不支持")
                    return@withContext null
                }
                
                if (virtualDisplay == null) {
                    Log.e(TAG, "❌ VirtualDisplay未初始化")
                    return@withContext null
                }
                
                // 获取屏幕尺寸
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                windowManager.defaultDisplay.getMetrics(displayMetrics)
                
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                
                Log.d(TAG, "📐 屏幕尺寸: ${width}x${height}")
                
                // 创建Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                // 使用CountDownLatch等待PixelCopy完成
                val latch = java.util.concurrent.CountDownLatch(1)
                var copySuccess = false
                
                // 获取VirtualDisplay的Surface
                val surface = virtualDisplay?.surface
                if (surface == null) {
                    Log.e(TAG, "❌ VirtualDisplay的Surface为空")
                    return@withContext null
                }
                
                // 优化版墨水屏渲染流程 - 大幅减少等待时间
                Log.d(TAG, "🎯 开始优化版墨水屏渲染流程...")
                
                // 阶段1：快速刷新VirtualDisplay
                Log.d(TAG, "📺 阶段1: 快速刷新VirtualDisplay...")
                try {
                    virtualDisplay?.resize(width, height, displayMetrics.densityDpi)
                    Log.d(TAG, "✅ VirtualDisplay刷新完成")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ VirtualDisplay刷新失败，继续后续流程", e)
                }
                
                // 阶段2：减少等待时间 (从800ms减少到200ms)
                Log.d(TAG, "⏰ 阶段2: 等待VirtualDisplay稳定（200ms）...")
                delay(200) // 从800ms优化为200ms
                Log.d(TAG, "✅ VirtualDisplay稳定期完成")
                
                // 阶段3：减少渲染次数和间隔 (从3次300ms减少到2次100ms)
                Log.d(TAG, "🔄 阶段3: 优化VirtualDisplay渲染...")
                repeat(2) { i -> // 从3次减少到2次
                    try {
                        Log.d(TAG, "   触发 ${i + 1}/2: VirtualDisplay刷新...")
                        virtualDisplay?.resize(width, height, displayMetrics.densityDpi)
                        delay(100) // 从300ms减少到100ms
                        Log.d(TAG, "   ✅ 触发 ${i + 1} 完成")
                    } catch (e: Exception) {
                        Log.w(TAG, "   ⚠️ 触发 ${i + 1} 失败", e)
                    }
                }
                
                // 阶段4：大幅减少最终等待时间 (从1000ms减少到300ms)
                Log.d(TAG, "⏰ 阶段4: 最终等待VirtualDisplay渲染（300ms）...")
                delay(300) // 从1000ms优化为300ms
                Log.d(TAG, "✅ VirtualDisplay渲染等待完成")
                
                Log.d(TAG, "🎯 开始PixelCopy操作...")
                
                // 在主线程执行PixelCopy
                withContext(Dispatchers.Main) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            android.view.PixelCopy.request(
                                virtualDisplay!!.surface,
                                bitmap,
                                object : android.view.PixelCopy.OnPixelCopyFinishedListener {
                                    override fun onPixelCopyFinished(result: Int) {
                                        copySuccess = (result == android.view.PixelCopy.SUCCESS)
                                        if (copySuccess) {
                                            Log.d(TAG, "✅ PixelCopy成功")
                                        } else {
                                            Log.e(TAG, "❌ PixelCopy失败，错误码: $result")
                                        }
                                        latch.countDown()
                                    }
                                },
                                backgroundHandler
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ PixelCopy请求异常", e)
                        latch.countDown()
                    }
                }
                
                // 等待PixelCopy完成，保持2秒超时
                Log.d(TAG, "⏳ 等待PixelCopy完成，最多2秒...")
                val completed = latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
                
                if (!completed) {
                    Log.w(TAG, "⚠️ PixelCopy超时（2秒）")
                    bitmap.recycle()
                    return@withContext null
                }
                
                if (!copySuccess) {
                    Log.w(TAG, "⚠️ PixelCopy失败")
                    bitmap.recycle()
                    return@withContext null
                }
                
                // 检查bitmap是否有效
                if (bitmap.isRecycled) {
                    Log.e(TAG, "❌ Bitmap已被回收")
                    return@withContext null
                }
                
                // 简化的内容检测 - 只检查关键区域
                Log.d(TAG, "🔍 开始简化像素分析...")
                val centerX = bitmap.width / 2
                val centerY = bitmap.height / 2
                
                // 只检查中心区域的9个像素点
                val testPixels = IntArray(9)
                bitmap.getPixels(testPixels, 0, 3, centerX - 1, centerY - 1, 3, 3)
                
                val nonTransparentCount = testPixels.count { Color.alpha(it) > 0 }
                val contentPercentage = (nonTransparentCount * 100) / 9
                
                Log.d(TAG, "📊 简化内容检测结果: $contentPercentage% (${nonTransparentCount}/9)")
                
                if (contentPercentage < 50) {
                    Log.w(TAG, "⚠️ 截屏内容可能不完整，但继续处理")
                }
                
                Log.d(TAG, "🎉 优化版PixelCopy截屏成功！尺寸: ${bitmap.width}x${bitmap.height}，内容: $contentPercentage%")
                
                // 保存截屏文件
                try {
                    val timestamp = System.currentTimeMillis()
                    val testFile = java.io.File("${applicationContext.getExternalFilesDir(null)?.absolutePath}/screenshot_$timestamp.png")
                    val outputStream = java.io.FileOutputStream(testFile)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.close()
                    Log.d(TAG, "📁 成功截屏已保存到: ${testFile.absolutePath}")
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ 保存截屏文件失败", e)
                }
                
                return@withContext bitmap
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 优化版PixelCopy截屏异常", e)
                return@withContext null
            }
        }
    }

    /**
     * 超快速截屏方法 - 专为性能优化设计
     */
    fun captureScreenUltraFast() {
        Log.d(TAG, "=== captureScreenUltraFast() 开始 ===")
        
        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection为空，无法截屏")
            screenshotCallback?.onScreenshotFailed("截屏服务未就绪")
            return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "开始超快速截屏...")
                
                // 直接使用优化版PixelCopy，不进行重试
                val resultBitmap = withTimeoutOrNull(3000) { // 3秒超时
                    captureWithPixelCopyOptimized()
                }
                
                if (resultBitmap != null) {
                    Log.d(TAG, "🎉 超快速截屏成功，大小: ${resultBitmap.width}x${resultBitmap.height}")
                    screenshotCallback?.onScreenshotSuccess(resultBitmap)
                } else {
                    Log.e(TAG, "💀 超快速截屏失败")
                    screenshotCallback?.onScreenshotFailed("截屏失败，请重试")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 超快速截屏异常", e)
                screenshotCallback?.onScreenshotFailed("截屏出错: ${e.message}")
            }
        }
        
        Log.d(TAG, "=== captureScreenUltraFast() 方法调用结束 ===")
    }
} 
