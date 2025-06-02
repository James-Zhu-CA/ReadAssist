package com.readassist.utils

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.readassist.model.AiPlatform
import com.readassist.model.AiModel
import java.security.GeneralSecurityException
import java.io.IOException

class PreferenceManager(private val context: Context) {
    
    companion object {
        // 偏好设置文件名
        private const val PREF_NAME = "readassist_prefs"
        private const val ENCRYPTED_PREF_NAME = "readassist_secure_prefs"
        
        // Key 常量
        private const val KEY_CURRENT_AI_PLATFORM = "current_ai_platform"
        private const val KEY_CURRENT_AI_MODEL = "current_ai_model"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_SILICONFLOW_API_KEY = "siliconflow_api_key"
        private const val KEY_CUSTOM_MODELS = "custom_models"
        private const val KEY_PROMPT_TEMPLATE = "prompt_template"
        private const val KEY_FLOATING_BUTTON_X = "floating_button_x"
        private const val KEY_FLOATING_BUTTON_Y = "floating_button_y"
        private const val KEY_IS_FIRST_LAUNCH = "is_first_launch"
        private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
        private const val KEY_AUTO_ANALYZE_ENABLED = "auto_analyze_enabled"
        private const val KEY_SCREENSHOT_PERMISSION_GRANTED = "screenshot_permission_granted"
        private const val KEY_SCREENSHOT_RESULT_CODE = "screenshot_result_code"
        private const val KEY_SCREENSHOT_RESULT_DATA = "screenshot_result_data"
        private const val KEY_AI_SETUP_COMPLETED = "ai_setup_completed"
        
        // 默认值
        private const val DEFAULT_PROMPT = "请用用户提问的语言回答问题：\n\n[TEXT]"
    }
    
    // 普通偏好设置
    private val normalPrefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    
    // 加密偏好设置（用于存储敏感信息）
    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            // 降级到普通 SharedPreferences（不推荐，但确保应用可用）
            context.getSharedPreferences("${ENCRYPTED_PREF_NAME}_fallback", Context.MODE_PRIVATE)
        } catch (e: IOException) {
            context.getSharedPreferences("${ENCRYPTED_PREF_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }
    
    // === AI 平台和模型管理 ===
    
    /**
     * 设置当前AI平台
     */
    fun setCurrentAiPlatform(platform: AiPlatform) {
        normalPrefs.edit().putString(KEY_CURRENT_AI_PLATFORM, platform.name).apply()
    }
    
    /**
     * 获取当前AI平台
     */
    fun getCurrentAiPlatform(): AiPlatform {
        val platformName = normalPrefs.getString(KEY_CURRENT_AI_PLATFORM, AiPlatform.GEMINI.name)
        return try {
            AiPlatform.valueOf(platformName ?: AiPlatform.GEMINI.name)
        } catch (e: IllegalArgumentException) {
            AiPlatform.GEMINI
        }
    }
    
    /**
     * 设置当前AI模型
     */
    fun setCurrentAiModel(modelId: String) {
        normalPrefs.edit().putString(KEY_CURRENT_AI_MODEL, modelId).apply()
    }
    
    /**
     * 获取当前AI模型ID
     */
    fun getCurrentAiModelId(): String {
        return normalPrefs.getString(KEY_CURRENT_AI_MODEL, "gemini-2.0-flash") ?: "gemini-2.0-flash"
    }
    
    /**
     * 获取当前AI模型对象
     */
    fun getCurrentAiModel(): AiModel? {
        val modelId = getCurrentAiModelId()
        val platform = getCurrentAiPlatform()
        
        // 先从默认模型中查找
        val defaultModels = AiModel.getDefaultModels()
        return defaultModels.find { it.id == modelId && it.platform == platform }
            ?: AiModel.getDefaultModelForPlatform(platform)
    }
    
    // === API Key 管理 ===
    
    /**
     * 设置指定平台的API Key
     */
    fun setApiKey(platform: AiPlatform, apiKey: String) {
        val key = when (platform) {
            AiPlatform.GEMINI -> KEY_GEMINI_API_KEY
            AiPlatform.SILICONFLOW -> KEY_SILICONFLOW_API_KEY
        }
        encryptedPrefs.edit().putString(key, apiKey).apply()
    }
    
    /**
     * 获取指定平台的API Key
     */
    fun getApiKey(platform: AiPlatform): String? {
        val key = when (platform) {
            AiPlatform.GEMINI -> KEY_GEMINI_API_KEY
            AiPlatform.SILICONFLOW -> KEY_SILICONFLOW_API_KEY
        }
        return encryptedPrefs.getString(key, null)
    }
    
    /**
     * 获取当前平台的API Key
     */
    fun getApiKey(): String? {
        return getApiKey(getCurrentAiPlatform())
    }
    
    /**
     * 检查指定平台是否有API Key
     */
    fun hasApiKey(platform: AiPlatform): Boolean {
        return !getApiKey(platform).isNullOrBlank()
    }
    
    /**
     * 检查当前平台是否有API Key
     */
    fun hasApiKey(): Boolean {
        return hasApiKey(getCurrentAiPlatform())
    }
    
    /**
     * 清除指定平台的API Key
     */
    fun clearApiKey(platform: AiPlatform) {
        val key = when (platform) {
            AiPlatform.GEMINI -> KEY_GEMINI_API_KEY
            AiPlatform.SILICONFLOW -> KEY_SILICONFLOW_API_KEY
        }
        encryptedPrefs.edit().remove(key).apply()
    }
    
    /**
     * 清除所有API Key
     */
    fun clearApiKey() {
        encryptedPrefs.edit()
            .remove(KEY_GEMINI_API_KEY)
            .remove(KEY_SILICONFLOW_API_KEY)
            .apply()
    }
    
    // === AI 设置状态 ===
    
    /**
     * 设置AI设置完成状态
     */
    fun setAiSetupCompleted(completed: Boolean) {
        normalPrefs.edit().putBoolean(KEY_AI_SETUP_COMPLETED, completed).apply()
    }
    
    /**
     * 检查AI设置是否完成
     */
    fun isAiSetupCompleted(): Boolean {
        return normalPrefs.getBoolean(KEY_AI_SETUP_COMPLETED, false)
    }
    
    /**
     * 检查当前配置是否可用
     */
    fun isCurrentConfigurationValid(): Boolean {
        val platform = getCurrentAiPlatform()
        val apiKey = getApiKey(platform)
        val model = getCurrentAiModel()
        
        return !apiKey.isNullOrBlank() && 
               model != null && 
               apiKey.matches(platform.keyValidationPattern.toRegex())
    }
    
    // === 兼容性方法（保持向后兼容） ===
    
    @Deprecated("使用 hasApiKey() 替代")
    fun isUsingDefaultApiKey(): Boolean {
        return !hasApiKey()
    }
    
    // === 提示模板管理 ===
    
    fun setPromptTemplate(template: String) {
        normalPrefs.edit().putString(KEY_PROMPT_TEMPLATE, template).apply()
    }
    
    fun getPromptTemplate(): String {
        return normalPrefs.getString(KEY_PROMPT_TEMPLATE, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    }
    
    // === 悬浮按钮位置 ===
    
    fun setFloatingButtonPosition(x: Int, y: Int) {
        normalPrefs.edit()
            .putInt(KEY_FLOATING_BUTTON_X, x)
            .putInt(KEY_FLOATING_BUTTON_Y, y)
            .apply()
    }
    
    fun getFloatingButtonX(): Int {
        return normalPrefs.getInt(KEY_FLOATING_BUTTON_X, -1)
    }
    
    fun getFloatingButtonY(): Int {
        return normalPrefs.getInt(KEY_FLOATING_BUTTON_Y, -1)
    }
    
    // === 应用状态 ===
    
    fun setFirstLaunch(isFirst: Boolean) {
        normalPrefs.edit().putBoolean(KEY_IS_FIRST_LAUNCH, isFirst).apply()
    }
    
    fun isFirstLaunch(): Boolean {
        return normalPrefs.getBoolean(KEY_IS_FIRST_LAUNCH, true)
    }
    
    fun setAccessibilityEnabled(enabled: Boolean) {
        normalPrefs.edit().putBoolean(KEY_ACCESSIBILITY_ENABLED, enabled).apply()
    }
    
    fun isAccessibilityEnabled(): Boolean {
        return normalPrefs.getBoolean(KEY_ACCESSIBILITY_ENABLED, false)
    }
    
    /**
     * 设置字符串偏好设置
     */
    fun setString(key: String, value: String) {
        normalPrefs.edit().putString(key, value).apply()
    }
    
    /**
     * 获取字符串偏好设置
     */
    fun getString(key: String, defaultValue: String): String {
        return normalPrefs.getString(key, defaultValue) ?: defaultValue
    }
    
    fun setAutoAnalyzeEnabled(enabled: Boolean) {
        normalPrefs.edit().putBoolean(KEY_AUTO_ANALYZE_ENABLED, enabled).apply()
    }
    
    fun isAutoAnalyzeEnabled(): Boolean {
        return normalPrefs.getBoolean(KEY_AUTO_ANALYZE_ENABLED, true)
    }
    
    // === 截屏权限管理 ===
    
    fun setScreenshotPermissionGranted(granted: Boolean) {
        normalPrefs.edit().putBoolean(KEY_SCREENSHOT_PERMISSION_GRANTED, granted).apply()
    }
    
    fun isScreenshotPermissionGranted(): Boolean {
        return normalPrefs.getBoolean(KEY_SCREENSHOT_PERMISSION_GRANTED, false)
    }
    
    /**
     * 设置截屏权限数据（同时设置resultCode和resultDataUri）
     */
    fun setScreenshotPermissionData(resultCode: Int, resultDataUri: String?) {
        normalPrefs.edit()
            .putInt(KEY_SCREENSHOT_RESULT_CODE, resultCode)
            .putString(KEY_SCREENSHOT_RESULT_DATA, resultDataUri)
            .apply()
    }
    
    /**
     * 设置截屏权限的结果代码
     */
    fun setScreenshotResultCode(resultCode: Int) {
        normalPrefs.edit().putInt(KEY_SCREENSHOT_RESULT_CODE, resultCode).apply()
    }
    
    /**
     * 设置截屏权限的结果数据URI
     */
    fun setScreenshotResultDataUri(resultDataUri: String?) {
        normalPrefs.edit().putString(KEY_SCREENSHOT_RESULT_DATA, resultDataUri).apply()
    }
    
    fun getScreenshotResultCode(): Int {
        return normalPrefs.getInt(KEY_SCREENSHOT_RESULT_CODE, -1)
    }
    
    fun getScreenshotResultDataUri(): String? {
        return normalPrefs.getString(KEY_SCREENSHOT_RESULT_DATA, null)
    }
    
    fun clearScreenshotPermission() {
        normalPrefs.edit()
            .remove(KEY_SCREENSHOT_PERMISSION_GRANTED)
            .remove(KEY_SCREENSHOT_RESULT_CODE)
            .remove(KEY_SCREENSHOT_RESULT_DATA)
            .apply()
    }
    
    /**
     * 清除截屏权限结果数据
     */
    fun clearScreenshotResultData() {
        normalPrefs.edit()
            .remove(KEY_SCREENSHOT_RESULT_CODE)
            .remove(KEY_SCREENSHOT_RESULT_DATA)
            .apply()
    }
    
    // === 清理方法 ===
    
    fun clearAllPreferences() {
        normalPrefs.edit().clear().apply()
        encryptedPrefs.edit().clear().apply()
    }
} 