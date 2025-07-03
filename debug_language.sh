#!/bin/bash

echo "🔍 ReadAssist 语言设置调试工具"
echo "================================="

# 检查设备连接
if ! adb devices | grep -q "device$"; then
    echo "❌ 没有检测到连接的Android设备"
    exit 1
fi

echo "📱 检查设备信息:"
adb shell getprop ro.product.locale || adb shell getprop persist.sys.locale || echo "无法获取系统语言"

echo ""
echo "🔧 检查应用SharedPreferences:"
# 检查应用的语言设置
adb shell "su -c 'cat /data/data/com.readassist/shared_prefs/readassist_prefs.xml 2>/dev/null | grep app_language || echo \"未找到语言设置\"'"

echo ""
echo "📋 检查应用进程:"
adb shell ps | grep com.readassist || echo "应用未在运行"

echo ""
echo "📜 最近日志 (最后20行):"
adb logcat -t 20 | grep -E "(BaseActivity|SettingsActivity|Language|语言)" || echo "没有找到相关日志"

echo ""
echo "🔄 清除应用日志缓存并重启应用以查看完整日志："
echo "执行: adb logcat -c && adb shell am start -n com.readassist/.ui.MainActivity" 