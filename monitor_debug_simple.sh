#!/bin/bash

echo "监控debug版本日志 - 请在应用中进行语言切换..."
echo "按Ctrl+C停止监控"
echo ""
 
# 实时监控debug版本相关的所有日志
adb logcat | grep -E "(readassist|BaseActivity|SettingsActivity|Language|SharedPreferences)" 