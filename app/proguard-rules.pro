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
-keepattributes SourceFile,LineNumberTable

# 保留 Room 数据库相关
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep class androidx.room.** { *; }

# 保留 Retrofit 相关
-keepattributes Signature
-keepclassmembernames,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# 保留 Gson 相关
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn com.google.gson.**

# 保留 OkHttp 相关
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 保留数据类
-keep class com.readassist.database.** { *; }
-keep class com.readassist.network.** { *; }

# 保留 Kotlin 协程
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 保留应用特定类
-keep class com.readassist.ReadAssistApplication { *; }
-keep class com.readassist.service.** { *; }
-keep class com.readassist.ui.** { *; }

# 保留 Android 组件
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# 保留 Accessibility Service
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

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

# 忽略警告
-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.** 