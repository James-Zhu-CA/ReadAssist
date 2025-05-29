package com.readassist.utils

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 权限工具类
 */
object PermissionUtils {
    
    private const val TAG = "PermissionUtils"
    
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
     * 检查辅助功能权限是否被授予
     */
    fun hasAccessibilityPermission(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        val serviceName = "${context.packageName}/${context.packageName}.service.TextAccessibilityService"
        Log.d(TAG, "Checking Accessibility: Looking for '$serviceName'")
        Log.d(TAG, "Enabled Services: $enabledServices")
        
        if (enabledServices == null) {
            Log.d(TAG, "Enabled Accessibility Services string is null.")
            return false
        }

        val granted = enabledServices.split(':').any { it.equals(serviceName, ignoreCase = true) }
        Log.d(TAG, "Accessibility Granted by setting: $granted (using exact match in split list)")
        return granted
    }
    
    /**
     * 检查特定的服务是否正在运行
     */
    fun isSpecificServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Log.d(TAG, "Checking if service ${serviceClass.name} is running.")
        try {
            // getRunningServices is deprecated for non-system apps on Android O and above
            // but for self-checking, it might still provide some info or at least not crash immediately.
            // A more robust way for higher APIs involves foreground service checks or binding.
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                // Log.d(TAG, "Found running service: ${service.service.className}") // Can be very verbose
                if (serviceClass.name == service.service.className) {
                    Log.d(TAG, "Service ${serviceClass.name} IS running.")
                    return true
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException while checking running services. Assuming not running.", e)
            return false // Cannot determine, assume not running
        }
        Log.d(TAG, "Service ${serviceClass.name} is NOT running.")
        return false
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
        
        private var currentCallback: PermissionCallback? = null

        fun checkAndRequestPermissions(callback: PermissionCallback) {
            this.currentCallback = callback
            val permissionStatus = checkAllPermissions(activity)
            
            if (permissionStatus.allGranted) {
                currentCallback?.onPermissionGranted()
            } else {
                // Store missing permissions to check against later, or let callback handle it
                // currentCallback?.onPermissionDenied(permissionStatus.missingPermissions)
                
                // Request necessary permissions directly if missing
                val permsToRequest = mutableListOf<String>()
                if (!hasOverlayPermission(activity) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Overlay handled by startActivityForResult
                    requestOverlayPermission(activity) 
                }
                if (!hasAccessibilityPermission(activity)) {
                    // Accessibility handled by startActivityForResult
                    requestAccessibilityPermission(activity)
                }

                // Consolidate and request runtime permissions (like storage)
                REQUIRED_PERMISSIONS.forEach { perm ->
                    if (ContextCompat.checkSelfPermission(activity, perm) != PackageManager.PERMISSION_GRANTED) {
                        permsToRequest.add(perm)
                    }
                }
                if (permsToRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(
                        activity,
                        permsToRequest.toTypedArray(),
                        REQUEST_CODE_STORAGE_PERMISSION // Using a single code for all runtime perms for simplicity here
                    )
                } else {
                    // If no runtime perms to request, but other perms (overlay/accessibility) might be pending from user action
                    // Re-check all after a small delay or assume user will interact with settings screen.
                    // For now, if only special permissions were missing and requested via settings,
                    // this path means runtime perms are granted. We might need a more robust state machine here.
                    // If all requested permissions were special (overlay/accessibility), then grant/deny is via onActivityResult.
                    // If all permissions (including runtime) are now granted after this check sequence, call onPermissionGranted.
                    // This part is tricky because overlay and accessibility are not handled by onRequestPermissionsResult.
                    // The current callback is primarily for runtime permissions.
                    val finalStatus = checkAllPermissions(activity) // Re-check
                    if (finalStatus.allGranted) {
                        currentCallback?.onPermissionGranted()
                    } else {
                        // It's possible only special permissions are missing, which won't trigger onRequestPermissionsResult
                        // The onPermissionDenied might have been too early if we expect user to go to settings.
                        // For now, let's trigger onPermissionDenied if not all granted at this point if no runtime perms were requested.
                        currentCallback?.onPermissionDenied(finalStatus.missingPermissions)
                    }
                }
            }
        }

        fun handleRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
        ) {
            if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) { // Or a general app permission request code
                val missingPermissionsAfterRequest = mutableListOf<String>()
                grantResults.forEachIndexed { index, grantResult ->
                    if (grantResult != PackageManager.PERMISSION_GRANTED) {
                        missingPermissionsAfterRequest.add(permissions[index])
                    }
                }

                if (missingPermissionsAfterRequest.isEmpty()) {
                    // Check special permissions again, as they are handled separately
                    val finalOverlayGranted = hasOverlayPermission(activity)
                    val finalAccessibilityGranted = hasAccessibilityPermission(activity)
                    if (finalOverlayGranted && finalAccessibilityGranted) {
                        currentCallback?.onPermissionGranted()
                    } else {
                        val finalMissing = mutableListOf<String>()
                        if (!finalOverlayGranted) finalMissing.add("悬浮窗权限")
                        if (!finalAccessibilityGranted) finalMissing.add("辅助功能权限")
                        currentCallback?.onPermissionDenied(finalMissing)
                    }
                } else {
                    val finalMissing = mutableListOf<String>()
                    if (!hasOverlayPermission(activity)) finalMissing.add("悬浮窗权限")
                    if (!hasAccessibilityPermission(activity)) finalMissing.add("辅助功能权限")
                    finalMissing.addAll(missingPermissionsAfterRequest)
                    currentCallback?.onPermissionDenied(finalMissing)
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