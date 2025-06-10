#!/bin/bash

# ReadAssist 设备日志提取脚本
# 用于从Android设备提取应用相关的日志信息

echo "🔍 ReadAssist 设备日志提取工具"
echo "================================"

# 检查ADB连接
if ! command -v adb &> /dev/null; then
    echo "❌ 错误: 未找到adb命令，请确保Android SDK已安装并配置PATH"
    exit 1
fi

# 检查设备连接
DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep -c "device")
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ 错误: 未检测到连接的Android设备"
    echo "请确保："
    echo "1. 设备已连接并开启USB调试"
    echo "2. 已授权调试权限"
    exit 1
fi

echo "✅ 检测到 $DEVICE_COUNT 个设备"

# 创建日志输出目录
LOG_DIR="device_logs_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"
echo "📁 日志将保存到: $LOG_DIR"

# 获取设备信息
echo "📱 获取设备信息..."
adb shell getprop ro.product.model > "$LOG_DIR/device_info.txt"
adb shell getprop ro.product.manufacturer >> "$LOG_DIR/device_info.txt"
adb shell getprop ro.build.version.release >> "$LOG_DIR/device_info.txt"
adb shell getprop ro.product.brand >> "$LOG_DIR/device_info.txt"

echo "设备型号: $(adb shell getprop ro.product.model)"
echo "制造商: $(adb shell getprop ro.product.manufacturer)"
echo "品牌: $(adb shell getprop ro.product.brand)"

# 函数：提取ReadAssist应用日志
extract_readassist_logs() {
    echo "🔍 提取ReadAssist应用日志..."
    
    # 清除旧日志缓冲区
    adb logcat -c
    
    # 实时监听ReadAssist相关日志 (30秒)
    timeout 30s adb logcat \
        -v time \
        --pid=$(adb shell pidof com.readassist) \
        TextAccessibilityService:* \
        ScreenshotManager:* \
        FloatingWindowServiceNew:* \
        ChatWindowManager:* \
        SessionManager:* \
        AiCommunicationManager:* \
        *:S \
        > "$LOG_DIR/readassist_realtime.log" 2>&1 &
    
    # 获取历史日志
    adb logcat -d \
        -v time \
        TextAccessibilityService:* \
        ScreenshotManager:* \
        FloatingWindowServiceNew:* \
        ChatWindowManager:* \
        SessionManager:* \
        AiCommunicationManager:* \
        *:S \
        > "$LOG_DIR/readassist_history.log"
}

# 函数：提取截屏相关日志
extract_screenshot_logs() {
    echo "📸 提取截屏相关日志..."
    
    adb logcat -d \
        -v time \
        | grep -i "screenshot\|截屏\|截图" \
        > "$LOG_DIR/screenshot_logs.log"
}

# 函数：提取文本选择相关日志
extract_text_selection_logs() {
    echo "📝 提取文本选择相关日志..."
    
    adb logcat -d \
        -v time \
        | grep -i "text.*select\|选中\|剪贴板\|clipboard" \
        > "$LOG_DIR/text_selection_logs.log"
}

# 函数：提取Supernote设备特定日志
extract_supernote_logs() {
    echo "🔴 提取Supernote设备特定日志..."
    
    adb logcat -d \
        -v time \
        | grep -i "supernote\|ratta" \
        > "$LOG_DIR/supernote_logs.log"
}

# 函数：提取系统级日志
extract_system_logs() {
    echo "⚙️ 提取系统级日志..."
    
    # 获取最近1000行系统日志
    adb logcat -d -t 1000 \
        -v time \
        ActivityManager:* \
        WindowManager:* \
        AccessibilityService:* \
        MediaProjectionManagerService:* \
        *:W \
        > "$LOG_DIR/system_logs.log"
}

# 函数：提取错误和崩溃日志
extract_error_logs() {
    echo "❌ 提取错误和崩溃日志..."
    
    # 获取错误级别日志
    adb logcat -d \
        -v time \
        *:E \
        > "$LOG_DIR/error_logs.log"
    
    # 获取崩溃日志
    adb logcat -d \
        -v time \
        | grep -i "crash\|exception\|error\|fatal" \
        > "$LOG_DIR/crash_logs.log"
}

# 函数：提取完整日志（用于深度分析）
extract_full_logs() {
    echo "📋 提取完整日志（最近2000行）..."
    
    adb logcat -d -t 2000 \
        -v time \
        > "$LOG_DIR/full_logs.log"
}

# 主菜单
show_menu() {
    echo ""
    echo "请选择要提取的日志类型："
    echo "1) ReadAssist应用日志 (推荐)"
    echo "2) 截屏相关日志"
    echo "3) 文本选择相关日志"
    echo "4) Supernote设备特定日志"
    echo "5) 系统级日志"
    echo "6) 错误和崩溃日志"
    echo "7) 完整日志 (最近2000行)"
    echo "8) 全部提取"
    echo "9) 实时监控模式"
    echo "0) 退出"
    echo ""
    read -p "请输入选项 (0-9): " choice
}

# 实时监控模式
realtime_monitor() {
    echo "🔄 启动实时监控模式..."
    echo "按 Ctrl+C 停止监控"
    
    # 清除日志缓冲区
    adb logcat -c
    
    # 实时显示ReadAssist相关日志
    adb logcat \
        -v time \
        TextAccessibilityService:* \
        ScreenshotManager:* \
        FloatingWindowServiceNew:* \
        ChatWindowManager:* \
        SessionManager:* \
        AiCommunicationManager:* \
        *:S \
        | tee "$LOG_DIR/realtime_monitor.log"
}

# 主循环
while true; do
    show_menu
    
    case $choice in
        1)
            extract_readassist_logs
            echo "✅ ReadAssist应用日志提取完成"
            ;;
        2)
            extract_screenshot_logs
            echo "✅ 截屏相关日志提取完成"
            ;;
        3)
            extract_text_selection_logs
            echo "✅ 文本选择相关日志提取完成"
            ;;
        4)
            extract_supernote_logs
            echo "✅ Supernote设备特定日志提取完成"
            ;;
        5)
            extract_system_logs
            echo "✅ 系统级日志提取完成"
            ;;
        6)
            extract_error_logs
            echo "✅ 错误和崩溃日志提取完成"
            ;;
        7)
            extract_full_logs
            echo "✅ 完整日志提取完成"
            ;;
        8)
            echo "🔄 提取所有类型日志..."
            extract_readassist_logs
            extract_screenshot_logs
            extract_text_selection_logs
            extract_supernote_logs
            extract_system_logs
            extract_error_logs
            extract_full_logs
            echo "✅ 所有日志提取完成"
            ;;
        9)
            realtime_monitor
            ;;
        0)
            echo "👋 退出日志提取工具"
            break
            ;;
        *)
            echo "❌ 无效选项，请重新选择"
            ;;
    esac
    
    if [ "$choice" != "9" ] && [ "$choice" != "0" ]; then
        echo ""
        read -p "按回车键继续..."
    fi
done

# 显示结果
if [ -d "$LOG_DIR" ]; then
    echo ""
    echo "📊 日志提取完成！"
    echo "日志文件保存在: $LOG_DIR"
    echo ""
    echo "文件列表:"
    ls -la "$LOG_DIR"
    echo ""
    echo "💡 使用建议:"
    echo "1. 查看 readassist_*.log 了解应用运行状态"
    echo "2. 查看 screenshot_logs.log 排查截屏问题"
    echo "3. 查看 error_logs.log 查找错误信息"
    echo "4. 查看 supernote_logs.log 了解设备特定问题"
fi 