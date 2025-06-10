# ===============================================
# ReadAssist Play Store Release ProGuard 配置
# 版本: v1.5 - 针对Play Store优化
# ===============================================

# 基本混淆设置 - 为Play Store启用
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# 启用优化和混淆（Play Store要求）
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
-allowaccessmodification

# ===============================================
# 保留关键属性
# ===============================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ===============================================
# Android 核心组件保护
# ===============================================
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService

# 保护所有View构造函数（用于布局）
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ===============================================
# ReadAssist 核心类保护
# ===============================================
# 保护所有服务类（辅助功能、悬浮窗等）
-keep class com.readassist.service.** { *; }
-keep class com.readassist.service.managers.** { *; }

# 保护UI类
-keep class com.readassist.ui.** { *; }

# 保护数据模型类（用于JSON序列化）
-keep class com.readassist.model.** { *; }
-keep class com.readassist.database.** { *; }

# 保护网络相关类
-keep class com.readassist.network.** { *; }
-keep class com.readassist.repository.** { *; }

# 保护工具类
-keep class com.readassist.utils.** { *; }

# 保护ViewModel
-keep class com.readassist.viewmodel.** { *; }

# ===============================================
# 第三方库保护
# ===============================================
# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Room Database
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# ===============================================
# 保护枚举类
# ===============================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ===============================================
# 保护Serializable类
# ===============================================
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ===============================================
# 保护Parcelable
# ===============================================
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ===============================================
# 移除日志（生产版本）
# ===============================================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# ===============================================
# 忽略常见警告
# ===============================================
-dontwarn java.lang.invoke.*
-dontwarn **$$serializer
-dontwarn java.lang.ClassValue
-dontwarn org.jetbrains.annotations.** 