#!/bin/bash

echo "=== ReadAssist 剪贴板监听详细诊断脚本 ==="
echo "开始时间: $(date)"
echo

# 创建日志目录
LOG_DIR="logs_clipboard_detailed_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$LOG_DIR"

echo "1. 检查Android版本和API级别..."
adb shell getprop ro.build.version.release > "$LOG_DIR/android_version.txt"
adb shell getprop ro.build.version.sdk > "$LOG_DIR/api_level.txt"
echo "Android版本信息已保存"

echo "2. 检查剪贴板服务状态..."
adb shell dumpsys clipboard > "$LOG_DIR/clipboard_service_dump.txt"
echo "剪贴板服务状态已保存"

echo "3. 检查ReadAssist应用的详细权限..."
adb shell dumpsys package com.readassist | grep -A 50 "User 0:" > "$LOG_DIR/app_permissions_detailed.txt"
echo "应用详细权限已保存"

echo "4. 检查无障碍服务详细配置..."
adb shell settings get secure enabled_accessibility_services > "$LOG_DIR/accessibility_detailed.txt"
adb shell dumpsys accessibility | grep -A 20 -B 5 "com.readassist" > "$LOG_DIR/accessibility_dump.txt"
echo "无障碍服务详细配置已保存"

echo "5. 提取所有ReadAssist相关日志（最近1小时）..."
adb logcat -d -t 3600 | grep -i readassist > "$LOG_DIR/readassist_all_logs.txt"
echo "ReadAssist所有日志已保存"

echo "6. 提取剪贴板相关的系统日志..."
adb logcat -d -t 3600 | grep -i clipboard > "$LOG_DIR/system_clipboard_logs.txt"
echo "系统剪贴板日志已保存"

echo "7. 检查应用进程状态..."
adb shell ps | grep readassist > "$LOG_DIR/app_processes.txt"
echo "应用进程状态已保存"

echo "8. 测试剪贴板访问权限..."
echo "测试文本" | adb shell am broadcast -a android.intent.action.CLIPBOARD_CHANGED > "$LOG_DIR/clipboard_test.txt" 2>&1
echo "剪贴板测试已保存"

echo
echo "=== 详细诊断完成 ==="
echo "所有日志文件已保存到目录: $LOG_DIR"
echo
echo "关键检查项目："
echo "1. 检查 android_version.txt 和 api_level.txt - 确认Android版本"
echo "2. 检查 clipboard_service_dump.txt - 确认剪贴板服务状态"
echo "3. 检查 accessibility_dump.txt - 确认无障碍服务详细状态"
echo "4. 检查 readassist_all_logs.txt - 查找剪贴板监听相关日志"
echo "5. 检查 system_clipboard_logs.txt - 查看系统剪贴板活动"
