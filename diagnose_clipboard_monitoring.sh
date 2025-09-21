#!/bin/bash

echo "=== ReadAssist 剪贴板监听诊断脚本 ==="
echo "开始时间: $(date)"
echo

# 创建日志目录
LOG_DIR="logs_clipboard_diagnosis_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"

echo "1. 检查无障碍服务状态..."
adb shell settings get secure enabled_accessibility_services > "$LOG_DIR/accessibility_services.txt"
echo "无障碍服务列表已保存到: $LOG_DIR/accessibility_services.txt"

echo "2. 检查ReadAssist服务是否运行..."
adb shell dumpsys activity services | grep -i readassist > "$LOG_DIR/running_services.txt"
echo "运行服务列表已保存到: $LOG_DIR/running_services.txt"

echo "3. 提取TextAccessibilityService相关日志..."
adb logcat -d | grep -E "(TextAccessibilityService|clipboard|Clipboard)" > "$LOG_DIR/accessibility_service_logs.txt"
echo "无障碍服务日志已保存到: $LOG_DIR/accessibility_service_logs.txt"

echo "4. 提取剪贴板变化相关日志..."
adb logcat -d | grep -E "(📋|剪贴板|clipboard|ClipboardManager)" > "$LOG_DIR/clipboard_logs.txt"
echo "剪贴板日志已保存到: $LOG_DIR/clipboard_logs.txt"

echo "5. 提取应用启动和连接日志..."
adb logcat -d | grep -E "(🚀|🔗|onCreate|onServiceConnected)" > "$LOG_DIR/service_connection_logs.txt"
echo "服务连接日志已保存到: $LOG_DIR/service_connection_logs.txt"

echo "6. 检查应用权限状态..."
adb shell dumpsys package com.readassist | grep -A 20 "requested permissions" > "$LOG_DIR/app_permissions.txt"
echo "应用权限已保存到: $LOG_DIR/app_permissions.txt"

echo "7. 提取最近的错误日志..."
adb logcat -d | grep -E "(ERROR|Exception|Failed|❌)" | tail -50 > "$LOG_DIR/recent_errors.txt"
echo "最近错误日志已保存到: $LOG_DIR/recent_errors.txt"

echo
echo "=== 诊断完成 ==="
echo "所有日志文件已保存到目录: $LOG_DIR"
echo "请检查以下关键信息："
echo "1. 检查 accessibility_services.txt 中是否包含 com.readassist"
echo "2. 检查 accessibility_service_logs.txt 中是否有服务启动和剪贴板监听日志"
echo "3. 检查 clipboard_logs.txt 中是否有剪贴板变化检测日志"
echo "4. 检查 recent_errors.txt 中是否有相关错误信息"
