package com.readassist.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 设备截屏目录管理器
 * 统一管理不同设备的截屏目录监控和SAF授权
 */
class DeviceScreenshotManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager
) {
    companion object {
        private const val TAG = "DeviceScreenshotManager"
        const val REQUEST_CODE_SAF_DIRECTORY = 2001
        
        // SAF权限配置键
        private const val PREF_KEY_IREADER_SAF_URI = "ireader_saf_uri"
        private const val PREF_KEY_SUPERNOTE_SAF_URI = "supernote_saf_uri"
        private const val PREF_KEY_GENERIC_SAF_URI = "generic_saf_uri"
    }

    /**
     * 设备截屏目录配置
     */
    data class ScreenshotDirectoryConfig(
        val deviceType: DeviceType,
        val displayName: String,
        val systemPath: String,
        val safPrefKey: String,
        val fileNamePattern: List<String>,
        val description: String
    )

    /**
     * 获取所有设备的截屏目录配置
     */
    fun getScreenshotDirectoryConfigs(): List<ScreenshotDirectoryConfig> {
        return listOf(
            // 掌阅设备
            ScreenshotDirectoryConfig(
                deviceType = DeviceType.IREADER,
                displayName = "掌阅设备",
                systemPath = "/storage/emulated/0/iReader/saveImage/tmp",
                safPrefKey = PREF_KEY_IREADER_SAF_URI,
                fileNamePattern = listOf("screenshot-", ".png", ".jpg"),
                description = "掌阅设备截屏目录，文件名格式：screenshot-时间戳.png"
            ),
            // Supernote设备
            ScreenshotDirectoryConfig(
                deviceType = DeviceType.SUPERNOTE,
                displayName = "Supernote设备",
                systemPath = "/storage/emulated/0/SCREENSHOT",
                safPrefKey = PREF_KEY_SUPERNOTE_SAF_URI,
                fileNamePattern = listOf("Screenshot_", "20", ".png", ".jpg"), // 支持Screenshot_xxx和20xxxxxx_xxxxxx格式
                description = "Supernote设备截屏目录，文件名格式：Screenshot_日期时间.png 或 20250622_223456.png"
            ),
            // 通用Android设备
            ScreenshotDirectoryConfig(
                deviceType = DeviceType.UNKNOWN,
                displayName = "标准Android设备",
                systemPath = "/storage/emulated/0/Pictures/Screenshots",
                safPrefKey = PREF_KEY_GENERIC_SAF_URI,
                fileNamePattern = listOf("Screenshot_", "screenshot_", ".png", ".jpg"),
                description = "标准Android截屏目录，文件名格式：Screenshot_日期时间.png"
            )
        )
    }

    /**
     * 获取当前设备适用的截屏目录配置
     */
    fun getCurrentDeviceConfig(): ScreenshotDirectoryConfig {
        val deviceType = DeviceUtils.getDeviceType()
        return getScreenshotDirectoryConfigs().find { it.deviceType == deviceType }
            ?: getScreenshotDirectoryConfigs().last() // 默认使用通用配置
    }

    /**
     * 检查设备是否已有截屏目录的SAF权限
     */
    fun hasDirectoryAccess(config: ScreenshotDirectoryConfig): Boolean {
        val savedUri = preferenceManager.getString(config.safPrefKey, "")
        if (savedUri.isNullOrEmpty()) {
            return false
        }

        return try {
            val uri = Uri.parse(savedUri)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: Exception) {
            Log.w(TAG, "SAF权限已失效 (${config.displayName}): ${e.message}")
            preferenceManager.setString(config.safPrefKey, "")
            false
        }
    }

    /**
     * 启动SAF目录选择器
     */
    fun requestDirectoryAccess(activity: Activity, config: ScreenshotDirectoryConfig) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, getInitialUri(config))
            }
        }
        
        Log.d(TAG, "启动SAF目录选择器: ${config.displayName}")
        activity.startActivityForResult(intent, REQUEST_CODE_SAF_DIRECTORY)
    }

    /**
     * 处理SAF目录选择结果
     */
    fun handleDirectoryAccessResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_CODE_SAF_DIRECTORY || resultCode != Activity.RESULT_OK) {
            return false
        }

        val uri = data?.data ?: return false
        
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            
            // 根据URI路径判断是哪个设备类型并保存
            val configs = getScreenshotDirectoryConfigs()
            val matchedConfig = configs.find { config ->
                uri.toString().contains(config.systemPath.substringAfterLast("/"))
            } ?: getCurrentDeviceConfig()
            
            preferenceManager.setString(matchedConfig.safPrefKey, uri.toString())
            
            Log.d(TAG, "SAF目录权限获取成功 (${matchedConfig.displayName}): $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "获取SAF权限失败", e)
            false
        }
    }

    /**
     * 获取截屏目录的DocumentFile
     */
    fun getScreenshotDirectory(config: ScreenshotDirectoryConfig): DocumentFile? {
        val savedUri = preferenceManager.getString(config.safPrefKey, "")
        if (savedUri.isNullOrEmpty()) {
            return null
        }

        return try {
            val uri = Uri.parse(savedUri)
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "获取DocumentFile失败 (${config.displayName})", e)
            null
        }
    }

    /**
     * 监控截屏目录中的新文件
     */
    suspend fun findRecentScreenshots(config: ScreenshotDirectoryConfig, maxAgeMs: Long = 5000): List<DocumentFile> = withContext(Dispatchers.IO) {
        val directory = getScreenshotDirectory(config) ?: return@withContext emptyList()
        
        val currentTime = System.currentTimeMillis()
        val screenshots = mutableListOf<DocumentFile>()
        
        try {
            directory.listFiles().forEach { file ->
                if (file.isFile && isScreenshotFile(file.name ?: "", config)) {
                    val lastModified = file.lastModified()
                    if (currentTime - lastModified <= maxAgeMs) {
                        screenshots.add(file)
                        Log.d(TAG, "找到最近的截屏文件 (${config.displayName}): ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描截屏文件失败 (${config.displayName})", e)
        }
        
        screenshots.sortedByDescending { it.lastModified() }
    }

    /**
     * 获取最新的截屏文件信息（同时检查系统目录和SAF目录）
     */
    fun getLatestScreenshotInfo(): ScreenshotInfo? {
        val configs = getScreenshotDirectoryConfigs()
        var latestInfo: ScreenshotInfo? = null
        
        for (config in configs) {
            // 检查系统目录
            val systemInfo = getLatestFromSystemDirectory(config)
            if (systemInfo != null && (latestInfo == null || systemInfo.lastModified > latestInfo.lastModified)) {
                latestInfo = systemInfo
            }
            
            // 检查SAF目录（如果有权限）
            if (hasDirectoryAccess(config)) {
                // 这里需要在协程中调用，暂时跳过SAF检查
                // TODO: 需要重构为挂起函数
            }
        }
        
        return latestInfo
    }

    /**
     * 从系统目录获取最新截屏
     */
    private fun getLatestFromSystemDirectory(config: ScreenshotDirectoryConfig): ScreenshotInfo? {
        val dir = File(config.systemPath)
        if (!dir.exists() || !dir.isDirectory) {
            return null
        }
        
        var latestFile: File? = null
        
        try {
            val files = dir.listFiles { file ->
                file.isFile && file.canRead() && isScreenshotFile(file.name, config)
            }
            
            files?.forEach { file ->
                if (latestFile == null || file.lastModified() > latestFile!!.lastModified()) {
                    latestFile = file
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描系统目录失败 (${config.displayName}): ${config.systemPath}", e)
        }
        
        return latestFile?.let { file ->
            ScreenshotInfo(
                filePath = file.absolutePath,
                fileName = file.name,
                lastModified = file.lastModified(),
                deviceType = config.deviceType,
                fromSAF = false
            )
        }
    }

    /**
     * 判断是否为截屏文件
     */
    private fun isScreenshotFile(fileName: String, config: ScreenshotDirectoryConfig): Boolean {
        val lowerName = fileName.lowercase()
        
        // 检查文件扩展名
        val hasValidExtension = config.fileNamePattern.any { pattern ->
            pattern.startsWith(".") && lowerName.endsWith(pattern.lowercase())
        }
        
        if (!hasValidExtension) return false
        
        // 根据设备类型进行更精确的文件名匹配
        return when (config.deviceType) {
            DeviceType.IREADER -> {
                // 掌阅设备：screenshot-时间戳.png
                lowerName.startsWith("screenshot-") && 
                lowerName.matches(Regex("screenshot-\\d+\\.(png|jpg)"))
            }
            DeviceType.SUPERNOTE -> {
                // Supernote设备：Screenshot_日期时间.png 或 20250622_223456.png
                lowerName.startsWith("screenshot_") && 
                lowerName.matches(Regex("screenshot_\\d{8}_\\d{6}\\.(png|jpg)")) ||
                lowerName.matches(Regex("\\d{8}_\\d{6}\\.(png|jpg)"))
            }
            DeviceType.UNKNOWN -> {
                // 通用Android：Screenshot_日期时间.png
                lowerName.startsWith("screenshot") && 
                (lowerName.matches(Regex("screenshot_\\d{8}_\\d{6}\\.(png|jpg)")) ||
                 lowerName.matches(Regex("screenshot.*\\.(png|jpg)")))
            }
        }
    }

    /**
     * 获取初始URI，尝试指向设备对应的目录
     */
    private fun getInitialUri(config: ScreenshotDirectoryConfig): Uri? {
        return try {
            val pathSegments = config.systemPath.split("/").drop(3) // 去掉 /storage/emulated/0
            val encodedPath = pathSegments.joinToString("%2F")
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A$encodedPath")
        } catch (e: Exception) {
            try {
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
            } catch (e2: Exception) {
                null
            }
        }
    }

    /**
     * 清除SAF权限设置
     */
    fun clearDirectoryAccess(config: ScreenshotDirectoryConfig) {
        val savedUri = preferenceManager.getString(config.safPrefKey, "")
        if (!savedUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(savedUri)
                context.contentResolver.releasePersistableUriPermission(
                    uri, 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "释放SAF权限失败 (${config.displayName})", e)
            }
        }
        
        preferenceManager.setString(config.safPrefKey, "")
        Log.d(TAG, "已清除SAF权限设置 (${config.displayName})")
    }

    /**
     * 获取设置状态信息
     */
    fun getStatusInfo(): String {
        val configs = getScreenshotDirectoryConfigs()
        val statusList = configs.map { config ->
            val hasAccess = hasDirectoryAccess(config)
            val status = if (hasAccess) "✅" else "❌"
            "$status ${config.displayName}: ${if (hasAccess) "已配置" else "未配置"}"
        }
        
        return "截屏目录监控状态：\n" + statusList.joinToString("\n")
    }

    /**
     * 格式化时间显示
     */
    fun formatTimeForDisplay(timestamp: Long): String {
        val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    /**
     * 截屏文件信息数据类
     */
    data class ScreenshotInfo(
        val filePath: String,
        val fileName: String,
        val lastModified: Long,
        val deviceType: DeviceType,
        val fromSAF: Boolean
    )
} 