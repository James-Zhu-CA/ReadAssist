# Supernote设备截屏目录兼容性修改

## 📋 问题描述

根据日志分析，Supernote设备的截屏文件保存在应用的私有目录中：
```
/storage/emulated/0/Android/data/com.readassist/files/screenshot_1749509731329.png
```

而原有代码只支持以下目录：
- `/storage/emulated/0/Pictures/Screenshots` (标准Android截屏目录)
- `/storage/emulated/0/DCIM/Screenshots` (部分设备的截屏目录)
- `/storage/emulated/0/iReader/saveImage/tmp` (掌阅设备专用目录)

## 🔧 修改内容

### 1. ScreenshotManager.kt 修改

**文件**: `app/src/main/java/com/readassist/service/managers/ScreenshotManager.kt`

**修改点**: `startMonitoring()` 方法中的目录监控逻辑

**修改前**:
```kotlin
val dirToWatch = if (DeviceUtils.getDeviceType() == DeviceType.IREADER) {
    File("/storage/emulated/0/iReader/saveImage/tmp")
} else {
    // 其他设备回退到标准的系统截图目录
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
}
```

**修改后**:
```kotlin
val dirToWatch = when (DeviceUtils.getDeviceType()) {
    DeviceType.IREADER -> {
        File("/storage/emulated/0/iReader/saveImage/tmp")
    }
    DeviceType.SUPERNOTE -> {
        // Supernote设备截屏保存在应用私有目录
        File(context.getExternalFilesDir(null), "")
    }
    else -> {
        // 其他设备回退到标准的系统截图目录
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
    }
}
```

### 2. FloatingWindowServiceNew.kt 修改

**文件**: `app/src/main/java/com/readassist/service/FloatingWindowServiceNew.kt`

#### 2.1 新增截屏目录获取方法

```kotlin
/**
 * 获取所有可能的截屏目录
 */
private fun getScreenshotDirectories(): List<String> {
    val dirs = mutableListOf(
        "/storage/emulated/0/Pictures/Screenshots",
        "/storage/emulated/0/DCIM/Screenshots",
        "/storage/emulated/0/iReader/saveImage/tmp"
    )
    
    // 添加Supernote设备的应用私有目录
    getExternalFilesDir(null)?.let { appDir ->
        dirs.add(appDir.absolutePath)
        Log.d(TAG, "🔴 添加Supernote截屏目录: ${appDir.absolutePath}")
    }
    
    return dirs
}
```

#### 2.2 更新截屏文件检测逻辑

**修改点1**: `getLatestScreenshotTimeString()` 方法
- 使用新的 `getScreenshotDirectories()` 方法
- 增加文件名过滤，只检测包含"screenshot"或"Screenshot"的文件
- 添加详细的日志记录

**修改点2**: `getRecentScreenshot()` 方法
- 使用统一的目录获取方法
- 增强文件名过滤逻辑

### 3. 日志提取脚本更新

#### 3.1 quick_extract_logs.sh
添加了Supernote应用私有目录的检查：
```bash
echo "Supernote应用私有目录:"
adb shell ls -la /storage/emulated/0/Android/data/com.readassist/files/ 2>/dev/null | tail -5
```

#### 3.2 quick_logcat_commands.md
添加了新的目录检查命令：
```bash
adb shell ls -la /storage/emulated/0/Android/data/com.readassist/files/
```

### 4. 新增测试脚本

**文件**: `test_supernote_screenshot.sh`

这是一个专门用于测试Supernote设备截屏目录兼容性的脚本，包含：
- 设备信息检查
- 所有截屏目录的存在性验证
- 截屏文件数量统计
- 最近截屏文件搜索
- 相关日志提取

## 🎯 预期效果

修改完成后，ReadAssist应用将能够：

1. **自动检测Supernote设备的截屏文件**
   - 监控应用私有目录 `/storage/emulated/0/Android/data/com.readassist/files/`
   - 识别以"screenshot_"开头的PNG文件

2. **正确更新悬浮窗口中的"发送截屏"选项**
   - 当检测到新的截屏文件时，自动勾选"发送截屏"选项
   - 显示最近截屏的时间信息

3. **兼容多种设备类型**
   - Supernote设备：应用私有目录
   - 掌阅设备：iReader专用目录
   - 其他Android设备：标准截屏目录

## 🧪 测试方法

1. **运行测试脚本**:
   ```bash
   ./test_supernote_screenshot.sh
   ```

2. **手动测试步骤**:
   - 在Supernote设备上进行截屏
   - 观察应用日志中是否出现截屏检测信息
   - 检查悬浮窗口中"发送截屏"选项是否自动勾选
   - 验证截屏文件是否能正确发送给AI

3. **日志监控**:
   ```bash
   adb logcat | grep -i "screenshot\|截屏\|ScreenshotManager"
   ```

## 📝 注意事项

1. **权限要求**: 应用需要有访问外部存储的权限才能读取截屏文件
2. **文件格式**: 目前支持PNG和JPG格式的截屏文件
3. **文件命名**: 优先检测文件名包含"screenshot"或"Screenshot"的文件
4. **性能考虑**: 文件监控只检查24小时内的文件，避免性能问题

## 🔄 后续优化建议

1. 可以考虑添加更多设备类型的特殊目录支持
2. 优化文件检测算法，减少不必要的文件系统访问
3. 添加用户自定义截屏目录的功能
4. 增强错误处理和用户反馈机制 