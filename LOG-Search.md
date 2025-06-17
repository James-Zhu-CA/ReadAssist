我来为您设计一个logcat命令来提取设备上的日志。基于这个ReadAssist项目的特点，我会提供几个不同场景的logcat命令。
现在让我为脚本添加执行权限并创建一个简化版本的快速命令：
```bash
chmod +x extract_device_logs.sh
```
现在让我创建一个简化的一键命令脚本：
```bash
chmod +x quick_extract_logs.sh
```
## 📱 ReadAssist 设备日志提取方案

我为您设计了完整的logcat命令方案，包括交互式脚本和快速命令参考：

### 🚀 快速使用

**1. 一键提取所有重要日志：**
```bash
./quick_extract_logs.sh
```

**2. 交互式日志提取工具：**
```bash
./extract_device_logs.sh
```

### 🔧 核心logcat命令

#### 最重要的ReadAssist应用日志：
```bash
adb logcat -d -v time 'TextAccessibilityService:*' 'ScreenshotManager:*' 'FloatingWindowServiceNew:*' 'ChatWindowManager:*' 'SessionManager:*' 'AiCommunicationManager:*' '*:S'
```

#### 实时监控ReadAssist应用：
```bash
adb logcat -v time TextAccessibilityService:* ScreenshotManager:* FloatingWindowServiceNew:* ChatWindowManager:* SessionManager:* AiCommunicationManager:* *:S
```

#### 截屏相关日志：
```bash
adb logcat -d -v time | grep -i "screenshot\|截屏\|截图"
```

#### Supernote设备特定日志：
```bash
adb logcat -d -v time | grep -i "supernote\|ratta"
```

### 📁 创建的文件

1. **`extract_device_logs.sh`** - 完整的交互式日志提取工具
   - 9种不同的日志提取选项
   - 实时监控模式
   - 自动设备检测和状态检查

2. **`quick_extract_logs.sh`** - 一键快速提取脚本
   - 提取最重要的6类日志
   - 自动生成带时间戳的日志目录
   - 显示日志统计和错误检查

3. **`quick_logcat_commands.md`** - 详细的命令参考文档
   - 基础到高级的logcat命令
   - 特定场景的调试命令
   - 故障排除和使用技巧

### 💡 使用建议

**日常开发调试：**
```bash
# 实时监控应用日志
adb logcat -v time TextAccessibilityService:* ScreenshotManager:* *:S
```

**问题排查：**
```bash
# 一键提取所有相关日志
./quick_extract_logs.sh
```

**深度分析：**
```bash
# 使用交互式工具选择特定类型
./extract_device_logs.sh
```

**检查设备状态：**
```bash
# 检查设备信息
adb shell getprop ro.product.model
adb shell getprop ro.product.manufacturer

# 检查应用状态
adb shell pidof com.readassist
```

### 🎯 特定场景命令

**调试截屏功能：**
```bash
adb logcat -c  # 清除缓冲区
adb logcat -v time ScreenshotManager:* TextAccessibilityService:* | grep -i "screenshot"
```

**调试文本选择：**
```bash
adb logcat -v time TextAccessibilityService:* | grep -i "select\|选中"
```

**监控错误：**
```bash
adb logcat -v time *:E | grep -i "com.readassist"
```

这套方案涵盖了从简单的一键提取到复杂的交互式分析的所有需求，特别针对ReadAssist应用和Supernote设备进行了优化。