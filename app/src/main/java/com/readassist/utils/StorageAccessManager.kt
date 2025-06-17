package com.readassist.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Storage Access Framework 管理器
 * 专门处理掌阅设备等需要特殊目录访问权限的情况
 */
class StorageAccessManager(
    private val context: Context,
    private val preferenceManager: PreferenceManager
) {
    companion object {
        private const val TAG = "StorageAccessManager"
        const val REQUEST_CODE_SAF_DIRECTORY = 1001
        private const val PREF_KEY_IREADER_SAF_URI = "ireader_saf_uri"
    }

    /**
     * 检查是否已有掌阅截屏目录的SAF权限
     */
    fun hasIReaderDirectoryAccess(): Boolean {
        val savedUri = preferenceManager.getString(PREF_KEY_IREADER_SAF_URI, "")
        if (savedUri.isNullOrEmpty()) {
            return false
        }

        return try {
            val uri = Uri.parse(savedUri)
            // 检查权限是否仍然有效
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        } catch (e: Exception) {
            Log.w(TAG, "SAF权限已失效: ${e.message}")
            // 清除无效的URI
            preferenceManager.setString(PREF_KEY_IREADER_SAF_URI, "")
            false
        }
    }

    /**
     * 启动SAF目录选择器
     */
    fun requestIReaderDirectoryAccess(activity: Activity) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // 尝试预设到可能的掌阅目录
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, getInitialUri())
            }
        }
        
        Log.d(TAG, "启动SAF目录选择器")
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
            // 获取持久化权限
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            
            // 保存URI
            preferenceManager.setString(PREF_KEY_IREADER_SAF_URI, uri.toString())
            
            Log.d(TAG, "SAF目录权限获取成功: $uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "获取SAF权限失败", e)
            false
        }
    }

    /**
     * 获取掌阅截屏目录的DocumentFile
     */
    fun getIReaderScreenshotDirectory(): DocumentFile? {
        val savedUri = preferenceManager.getString(PREF_KEY_IREADER_SAF_URI, "")
        if (savedUri.isNullOrEmpty()) {
            return null
        }

        return try {
            val uri = Uri.parse(savedUri)
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "获取DocumentFile失败", e)
            null
        }
    }

    /**
     * 监控掌阅截屏目录中的新文件
     */
    suspend fun findRecentScreenshots(maxAgeMs: Long = 5000): List<DocumentFile> = withContext(Dispatchers.IO) {
        val directory = getIReaderScreenshotDirectory() ?: return@withContext emptyList()
        
        val currentTime = System.currentTimeMillis()
        val screenshots = mutableListOf<DocumentFile>()
        
        try {
            directory.listFiles().forEach { file ->
                if (file.isFile && isScreenshotFile(file.name ?: "")) {
                    val lastModified = file.lastModified()
                    if (currentTime - lastModified <= maxAgeMs) {
                        screenshots.add(file)
                        Log.d(TAG, "找到最近的截屏文件: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "扫描截屏文件失败", e)
        }
        
        screenshots.sortedByDescending { it.lastModified() }
    }

    /**
     * 判断是否为截屏文件
     */
    private fun isScreenshotFile(fileName: String): Boolean {
        val lowerName = fileName.lowercase()
        return (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) &&
               (lowerName.contains("screenshot") || lowerName.contains("screen") || 
                lowerName.contains("capture") || lowerName.contains("snap"))
    }

    /**
     * 获取初始URI，尝试指向可能的掌阅目录
     */
    private fun getInitialUri(): Uri? {
        return try {
            // 尝试构建指向掌阅截屏目录的URI: /storage/emulated/0/iReader/saveImage
            Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AiReader%2FsaveImage")
        } catch (e: Exception) {
            // 如果失败，回退到iReader根目录
            try {
                Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AiReader")
            } catch (e2: Exception) {
                // 最后回退到外部存储根目录
                try {
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary%3A")
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    /**
     * 清除SAF权限设置
     */
    fun clearIReaderDirectoryAccess() {
        val savedUri = preferenceManager.getString(PREF_KEY_IREADER_SAF_URI, "")
        if (!savedUri.isNullOrEmpty()) {
            try {
                val uri = Uri.parse(savedUri)
                context.contentResolver.releasePersistableUriPermission(
                    uri, 
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "释放SAF权限失败", e)
            }
        }
        
        preferenceManager.setString(PREF_KEY_IREADER_SAF_URI, "")
        Log.d(TAG, "已清除SAF权限设置")
    }

    /**
     * 获取设置状态信息
     */
    fun getStatusInfo(): String {
        return if (hasIReaderDirectoryAccess()) {
            val uri = preferenceManager.getString(PREF_KEY_IREADER_SAF_URI, "")
            "✅ 已配置掌阅截屏目录访问权限\n路径: ${getDisplayPath(uri)}"
        } else {
            "❌ 未配置掌阅截屏目录访问权限"
        }
    }

    /**
     * 获取用于显示的路径
     */
    private fun getDisplayPath(uriString: String?): String {
        if (uriString.isNullOrEmpty()) return "未知"
        
        return try {
            val uri = Uri.parse(uriString)
            // 尝试从URI中提取可读的路径
            uri.lastPathSegment?.let { segment ->
                if (segment.contains(":")) {
                    val parts = segment.split(":")
                    if (parts.size >= 2) {
                        "/storage/emulated/0/${parts[1]}"
                    } else {
                        segment
                    }
                } else {
                    segment
                }
            } ?: "未知路径"
        } catch (e: Exception) {
            "解析失败"
        }
    }
} 