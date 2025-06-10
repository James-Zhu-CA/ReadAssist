package com.readassist.utils

import android.content.Context
import android.os.Build
import java.util.Locale

object DeviceUtils {

    /**
     * Check if the device is a Supernote device.
     */
    fun isSupernoteDevice(): Boolean {
        return Build.MANUFACTURER.equals("Ratta", ignoreCase = true) ||
                Build.BRAND.equals("Supernote", ignoreCase = true)
    }

    /**
     * Check if the device is an iReader device.
     * Checks for manufacturer, brand, and specific models like "X3 Pro".
     */
    fun isIReaderDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        val brand = Build.BRAND.lowercase(Locale.getDefault())
        val model = Build.MODEL.lowercase(Locale.getDefault())
        // 兼容ro.product.*属性（通过反射获取，防止部分ROM定制）
        val productBrand = getSystemProperty("ro.product.brand").lowercase(Locale.getDefault())
        val productManufacturer = getSystemProperty("ro.product.manufacturer").lowercase(Locale.getDefault())
        val productModel = getSystemProperty("ro.product.model").lowercase(Locale.getDefault())

        return manufacturer.contains("ireader") ||
               brand.contains("ireader") ||
               productBrand.contains("ireader") ||
               productManufacturer.contains("ireader") ||
               model.contains("x3 pro") ||
               model.contains("smart x3 pro") ||
               productModel.contains("x3 pro") ||
               productModel.contains("smart x3 pro")
    }

    /**
     * 获取系统属性，兼容部分ROM定制字段。
     */
    private fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Returns the type of the device.
     */
    fun getDeviceType(): DeviceType {
        return when {
            isSupernoteDevice() -> DeviceType.SUPERNOTE
            isIReaderDevice() -> DeviceType.IREADER
            else -> DeviceType.UNKNOWN
        }
    }

    /**
     * 检查是否是掌阅设备
     */
    fun isLibreReaderDevice(): Boolean {
        return Build.MANUFACTURER.lowercase().contains("libre") ||
               Build.MODEL.lowercase().contains("libre") ||
               Build.DEVICE.lowercase().contains("libre")
    }
}

enum class DeviceType {
    SUPERNOTE,
    IREADER,
    UNKNOWN
} 