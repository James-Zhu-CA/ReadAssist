#!/bin/bash

echo "🔍 监控ReadAssist应用错误..."
echo "📱 请在设备上测试ReadAssist网络功能"
echo "⏹️  按Ctrl+C停止"
echo ""

# 清空日志并开始监控
adb logcat -c

# 获取应用PID
PID=$(adb shell pidof com.readassist)
echo "📱 ReadAssist PID: $PID"

# 监控应用的所有日志，重点关注错误
adb logcat --pid=$PID -v threadtime | while read line; do
    if [[ $line == *"E/"* ]] || [[ $line == *"W/"* ]] || [[ $line == *"ERROR"* ]] || [[ $line == *"WARN"* ]] || [[ $line == *"Exception"* ]] || [[ $line == *"Failed"* ]] || [[ $line == *"IOException"* ]] || [[ $line == *"HTTP"* ]]; then
        echo "🚨 [$(date '+%H:%M:%S')] $line"
    fi
done 