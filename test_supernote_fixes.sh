#!/bin/bash

# Supernote设备修复验证脚本
# 用于测试修复后的截屏功能和崩溃问题

echo "🔧 Supernote设备修复验证测试"
echo "=================================="

# 检查ADB连接
if ! command -v adb &> /dev/null; then
    echo "❌ 未找到adb命令"
    exit 1
fi

if [ "$(adb devices | grep -c 'device$')" -eq 0 ]; then
    echo "❌ 未检测到设备连接"
    exit 1
fi

echo "✅ 设备连接正常"

# 创建测试日志目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TEST_DIR="supernote_fix_test_$TIMESTAMP"
mkdir -p "$TEST_DIR"

echo "📁 测试日志保存到: $TEST_DIR"

# 获取设备信息
echo ""
echo "📱 设备信息:"
{
    echo "=== 设备基本信息 ==="
    echo "型号: $(adb shell getprop ro.product.model)"
    echo "制造商: $(adb shell getprop ro.product.manufacturer)"
    echo "品牌: $(adb shell getprop ro.product.brand)"
    echo "Android版本: $(adb shell getprop ro.build.version.release)"
    echo "API级别: $(adb shell getprop ro.build.version.sdk)"
    echo ""
} > "$TEST_DIR/device_info.txt"

# 检查应用状态
echo "🔍 检查应用状态..."
{
    echo "=== ReadAssist应用状态 ==="
    echo "应用PID: $(adb shell pidof com.readassist)"
    echo "应用是否安装: $(adb shell pm list packages | grep readassist)"
    echo "应用版本: $(adb shell dumpsys package com.readassist | grep versionName | head -1)"
    echo ""
    echo "=== 辅助功能服务状态 ==="
    adb shell settings get secure enabled_accessibility_services | grep readassist
    echo ""
} > "$TEST_DIR/app_status.txt"

# 清除旧日志
echo "🧹 清除旧日志..."
adb logcat -c

# 启动实时日志监控（后台运行）
echo "📊 启动实时日志监控..."
adb logcat -v time \
    TextAccessibilityService:* \
    ScreenshotManager:* \
    FloatingWindowServiceNew:* \
    ChatRepository:* \
    AndroidRuntime:E \
    *:S \
    > "$TEST_DIR/realtime_logs.log" 2>&1 &

LOGCAT_PID=$!

# 等待用户操作
echo ""
echo "🎯 测试指导:"
echo "1. 点击悬浮按钮进行截屏"
echo "2. 观察是否出现'截屏失败'提示"
echo "3. 如果截屏成功，尝试点击'发送图片'按钮"
echo "4. 观察应用是否崩溃重启"
echo "5. 完成测试后按任意键继续..."
echo ""

read -p "按任意键开始监控（将监控30秒）..." -n1 -s
echo ""

echo "⏱️  开始30秒测试监控..."
sleep 30

# 停止日志监控
echo "⏹️  停止日志监控..."
kill $LOGCAT_PID 2>/dev/null

# 获取最终日志快照
echo "📸 获取最终日志快照..."
adb logcat -d -v time \
    TextAccessibilityService:* \
    ScreenshotManager:* \
    FloatingWindowServiceNew:* \
    ChatRepository:* \
    AndroidRuntime:E \
    *:S \
    > "$TEST_DIR/final_logs.log"

# 检查关键错误
echo "🔍 分析测试结果..."

# 检查是否有List.reversed()错误
REVERSED_ERROR_COUNT=$(grep -c "NoSuchMethodError.*reversed" "$TEST_DIR/final_logs.log" 2>/dev/null || echo "0")
if [ "$REVERSED_ERROR_COUNT" -gt 0 ]; then
    echo "❌ 发现 $REVERSED_ERROR_COUNT 个List.reversed()错误 - 修复未生效"
else
    echo "✅ 未发现List.reversed()错误 - 兼容性修复生效"
fi

# 检查截屏解码失败
DECODE_ERROR_COUNT=$(grep -c "无法解码截屏图片" "$TEST_DIR/final_logs.log" 2>/dev/null || echo "0")
if [ "$DECODE_ERROR_COUNT" -gt 0 ]; then
    echo "⚠️  发现 $DECODE_ERROR_COUNT 个截屏解码失败"
    echo "   - 检查是否有Supernote特殊处理日志"
    SUPERNOTE_HANDLING_COUNT=$(grep -c "Supernote设备.*重试" "$TEST_DIR/final_logs.log" 2>/dev/null || echo "0")
    if [ "$SUPERNOTE_HANDLING_COUNT" -gt 0 ]; then
        echo "   ✅ 检测到Supernote特殊处理逻辑（$SUPERNOTE_HANDLING_COUNT 次）"
    else
        echo "   ❌ 未检测到Supernote特殊处理逻辑"
    fi
else
    echo "✅ 未发现截屏解码失败"
fi

# 检查应用崩溃
CRASH_COUNT=$(grep -c "FATAL EXCEPTION\|AndroidRuntime.*FATAL" "$TEST_DIR/final_logs.log" 2>/dev/null || echo "0")
if [ "$CRASH_COUNT" -gt 0 ]; then
    echo "❌ 发现 $CRASH_COUNT 个应用崩溃"
    echo "   - 详细信息请查看 final_logs.log"
else
    echo "✅ 未发现应用崩溃"
fi

# 检查改进的错误处理
IMPROVED_ERROR_COUNT=$(grep -c "截屏解码失败，这可能是设备兼容性问题" "$TEST_DIR/final_logs.log" 2>/dev/null || echo "0")
if [ "$IMPROVED_ERROR_COUNT" -gt 0 ]; then
    echo "✅ 检测到改进的错误处理消息（$IMPROVED_ERROR_COUNT 次）"
else
    echo "ℹ️  未检测到改进的错误处理消息（可能没有触发相关错误）"
fi

# 生成测试报告
echo ""
echo "📋 生成测试报告..."
{
    echo "Supernote设备修复验证报告"
    echo "=========================="
    echo "测试时间: $(date)"
    echo "设备型号: $(adb shell getprop ro.product.model)"
    echo ""
    echo "修复验证结果:"
    echo "- List.reversed()兼容性错误: $REVERSED_ERROR_COUNT"
    echo "- 截屏解码失败: $DECODE_ERROR_COUNT"
    echo "- Supernote特殊处理: $SUPERNOTE_HANDLING_COUNT"
    echo "- 应用崩溃: $CRASH_COUNT"
    echo "- 改进错误处理: $IMPROVED_ERROR_COUNT"
    echo ""
    if [ "$REVERSED_ERROR_COUNT" -eq 0 ] && [ "$CRASH_COUNT" -eq 0 ]; then
        echo "总体评估: ✅ 修复成功"
        echo "应用稳定性显著改善，未发现兼容性问题和崩溃。"
    elif [ "$REVERSED_ERROR_COUNT" -eq 0 ] && [ "$CRASH_COUNT" -gt 0 ]; then
        echo "总体评估: ⚠️  部分修复"
        echo "兼容性问题已修复，但仍有其他崩溃问题需要进一步调查。"
    else
        echo "总体评估: ❌ 修复不完整"
        echo "仍存在兼容性或崩溃问题，需要进一步修复。"
    fi
} > "$TEST_DIR/test_report.txt"

# 显示结果
echo ""
echo "✅ 测试完成！"
echo "📁 测试结果保存在: $TEST_DIR"
echo ""
echo "📊 文件列表:"
ls -la "$TEST_DIR"
echo ""
echo "📋 测试报告:"
cat "$TEST_DIR/test_report.txt"

echo ""
echo "💡 后续建议:"
echo "1. 如果仍有问题，请查看详细日志文件"
echo "2. 对于持续的截屏问题，可能需要设备特定的调优"
echo "3. 建议在不同使用场景下重复测试"
