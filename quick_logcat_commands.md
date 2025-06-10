# ReadAssist 设备日志提取命令参考

## 🚀 快速命令

### 1. 基础ReadAssist应用日志
```bash
# 提取ReadAssist应用的所有日志
adb logcat -d -v time TextAccessibilityService:* ScreenshotManager:* FloatingWindowServiceNew:* ChatWindowManager:* SessionManager:* AiCommunicationManager:* *:S

# 实时监控ReadAssist应用日志
adb logcat -v time TextAccessibilityService:* ScreenshotManager:* FloatingWindowServiceNew:* ChatWindowManager:* SessionManager:* AiCommunicationManager:* *:S
```

### 2. 截屏相关日志
```bash
# 提取所有截屏相关日志
adb logcat -d -v time | grep -i "screenshot\|截屏\|截图"

# 只看截屏管理器日志
adb logcat -d -v time ScreenshotManager:* *:S

# 实时监控截屏事件
adb logcat -v time | grep -i "screenshot\|截屏"
```

### 3. Supernote设备特定日志
```bash
# 提取Supernote设备相关日志
adb logcat -d -v time | grep -i "supernote\|ratta"

# 监控Supernote应用事件
adb logcat -v time | grep -i "supernote\|ratta\|com.supernote\|com.ratta"
```

### 4. 文本选择相关日志
```bash
# 提取文本选择和剪贴板日志
adb logcat -d -v time | grep -i "text.*select\|选中\|剪贴板\|clipboard"

# 监控辅助功能服务
adb logcat -v time TextAccessibilityService:* *:S
```

### 5. 错误和崩溃日志
```bash
# 提取所有错误级别日志
adb logcat -d -v time *:E

# 提取崩溃相关日志
adb logcat -d -v time | grep -i "crash\|exception\|error\|fatal"

# 监控应用崩溃
adb logcat -v time *:E | grep -i "com.readassist"
```

### 6. 系统级日志
```bash
# 提取系统服务日志
adb logcat -d -v time ActivityManager:* WindowManager:* AccessibilityService:* MediaProjectionManagerService:* *:W

# 监控权限相关日志
adb logcat -v time | grep -i "permission\|权限"
```

## 🔧 高级命令

### 按进程ID过滤
```bash
# 获取ReadAssist进程ID
PID=$(adb shell pidof com.readassist)
echo "ReadAssist PID: $PID"

# 按进程ID过滤日志
adb logcat -d -v time --pid=$PID
```

### 按时间范围过滤
```bash
# 获取最近100行日志
adb logcat -d -t 100 -v time

# 获取最近1小时的日志
adb logcat -d -T "$(date -d '1 hour ago' '+%m-%d %H:%M:%S.000')" -v time
```

### 保存到文件
```bash
# 保存ReadAssist日志到文件
adb logcat -d -v time TextAccessibilityService:* ScreenshotManager:* FloatingWindowServiceNew:* *:S > readassist_logs.txt

# 实时保存日志到文件
adb logcat -v time TextAccessibilityService:* ScreenshotManager:* *:S | tee readassist_realtime.log
```

## 📱 设备信息命令

### 获取设备基本信息
```bash
# 设备型号和制造商
adb shell getprop ro.product.model
adb shell getprop ro.product.manufacturer
adb shell getprop ro.product.brand

# Android版本
adb shell getprop ro.build.version.release

# 检查是否为Supernote设备
adb shell getprop | grep -i "ratta\|supernote"
```

### 检查应用状态
```bash
# 检查ReadAssist是否运行
adb shell pidof com.readassist

# 检查应用权限
adb shell dumpsys package com.readassist | grep -i permission

# 检查辅助功能服务状态
adb shell settings get secure enabled_accessibility_services | grep readassist
```

## 🎯 特定场景命令

### 调试截屏功能
```bash
# 清除日志缓冲区
adb logcat -c

# 监控截屏相关事件
adb logcat -v time ScreenshotManager:* TextAccessibilityService:* | grep -i "screenshot\|截屏"

# 检查截屏文件目录
adb shell ls -la /storage/emulated/0/Pictures/Screenshots/
adb shell ls -la /storage/emulated/0/iReader/saveImage/tmp/
adb shell ls -la /storage/emulated/0/Android/data/com.readassist/files/
```

### 调试文本选择功能
```bash
# 监控文本选择事件
adb logcat -v time TextAccessibilityService:* | grep -i "select\|选中\|clipboard"

# 检查剪贴板内容
adb shell service call clipboard 2 s16 com.readassist
```

### 调试AI通信功能
```bash
# 监控AI通信日志
adb logcat -v time AiCommunicationManager:* ChatWindowManager:* | grep -i "api\|response\|error"
```

## 💡 使用技巧

### 1. 组合过滤
```bash
# 同时监控截屏和文本选择
adb logcat -v time | grep -E "(screenshot|截屏|select|选中)"

# 排除调试信息，只看重要日志
adb logcat -v time *:I | grep -v "DEBUG"
```

### 2. 彩色输出
```bash
# 使用颜色高亮关键词
adb logcat -v time | grep --color=always -E "(ERROR|WARN|screenshot|截屏)"
```

### 3. 分割日志文件
```bash
# 按大小分割日志文件 (每10MB一个文件)
adb logcat -v time | split -b 10M - readassist_log_
```

## 🔍 故障排除命令

### 检查ADB连接
```bash
# 列出连接的设备
adb devices

# 重启ADB服务
adb kill-server && adb start-server

# 检查设备连接状态
adb get-state
```

### 检查应用安装
```bash
# 检查ReadAssist是否已安装
adb shell pm list packages | grep readassist

# 检查应用版本
adb shell dumpsys package com.readassist | grep versionName
```

### 清理和重置
```bash
# 清除应用数据
adb shell pm clear com.readassist

# 重启应用
adb shell am force-stop com.readassist
adb shell am start -n com.readassist/.MainActivity
```

---

## 📋 使用建议

1. **开发调试**: 使用实时监控命令 (`adb logcat -v time`)
2. **问题排查**: 先提取历史日志 (`adb logcat -d`)，再实时监控
3. **性能分析**: 结合系统日志和应用日志
4. **用户反馈**: 提取完整日志并保存到文件
5. **设备适配**: 重点关注设备特定的日志信息 