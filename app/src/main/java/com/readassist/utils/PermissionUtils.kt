package com.readassist.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具类
 */
object PermissionUtils {
    
    // 权限请求码
    const val REQUEST_CODE_OVERLAY_PERMISSION = 1001
    const val REQUEST_CODE_ACCESSIBILITY_PERMISSION = 1002
    const val REQUEST_CODE_STORAGE_PERMISSION = 1003
    
    // 所需权限列表
    val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )
    
    /**
     * 权限状态数据类
     */
    data class PermissionStatus(
        val allGranted: Boolean,
        val missingPermissions: List<String>
    )
    
    /**
     * 检查悬浮窗权限
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true // Android 6.0 以下默认有权限
        }
    }
    
    /**
     * 请求悬浮窗权限
     */
    fun requestOverlayPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivityForResult(intent, REQUEST_CODE_OVERLAY_PERMISSION)
        }
    }
    
    /**
     * 检查辅助功能权限
     */
    fun hasAccessibilityPermission(context: Context): Boolean {
        val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) 
            as android.view.accessibility.AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(context.packageName) == true
    }
    
    /**
     * 请求辅助功能权限
     */
    fun requestAccessibilityPermission(activity: Activity) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        activity.startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY_PERMISSION)
    }
    
    /**
     * 检查存储权限
     */
    fun hasStoragePermissions(context: Context): PermissionStatus {
        val missingPermissions = mutableListOf<String>()
        
        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        
        return PermissionStatus(
            allGranted = missingPermissions.isEmpty(),
            missingPermissions = missingPermissions
        )
    }
    
    /**
     * 请求存储权限
     */
    fun requestStoragePermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            REQUIRED_PERMISSIONS,
            REQUEST_CODE_STORAGE_PERMISSION
        )
    }
    
    /**
     * 检查所有权限状态
     */
    fun checkAllPermissions(context: Context): PermissionStatus {
        val missingPermissions = mutableListOf<String>()
        
        // 检查悬浮窗权限
        if (!hasOverlayPermission(context)) {
            missingPermissions.add("悬浮窗权限")
        }
        
        // 检查辅助功能权限
        if (!hasAccessibilityPermission(context)) {
            missingPermissions.add("辅助功能权限")
        }
        
        // 检查存储权限
        val storageStatus = hasStoragePermissions(context)
        if (!storageStatus.allGranted) {
            missingPermissions.add("存储权限")
        }
        
        return PermissionStatus(
            allGranted = missingPermissions.isEmpty(),
            missingPermissions = missingPermissions
        )
    }
    
    /**
     * 权限检查器
     */
    class PermissionChecker(private val activity: Activity) {
        
        fun checkAndRequestPermissions(callback: PermissionCallback) {
            val permissionStatus = checkAllPermissions(activity)
            
            if (permissionStatus.allGranted) {
                callback.onPermissionGranted()
            } else {
                callback.onPermissionDenied(permissionStatus.missingPermissions)
                
                // 自动请求缺失的权限
                when {
                    !hasOverlayPermission(activity) -> requestOverlayPermission(activity)
                    !hasAccessibilityPermission(activity) -> requestAccessibilityPermission(activity)
                    !hasStoragePermissions(activity).allGranted -> requestStoragePermissions(activity)
                }
            }
        }
    }
    
    /**
     * 权限回调接口
     */
    interface PermissionCallback {
        fun onPermissionGranted()
        fun onPermissionDenied(missingPermissions: List<String>)
    }
} 