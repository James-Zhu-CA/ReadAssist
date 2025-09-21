# ReadAssist 剪贴板监听修复方案

## 🔍 问题诊断结果

### 根本原因
**Android 11 (API 30) 的剪贴板访问限制**

从详细诊断日志发现：
```
ClipboardService: Denying clipboard access to com.readassist, application is not in focus nor is it a system service for user 0
```

Android 10+ 引入了严格的剪贴板访问限制：
- 只有前台应用或系统服务才能访问剪贴板
- 后台应用无法访问剪贴板内容
- 即使是无障碍服务也受到这个限制

## 🔧 解决方案

### 方案1：修改剪贴板监听实现（推荐）

需要在 `TextAccessibilityService.kt` 中修改剪贴板监听逻辑：

```kotlin
private fun setupClipboardListener() {
    clipboardManager.addPrimaryClipChangedListener {
        mainHandler.post {
            // 添加前台应用检测
            if (isAppInForeground()) {
                handleClipboardChange()
            } else {
                Log.d(TAG, "🚫 应用不在前台，跳过剪贴板处理")
            }
        }
    }
}

private fun isAppInForeground(): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val runningTasks = activityManager.getRunningTasks(1)
    return if (runningTasks.isNotEmpty()) {
        val topTask = runningTasks[0]
        val topActivity = topTask.topActivity
        topActivity?.packageName != "com.readassist"
    } else {
        false
    }
}
```

### 方案2：使用前台服务权限

在 `AndroidManifest.xml` 中添加：
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_ACCESSIBILITY" />
```

### 方案3：延迟剪贴板访问

```kotlin
private fun handleClipboardChange() {
    // 延迟访问剪贴板，等待系统权限检查
    mainHandler.postDelayed({
        try {
            val clipData = clipboardManager.primaryClip
            // 处理剪贴板内容...
        } catch (e: SecurityException) {
            Log.e(TAG, "剪贴板访问被拒绝: ${e.message}")
        }
    }, 100)
}
```

## 🚀 立即修复步骤

### 1. 代码修改
需要修改 `TextAccessibilityService.kt` 中的 `setupClipboardListener()` 方法

### 2. 测试验证
1. 重新编译安装应用
2. 在支持的应用中复制文本
3. 观察是否自动弹出聊天窗口

### 3. 备选方案
如果自动监听仍然不工作：
- 使用手动方式：勾选"发送剪贴板内容"
- 使用截图功能：勾选"发送截屏图片"

## 📋 兼容性说明

- Android 10+ 都有此限制
- 需要特殊处理才能实现后台剪贴板监听
- 建议优先使用前台应用检测方案
