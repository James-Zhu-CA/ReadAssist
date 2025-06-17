#!/bin/bash

echo "🔧 ReadAssist Release 构建"
echo "========================="

# 设置Java环境
echo "☕ 设置Java 17环境..."
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
echo "Java版本: $(java -version 2>&1 | head -1)"

echo ""
echo "🧹 清理旧构建文件..."
./gradlew clean

echo ""
echo "📦 构建Release APK..."
./gradlew assembleRelease
if [ $? -ne 0 ]; then
    echo "❌ Release APK构建失败，请检查错误信息"
    exit 1
fi

echo ""
echo "📱 构建Play Store AAB包..."
./gradlew bundleRelease
if [ $? -ne 0 ]; then
    echo "❌ AAB包构建失败，请检查错误信息"
    exit 1
fi

echo ""
echo "📋 构建结果："
echo "============="

# 查找并显示APK文件信息
APK_FILE=$(find app/build/outputs/apk/release/ -name "*.apk" | head -1)
if [ -n "$APK_FILE" ]; then
    APK_SIZE=$(du -h "$APK_FILE" | cut -f1)
    echo "✅ Release APK: $APK_FILE ($APK_SIZE)"
else
    echo "❌ 未找到Release APK文件"
fi

# 查找并显示AAB文件信息
AAB_FILE=$(find app/build/outputs/bundle/release/ -name "*.aab" | head -1)
if [ -n "$AAB_FILE" ]; then
    AAB_SIZE=$(du -h "$AAB_FILE" | cut -f1)
    echo "✅ Play Store AAB: $AAB_FILE ($AAB_SIZE)"
else
    echo "❌ 未找到AAB文件"
fi

echo ""
echo "📱 是否要安装Release APK到设备？(y/n)"
read -r install_choice

if [ "$install_choice" = "y" ] || [ "$install_choice" = "Y" ]; then
    if [ -n "$APK_FILE" ]; then
        echo "📱 安装Release APK到设备..."
        adb install -r "$APK_FILE"
        if [ $? -eq 0 ]; then
            echo "✅ 安装成功！"

echo ""
echo "🚀 启动ReadAssist应用..."
adb shell am start -n com.readassist/.ui.MainActivity

echo ""
echo "⏳ 等待5秒让应用完全启动..."
sleep 5

echo ""
echo "🔍 检查服务状态..."
adb shell dumpsys activity services | grep -E "(FloatingWindowService|TextAccessibilityService)" || echo "⚠️ 服务可能未启动"
        else
            echo "❌ 安装失败，请检查设备连接"
        fi
    else
        echo "❌ 没有APK文件可安装"
    fi
else
    echo "⏭️ 跳过安装"
fi

echo ""
echo "📊 版本信息："
echo "============"
VERSION_NAME=$(grep "versionName" app/build.gradle | head -1 | sed 's/.*"\(.*\)".*/\1/')
VERSION_CODE=$(grep "versionCode" app/build.gradle | head -1 | sed 's/.*\([0-9]\+\).*/\1/')
echo "版本名称: $VERSION_NAME"
echo "版本代码: $VERSION_CODE"

echo ""
echo "✅ 构建完成！"
echo ""
echo "📁 文件位置："
if [ -n "$APK_FILE" ]; then
    echo "   APK: $APK_FILE"
fi
if [ -n "$AAB_FILE" ]; then
    echo "   AAB: $AAB_FILE"
fi

echo ""
echo "🎯 下一步操作："
echo "   1. 测试Release APK功能"
echo "   2. 上传AAB到Google Play Console"
echo "   3. 发布GitHub Release" 