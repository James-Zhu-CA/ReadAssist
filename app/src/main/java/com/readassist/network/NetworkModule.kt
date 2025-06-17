package com.readassist.network

import com.readassist.BuildConfig
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    
    private const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/"
    private const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/"
    
    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .apply {
            // 只在调试版本中添加日志拦截器
            if (BuildConfig.DEBUG) {
            addInterceptor(loggingInterceptor)
            }
        }
        .build()
    
    // 自定义Gson配置，解决ProGuard问题
    private val gson: Gson = GsonBuilder()
        .setLenient()
        .disableHtmlEscaping()
        .create()
    
    // Gemini API Retrofit 实例
    private val geminiRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(GEMINI_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    // SiliconFlow API Retrofit 实例
    private val siliconFlowRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl(SILICONFLOW_BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
    
    // 保持向后兼容
    @Deprecated("使用 geminiRetrofit 替代")
    val retrofit: Retrofit = geminiRetrofit
    
    // API 服务实例
    val geminiApiService: GeminiApiService = geminiRetrofit.create(GeminiApiService::class.java)
    val siliconFlowApiService: SiliconFlowApiService = siliconFlowRetrofit.create(SiliconFlowApiService::class.java)
} 