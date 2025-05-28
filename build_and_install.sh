#!/bin/bash

echo "🔧 ReadAssist 构建和安装"
echo "======================"

# 设置Java环境
echo "☕ 设置Java 17环境..."
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
echo "Java版本: $(java -version 2>&1 | head -1)"

echo ""
echo "📦 构建应用..."
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "❌ 构建失败，请检查错误信息"
    exit 1
fi

echo ""
echo "📱 安装应用到设备..."
adb install -r app/build/outputs/apk/debug/app-debug.apk
if [ $? -ne 0 ]; then
    echo "❌ 安装失败，请检查设备连接"
    exit 1
fi

echo ""
echo "🚀 启动ReadAssist应用..."
adb shell am start -n com.readassist/.ui.MainActivity

echo ""
echo "⏳ 等待5秒让应用完全启动..."
sleep 5

echo ""
echo "🔍 检查服务状态..."
adb shell dumpsys activity services | grep -E "(FloatingWindowService|TextAccessibilityService)" || echo "⚠️ 服务可能未启动"

echo ""
echo "✅ 构建安装完成！现在可以运行监听脚本进行测试。" 