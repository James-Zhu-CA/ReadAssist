# ===============================================
# ReadAssist 最保守 ProGuard 配置
# 版本: v1.2 - 彻底解决反射问题
# ===============================================

# 基本配置（关闭激进优化）
-dontoptimize
-dontobfuscate
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# ===============================================
# 保留所有关键属性
# ===============================================
-keepattributes *

# ===============================================
# 全面保护 Gson 和反射
# ===============================================
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.** { *; }
-keep class * extends com.google.gson.** { *; }

# 保留所有反射相关类
-keep class java.lang.reflect.** { *; }
-keep class sun.misc.** { *; }

# 保留所有类型相关的类
-keep class java.lang.reflect.Type { *; }
-keep class java.lang.reflect.ParameterizedType { *; }
-keep class java.lang.reflect.GenericArrayType { *; }
-keep class java.lang.reflect.WildcardType { *; }
-keep class java.lang.reflect.TypeVariable { *; }

# ===============================================
# 保护项目所有类
# ===============================================
-keep class com.readassist.** { *; }

# ===============================================
# 保护第三方库
# ===============================================
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class androidx.** { *; }
-keep class kotlinx.** { *; }

# ===============================================
# 保护 Android 组件
# ===============================================
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# ===============================================
# 忽略警告
# ===============================================
-dontwarn ** 