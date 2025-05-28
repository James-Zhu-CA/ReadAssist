# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# 基本配置
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# 保留注解
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 保留 Room 数据库相关
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# 保留 Retrofit 相关
-keepattributes Signature
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }

# 保留 Gson 相关
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# 保留 OkHttp 相关
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# 保留数据类
-keep class com.readassist.database.** { *; }
-keep class com.readassist.network.** { *; }

# 保留 Kotlin 协程
-keep class kotlinx.coroutines.** { *; }

# 保留应用特定类
-keep class com.readassist.ReadAssistApplication { *; }
-keep class com.readassist.service.** { *; }

# 移除日志
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# 保留原生方法
-keepclasseswithmembernames class * {
    native <methods>;
} 