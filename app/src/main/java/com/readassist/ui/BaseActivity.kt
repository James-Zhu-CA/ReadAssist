package com.readassist.ui

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.readassist.ReadAssistApplication
import com.readassist.utils.LanguageManager

/**
 * 基础Activity类，处理语言设置
 * 所有Activity都应该继承此类以确保正确应用语言设置
 */
abstract class BaseActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "ReadAssist_BaseActivity"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        val activityName = this::class.java.simpleName
        Log.e(TAG, "🔧 $activityName onCreate started")
        
        // 在onCreate中强制重新应用语言设置
        forceApplyLanguageSetting()
        
        super.onCreate(savedInstanceState)
        Log.e(TAG, "✅ $activityName onCreate language reapplication completed")
    }
    
    /**
     * 强制重新应用语言设置到当前Activity的资源
     */
    private fun forceApplyLanguageSetting() {
        try {
            val languageCode = getLanguageFromPreferences(this)
            Log.e(TAG, "🔄 Force reapplying language: '$languageCode'")
            
            if (languageCode != "system") {
                val locale = when (languageCode) {
                    "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
                    "en" -> java.util.Locale.ENGLISH
                    else -> return
                }
                
                Log.e(TAG, "🎯 Target locale: $locale")
                
                // 强制更新资源配置
                val config = android.content.res.Configuration(resources.configuration)
                java.util.Locale.setDefault(locale)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    config.setLocales(android.os.LocaleList(locale))
                } else {
                    @Suppress("DEPRECATION")
                    config.locale = locale
                }
                
                // 强制更新当前Activity的资源
                @Suppress("DEPRECATION")
                resources.updateConfiguration(config, resources.displayMetrics)
                
                Log.e(TAG, "🔄 Updated resources locale: ${resources.configuration.locales[0]}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to force apply language setting", e)
        }
    }
    
    override fun attachBaseContext(newBase: Context?) {
        val activityName = this::class.java.simpleName
                    Log.e(TAG, "🔧 $activityName attachBaseContext started")
        
        if (newBase == null) {
            Log.e(TAG, "⚠️ $activityName newBase is null, using default")
            super.attachBaseContext(newBase)
            return
        }
        
        try {
            // 获取用户设置的语言
            val languageCode = getLanguageFromPreferences(newBase)
            Log.e(TAG, "🔍 $activityName language setting: '$languageCode'")
            
            val locale = when (languageCode) {
                "zh" -> {
                    Log.e(TAG, "🇨🇳 $activityName applying Chinese locale")
                    java.util.Locale.SIMPLIFIED_CHINESE
                }
                "en" -> {
                    Log.e(TAG, "🇺🇸 $activityName applying English locale")
                    java.util.Locale.ENGLISH
                }
                "system" -> {
                    val systemLocale = getSystemLocale()
                    Log.e(TAG, "🌍 $activityName using system locale: $systemLocale")
                    systemLocale
                }
                else -> {
                    Log.e(TAG, "⚠️ $activityName unknown language code '$languageCode', using system locale")
                    getSystemLocale()
                }
            }
            
            Log.e(TAG, "🌐 $activityName final locale: $locale (${locale.language}_${locale.country})")
            
            // 创建本地化Context
            val context = createLocalizedContext(newBase, locale)
            super.attachBaseContext(context)
            
            Log.e(TAG, "✅ $activityName attachBaseContext completed successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ $activityName attachBaseContext failed", e)
            // 发生异常时使用原始context
            super.attachBaseContext(newBase)
        }
    }
    
    /**
     * 从SharedPreferences获取语言设置
     */
    private fun getLanguageFromPreferences(context: Context): String {
        return try {
            val prefs = context.getSharedPreferences("readassist_prefs", Context.MODE_PRIVATE)
            
            // 调试：打印所有相关信息
            Log.e(TAG, "🔍 SharedPreferences file: readassist_prefs")
            Log.e(TAG, "🔍 Context: ${context.javaClass.simpleName}")
            Log.e(TAG, "🔍 Looking for key: app_language")
            
            val language = prefs.getString("app_language", "system") ?: "system"
            Log.e(TAG, "🔍 Read language from preferences: '$language'")
            
            // 调试：打印所有保存的键值对
            val allPrefs = prefs.all
            Log.e(TAG, "🔍 All preferences:")
            for ((key, value) in allPrefs) {
                Log.e(TAG, "   $key = $value")
            }
            
            // 验证语言代码是否有效
            when (language) {
                "system", "zh", "en" -> {
                    Log.e(TAG, "✅ Valid language code: '$language'")
                    language
                }
                else -> {
                    Log.e(TAG, "⚠️ Invalid language code '$language', falling back to system")
                    "system"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get language from preferences", e)
            "system"
        }
    }
    
    /**
     * 获取系统默认语言
     */
    private fun getSystemLocale(): java.util.Locale {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            android.content.res.Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            android.content.res.Resources.getSystem().configuration.locale
        }
    }
    
    /**
     * 创建本地化Context
     */
    private fun createLocalizedContext(context: Context, locale: java.util.Locale): Context {
        java.util.Locale.setDefault(locale)
        
        val configuration = android.content.res.Configuration(context.resources.configuration)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            configuration.setLocales(android.os.LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }
        
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N_MR1) {
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            context
        }
    }
} 