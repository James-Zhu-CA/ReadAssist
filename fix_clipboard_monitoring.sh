#!/bin/bash

echo "=== ReadAssist 剪贴板监听修复脚本 ==="
echo "开始时间: $(date)"
echo

echo "🔧 执行修复步骤..."
echo

echo "步骤1: 重启无障碍服务..."
adb shell am force-stop com.readassist
sleep 2
adb shell am start -n com.readassist/.ui.MainActivity
sleep 3

echo "步骤2: 检查服务状态..."
adb shell settings get secure enabled_accessibility_services | grep readassist
if [ $? -eq 0 ]; then
    echo "✅ 无障碍服务已启用"
else
    echo "❌ 无障碍服务未启用，请手动启用"
    echo "请到 设置 → 辅助功能 → ReadAssist → 启用"
fi

echo "步骤3: 测试剪贴板监听..."
echo "请按以下步骤测试："
echo "1. 打开任意应用（如浏览器）"
echo "2. 复制一段文本"
echo "3. 观察是否自动弹出ReadAssist聊天窗口"
echo

echo "步骤4: 如果仍然不工作，请运行详细诊断..."
echo "执行: ./diagnose_clipboard_detailed.sh"
echo

echo "=== 修复脚本完成 ==="
