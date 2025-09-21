#!/bin/bash

echo "=== ReadAssist 剪贴板实时监控 ==="
echo "开始实时监控剪贴板监听功能..."
echo "请在另一个应用中复制一些文本，然后观察日志输出"
echo "按 Ctrl+C 停止监控"
echo

# 清空之前的日志
adb logcat -c

echo "开始监控日志..."
adb logcat | grep -E "(TextAccessibilityService|📋|剪贴板|clipboard|ClipboardManager|🔔|handleClipboardChange)"
