package com.readassist.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import android.util.Log
import java.util.Locale

object LanguageManager {
    
    private const val TAG = "LanguageManager"
    
    enum class Language(val code: String, val displayNameRes: Int) {
        SYSTEM("system", com.readassist.R.string.language_system),
        CHINESE("zh", com.readassist.R.string.language_chinese),
        ENGLISH("en", com.readassist.R.string.language_english)
    }
    
    /**
     * 应用语言设置
     */
    fun applyLanguage(context: Context, language: Language): Context {
        Log.d(TAG, "🌐 applyLanguage: $language")
        
        try {
            val locale = when (language) {
                Language.SYSTEM -> getSystemLocale()
                Language.CHINESE -> Locale.CHINESE
                Language.ENGLISH -> Locale.ENGLISH
            }
            
            Log.d(TAG, "🌍 Target locale: $locale")
            val result = updateLocale(context, locale)
            Log.d(TAG, "✅ Language applied successfully")
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to apply language: $language", e)
            // 发生异常时返回原始context
            return context
        }
    }
    
    /**
     * 获取当前设置的语言
     */
    fun getCurrentLanguage(context: Context): Language {
        Log.d(TAG, "🔍 getCurrentLanguage called")
        
        try {
            val app = context.applicationContext
            if (app !is com.readassist.ReadAssistApplication) {
                Log.w(TAG, "⚠️ ApplicationContext is not ReadAssistApplication, using SYSTEM")
                return Language.SYSTEM
            }
            
            // 检查preferenceManager是否已初始化
            if (!isPreferenceManagerInitialized(app)) {
                Log.w(TAG, "⚠️ PreferenceManager not initialized, using SYSTEM")
                return Language.SYSTEM
            }
            
            val languageCode = app.preferenceManager.getAppLanguage()
            val language = Language.values().find { it.code == languageCode } ?: Language.SYSTEM
            
            Log.d(TAG, "🌐 Current language: $languageCode -> $language")
            return language
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to get current language", e)
            return Language.SYSTEM
        }
    }
    
    /**
     * 安全地检查preferenceManager是否已初始化
     */
    private fun isPreferenceManagerInitialized(app: com.readassist.ReadAssistApplication): Boolean {
        return try {
            app.preferenceManager
            true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 设置应用语言
     */
    fun setAppLanguage(context: Context, language: Language) {
        Log.d(TAG, "💾 setAppLanguage: $language")
        
        try {
            val app = context.applicationContext
            if (app !is com.readassist.ReadAssistApplication) {
                Log.e(TAG, "❌ ApplicationContext is not ReadAssistApplication")
                return
            }
            
            if (!isPreferenceManagerInitialized(app)) {
                Log.e(TAG, "❌ PreferenceManager not initialized")
                return
            }
            
            app.preferenceManager.setAppLanguage(language.code)
            Log.d(TAG, "✅ Language setting saved: ${language.code}")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set app language", e)
        }
    }
    
    /**
     * 获取系统默认语言
     */
    private fun getSystemLocale(): Locale {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Resources.getSystem().configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            Resources.getSystem().configuration.locale
        }
    }
    
    /**
     * 更新Context的语言设置
     */
    private fun updateLocale(context: Context, locale: Locale): Context {
        try {
            Log.d(TAG, "🔄 updateLocale: $locale")
            
            Locale.setDefault(locale)
            
            val configuration = Configuration(context.resources.configuration)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.setLocales(LocaleList(locale))
                Log.d(TAG, "📱 Using LocaleList for API ${Build.VERSION.SDK_INT}")
            } else {
                @Suppress("DEPRECATION")
                configuration.locale = locale
                Log.d(TAG, "📱 Using legacy locale for API ${Build.VERSION.SDK_INT}")
            }
            
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val result = context.createConfigurationContext(configuration)
                Log.d(TAG, "✅ Created configuration context")
                result
            } else {
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
                Log.d(TAG, "✅ Updated configuration legacy")
                context
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to update locale", e)
            return context
        }
    }
    
    /**
     * 检查是否需要重启应用以应用语言更改
     */
    fun shouldRestartForLanguageChange(context: Context, newLanguage: Language): Boolean {
        val currentLanguage = getCurrentLanguage(context)
        return currentLanguage != newLanguage
    }
    
    /**
     * 获取语言显示名称
     */
    fun getLanguageDisplayName(context: Context, language: Language): String {
        return context.getString(language.displayNameRes)
    }
    
    /**
     * 获取所有支持的语言
     */
    fun getSupportedLanguages(): Array<Language> {
        return Language.values()
    }
} 