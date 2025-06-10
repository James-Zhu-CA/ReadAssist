#!/bin/bash

# ReadAssist 快速日志提取脚本
# 一键提取最重要的日志信息

echo "🔍 ReadAssist 快速日志提取"
echo "=========================="

# 检查ADB
if ! command -v adb &> /dev/null; then
    echo "❌ 未找到adb命令"
    exit 1
fi

# 检查设备连接
if [ "$(adb devices | grep -c 'device$')" -eq 0 ]; then
    echo "❌ 未检测到设备连接"
    exit 1
fi

# 创建日志目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_DIR="logs_$TIMESTAMP"
mkdir -p "$LOG_DIR"

echo "📁 日志保存到: $LOG_DIR"

# 获取设备信息
echo "📱 获取设备信息..."
{
    echo "=== 设备信息 ==="
    echo "型号: $(adb shell getprop ro.product.model)"
    echo "制造商: $(adb shell getprop ro.product.manufacturer)"
    echo "品牌: $(adb shell getprop ro.product.brand)"
    echo "Android版本: $(adb shell getprop ro.build.version.release)"
    echo ""
} > "$LOG_DIR/device_info.txt"

# 1. ReadAssist应用日志 (最重要)
echo "🔍 提取ReadAssist应用日志..."
adb logcat -d -v time \
    TextAccessibilityService:* \
    ScreenshotManager:* \
    FloatingWindowServiceNew:* \
    ChatWindowManager:* \
    SessionManager:* \
    AiCommunicationManager:* \
    *:S \
    > "$LOG_DIR/readassist_app.log"

# 2. 截屏相关日志
echo "📸 提取截屏相关日志..."
adb logcat -d -v time | grep -i "screenshot\|截屏\|截图" > "$LOG_DIR/screenshot.log"

# 3. Supernote设备特定日志
echo "🔴 提取Supernote设备日志..."
adb logcat -d -v time | grep -i "supernote\|ratta" > "$LOG_DIR/supernote.log"

# 4. 错误日志
echo "❌ 提取错误日志..."
adb logcat -d -v time *:E > "$LOG_DIR/errors.log"

# 5. 文本选择日志
echo "📝 提取文本选择日志..."
adb logcat -d -v time | grep -i "text.*select\|选中\|剪贴板\|clipboard" > "$LOG_DIR/text_selection.log"

# 6. 系统相关日志
echo "⚙️ 提取系统日志..."
adb logcat -d -v time -t 500 \
    ActivityManager:* \
    WindowManager:* \
    AccessibilityService:* \
    *:W \
    > "$LOG_DIR/system.log"

# 检查应用状态
echo "🔍 检查应用状态..."
{
    echo "=== 应用状态 ==="
    echo "ReadAssist PID: $(adb shell pidof com.readassist)"
    echo "应用是否安装: $(adb shell pm list packages | grep readassist)"
    echo ""
    echo "=== 辅助功能服务状态 ==="
    adb shell settings get secure enabled_accessibility_services | grep readassist
    echo ""
    echo "=== 截屏目录检查 ==="
    echo "标准截屏目录:"
    adb shell ls -la /storage/emulated/0/Pictures/Screenshots/ 2>/dev/null | tail -5
    echo ""
    echo "iReader截屏目录:"
    adb shell ls -la /storage/emulated/0/iReader/saveImage/tmp/ 2>/dev/null | tail -5
    echo ""
    echo "Supernote应用私有目录:"
    adb shell ls -la /storage/emulated/0/Android/data/com.readassist/files/ 2>/dev/null | tail -5
    echo ""
} > "$LOG_DIR/app_status.txt"

# 显示结果
echo ""
echo "✅ 日志提取完成！"
echo "📁 日志文件保存在: $LOG_DIR"
echo ""
echo "📊 文件列表:"
ls -la "$LOG_DIR"
echo ""
echo "🔍 快速检查:"
echo "- ReadAssist应用日志: $(wc -l < "$LOG_DIR/readassist_app.log") 行"
echo "- 截屏相关日志: $(wc -l < "$LOG_DIR/screenshot.log") 行"
echo "- 错误日志: $(wc -l < "$LOG_DIR/errors.log") 行"
echo "- Supernote设备日志: $(wc -l < "$LOG_DIR/supernote.log") 行"

# 检查是否有重要错误
ERROR_COUNT=$(grep -c "FATAL\|AndroidRuntime" "$LOG_DIR/errors.log" 2>/dev/null || echo "0")
if [ "$ERROR_COUNT" -gt 0 ]; then
    echo ""
    echo "⚠️  发现 $ERROR_COUNT 个严重错误，请检查 errors.log"
fi

echo ""
echo "💡 使用建议:"
echo "1. 首先查看 readassist_app.log 了解应用运行状态"
echo "2. 如有截屏问题，查看 screenshot.log"
echo "3. 如有错误，查看 errors.log"
echo "4. Supernote设备特定问题查看 supernote.log" 