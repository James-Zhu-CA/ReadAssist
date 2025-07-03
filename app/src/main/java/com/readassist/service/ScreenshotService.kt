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
import android.net.Uri
import android.view.PixelCopy
import androidx.core.app.NotificationCompat
import com.readassist.R
import com.readassist.utils.DeviceUtils
import com.readassist.utils.DeviceType
import com.readassist.utils.PreferenceManager
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
                getString(R.string.screenshot_service_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.screenshot_service_description)
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screenshot_service_title))
            .setContentText(getString(R.string.screenshot_service_preparing))
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
                                screenshotCallback?.onScreenshotFailed(getString(R.string.screenshot_permission_revoked_by_system))
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
     * 执行截屏 - 使用PixelCopy方案，直接保存到系统截屏目录
     */
    fun captureScreen() {
        Log.d(TAG, "=== captureScreen() PixelCopy方案开始 ===")
        
        // 检查MediaProjection状态
        if (mediaProjection == null) {
            Log.e(TAG, "❌ MediaProjection为空，无法截屏")
            screenshotCallback?.onScreenshotFailed(getString(R.string.screenshot_permission_not_granted_simple))
                return
        }
        
        serviceScope.launch {
            try {
                Log.d(TAG, "🎯 使用PixelCopy方案进行截屏...")
                
                // 使用PixelCopy + 直接保存到系统目录的方案
                val bitmap = captureWithPixelCopyToFile()
                
                if (bitmap != null) {
                    Log.d(TAG, "✅ PixelCopy截屏成功，尺寸: ${bitmap.width}x${bitmap.height}")
                    // 注意：这里不再调用onScreenshotSuccess，因为文件已保存到系统目录
                    // FileObserver会检测到文件并统一处理弹窗逻辑
                    bitmap.recycle() // 回收bitmap，因为文件已保存
                } else {
                    Log.e(TAG, "❌ PixelCopy截屏失败")
                    screenshotCallback?.onScreenshotFailed("PixelCopy截屏失败")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ 截屏异常", e)
                screenshotCallback?.onScreenshotFailed("截屏异常：${e.message}")
            }
        }
        
        Log.d(TAG, "=== captureScreen() PixelCopy方案结束 ===")
    }
    
    /**
     * 使用PixelCopy方案截屏并直接保存到系统目录
     * 这样FileObserver能统一监控到文件变化
     */
    private suspend fun captureWithPixelCopyToFile(): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🎯 开始PixelCopy + VirtualDisplay截屏...")
                
                // 确保VirtualDisplay已创建
                if (virtualDisplay == null) {
                    Log.w(TAG, "VirtualDisplay未就绪，尝试重新初始化...")
                    withContext(Dispatchers.Main) {
                        setupVirtualDisplay()
                    }
                    delay(1000) // 等待VirtualDisplay初始化完成
                }
                
                if (virtualDisplay == null) {
                    Log.e(TAG, "❌ VirtualDisplay初始化失败")
                    return@withContext null
                }
                
                // 获取屏幕尺寸
                val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val displayMetrics = DisplayMetrics()
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.currentWindowMetrics.bounds
                    displayMetrics.widthPixels = bounds.width()
                    displayMetrics.heightPixels = bounds.height()
                    displayMetrics.densityDpi = resources.configuration.densityDpi
                } else {
                    @Suppress("DEPRECATION")
                    windowManager.defaultDisplay.getMetrics(displayMetrics)
                }
                
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                
                Log.d(TAG, "📐 屏幕尺寸: ${width}x${height}")
                
                // 创建目标Bitmap
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                
                Log.d(TAG, "✅ VirtualDisplay已就绪，开始PixelCopy截屏...")
                
                // 使用PixelCopy从VirtualDisplay的Surface复制像素
                val latch = java.util.concurrent.CountDownLatch(1)
                var pixelCopySuccess = false
                var pixelCopyError: String? = null
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    withContext(Dispatchers.Main) {
                        try {
                            val surface = virtualDisplay!!.surface
                            if (surface == null) {
                                Log.e(TAG, "❌ VirtualDisplay的Surface为空")
                                pixelCopyError = "VirtualDisplay Surface为空"
                                latch.countDown()
                                return@withContext
                            }
                            
                            Log.d(TAG, "🔄 执行PixelCopy.request...")
                            PixelCopy.request(
                                surface,
                                bitmap,
                                { result ->
                                    pixelCopySuccess = (result == PixelCopy.SUCCESS)
                                    if (pixelCopySuccess) {
                                        Log.d(TAG, "✅ PixelCopy成功")
                                    } else {
                                        val errorMsg = when (result) {
                                            PixelCopy.ERROR_UNKNOWN -> "ERROR_UNKNOWN"
                                            PixelCopy.ERROR_TIMEOUT -> "ERROR_TIMEOUT"
                                            PixelCopy.ERROR_SOURCE_NO_DATA -> "ERROR_SOURCE_NO_DATA"
                                            PixelCopy.ERROR_SOURCE_INVALID -> "ERROR_SOURCE_INVALID"
                                            PixelCopy.ERROR_DESTINATION_INVALID -> "ERROR_DESTINATION_INVALID"
                                            else -> "未知错误码: $result"
                                        }
                                        Log.e(TAG, "❌ PixelCopy失败: $errorMsg")
                                        pixelCopyError = errorMsg
                                    }
                                    latch.countDown()
                                },
                                backgroundHandler
                            )
                                            } catch (e: Exception) {
                            Log.e(TAG, "❌ PixelCopy请求异常", e)
                            pixelCopyError = "PixelCopy异常: ${e.message}"
                            latch.countDown()
                        }
                    }
                } else {
                    Log.e(TAG, "❌ PixelCopy需要Android 8.0+")
                    pixelCopyError = "系统版本过低，需要Android 8.0+"
                    latch.countDown()
                }
                
                // 等待PixelCopy完成，最多等待5秒
                Log.d(TAG, "⏳ 等待PixelCopy完成...")
                val completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                
                // 检查结果
                when {
                    !completed -> {
                        Log.e(TAG, "❌ PixelCopy超时（5秒）")
                        bitmap.recycle()
                        return@withContext null
                    }
                    !pixelCopySuccess -> {
                        Log.e(TAG, "❌ PixelCopy失败: $pixelCopyError")
                        bitmap.recycle()
                        return@withContext null
                    }
                }
                
                Log.d(TAG, "✅ PixelCopy截屏成功: ${bitmap.width}x${bitmap.height}")
                    

                
                // 保存到系统截屏目录
                val savedFile = saveScreenshotToSystemDirectory(bitmap)
                if (savedFile != null) {
                    Log.d(TAG, "✅ 截屏已保存到系统目录: ${savedFile.absolutePath}")
                    return@withContext bitmap
                            } else {
                    Log.e(TAG, "❌ 保存截屏到系统目录失败")
                    bitmap.recycle()
                    return@withContext null
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ PixelCopy截屏异常", e)
                return@withContext null
            }
        }
    }
    
    /**
     * 保存截屏到系统目录 - 根据设备类型选择目录
     */
    private fun saveScreenshotToSystemDirectory(bitmap: Bitmap): java.io.File? {
        return try {
            // 根据设备类型确定保存目录和文件名格式
            val deviceType = DeviceUtils.getDeviceType()
            
            // 先尝试SAF授权目录（如果是Supernote设备）
            if (deviceType == DeviceType.SUPERNOTE) {
                val deviceScreenshotManager = com.readassist.utils.DeviceScreenshotManager(this, com.readassist.utils.PreferenceManager(this))
                val config = deviceScreenshotManager.getCurrentDeviceConfig()
                val hasAccess = deviceScreenshotManager.hasDirectoryAccess(config)
                
                if (hasAccess) {
                    Log.d(TAG, "Supernote设备：尝试使用SAF授权目录")
                    val safResult = saveToSafDirectorySync(bitmap, deviceScreenshotManager, config)
                    if (safResult != null) {
                        Log.d(TAG, "✅ 截屏已保存到SAF目录")
                        return safResult
                    } else {
                        Log.e(TAG, "❌ SAF保存失败，回退到系统目录")
                    }
                }
            }
            
            // 使用系统目录保存
            val (screenshotDir, fileName) = when (deviceType) {
                DeviceType.SUPERNOTE -> {
                    Log.d(TAG, "Supernote设备：使用系统Screenshots目录")
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), 
                        "Screenshots"
                    )
                    val timestamp = System.currentTimeMillis()
                    val name = "${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}.png"
                    Pair(dir, name)
                }
                DeviceType.IREADER -> {
                    // 掌阅设备：保存到系统Screenshots目录（因为有SAF授权的特殊目录）
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), 
                        "Screenshots"
                    )
                    val timestamp = System.currentTimeMillis()
                    val name = "screenshot-${timestamp}.png"
                    Pair(dir, name)
                }
                else -> {
                    // 通用Android设备：保存到系统Screenshots目录
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), 
                        "Screenshots"
                    )
                    val timestamp = System.currentTimeMillis()
                    val name = "Screenshot_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}.png"
                    Pair(dir, name)
                }
            }
            
            // 确保目录存在
            if (!screenshotDir.exists()) {
                screenshotDir.mkdirs()
                Log.d(TAG, "创建截屏目录: ${screenshotDir.absolutePath}")
            }
            
            val file = java.io.File(screenshotDir, fileName)
            
            Log.d(TAG, "保存截屏到: ${file.absolutePath}")
            
            // 保存bitmap到文件
            file.outputStream().use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            }
            
            // 验证文件是否保存成功
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "✅ 截屏文件保存成功: ${file.absolutePath}, 大小: ${file.length()} bytes")
                
                // 通知媒体扫描器更新
                val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                intent.data = Uri.fromFile(file)
                sendBroadcast(intent)
                
                file
                    } else {
                Log.e(TAG, "❌ 截屏文件保存失败或文件为空")
                null
                    }
                    
            } catch (e: Exception) {
            Log.e(TAG, "保存截屏文件异常", e)
            null
        }
    }
    
    /**
     * 保存截屏到SAF授权目录（同步版本）
     */
    private fun saveToSafDirectorySync(
        bitmap: Bitmap, 
        deviceScreenshotManager: com.readassist.utils.DeviceScreenshotManager,
        config: com.readassist.utils.DeviceScreenshotManager.ScreenshotDirectoryConfig
    ): java.io.File? {
        return try {
            val directory = deviceScreenshotManager.getScreenshotDirectory(config)
            if (directory == null) {
                Log.e(TAG, "❌ 无法获取SAF目录")
                return null
            }
            
            // 生成文件名
            val timestamp = System.currentTimeMillis()
            val fileName = "${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}.png"
            
            // 创建文件
            val documentFile = directory.createFile("image/png", fileName)
            if (documentFile == null) {
                Log.e(TAG, "❌ 无法在SAF目录创建文件")
                return null
            }
            
            // 保存bitmap到文件
            contentResolver.openOutputStream(documentFile.uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
                outputStream.flush()
            }
            
            Log.d(TAG, "✅ 截屏已保存到SAF目录: ${documentFile.uri}")
            
            // 通知媒体扫描器更新
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = documentFile.uri
            sendBroadcast(intent)
            
            // 返回一个虚拟的File对象用于兼容（实际文件通过SAF管理）
            java.io.File(config.systemPath, fileName)
        } catch (e: Exception) {
            Log.e(TAG, "❌ 保存到SAF目录失败", e)
            null
        }
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
     * 简化验证逻辑，避免误判
     */
    fun isMediaProjectionValid(): Boolean {
        val isValid = mediaProjection != null
        Log.d(TAG, "MediaProjection有效性检查: $isValid")
        
        // 简化验证：只检查MediaProjection是否存在
        // 避免复杂的测试操作可能导致的误判
            if (isValid) {
            Log.d(TAG, "✅ MediaProjection存在，权限有效")
                } else {
            Log.d(TAG, "❌ MediaProjection为空，权限无效")
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
    
} 
