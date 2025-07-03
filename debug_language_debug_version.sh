#!/bin/bash

echo "=== ReadAssist Debug版本语言调试脚本 ==="
echo ""

# 检查debug版本是否安装和运行
echo "1. 检查应用状态："
echo "已安装的包："
adb shell pm list packages | grep readassist

echo ""
echo "正在运行的进程："
adb shell ps | grep readassist

echo ""
echo "2. 检查SharedPreferences中的语言设置："
adb shell run-as com.readassist.debug cat /data/data/com.readassist.debug/shared_prefs/readassist_prefs.xml 2>/dev/null || echo "无法读取SharedPreferences文件"

echo ""
echo "3. 检查系统语言："
adb shell getprop ro.product.locale
adb shell getprop persist.sys.locale

echo ""
echo "4. 开始监控语言切换日志..."
echo "请在应用中进行语言切换操作，然后等待10秒..."
echo ""

# 实时监控debug版本的日志
timeout 15s adb logcat | grep -E "(com.readassist.debug|BaseActivity|SettingsActivity|Language|app_language)" &
LOGCAT_PID=$!

echo "监控中... (15秒后自动停止)"
wait $LOGCAT_PID

echo ""
echo "5. 检查最近的日志记录："
adb logcat -d | grep -E "(com.readassist.debug)" | grep -E "(BaseActivity|SettingsActivity|Language|app_language)" | tail -10

echo ""
echo "调试完成！" 