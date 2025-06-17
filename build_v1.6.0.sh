#!/bin/bash

# ReadAssist v1.6.0 构建脚本
# 日期: 2025-01-16

echo "🚀 开始构建 ReadAssist v1.6.0..."

# 设置Java 17环境
export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.15/libexec/openjdk.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH

echo "☕ Java版本检查:"
java -version

echo ""
echo "🧹 清理之前的构建..."
./gradlew clean

echo ""
echo "🔨 构建 Release APK..."
./gradlew assembleRelease

echo ""
echo "📦 检查生成的文件..."
if [ -f "app/build/outputs/apk/release/ReadAssist-v1.6.0-release.apk" ]; then
    echo "✅ APK 构建成功!"
    echo "📍 文件位置: app/build/outputs/apk/release/ReadAssist-v1.6.0-release.apk"
    
    # 显示文件信息
    ls -lh app/build/outputs/apk/release/ReadAssist-v1.6.0-release.apk
    
    # 创建发布目录
    mkdir -p release/v1.6.0
    
    # 复制APK到发布目录
    cp app/build/outputs/apk/release/ReadAssist-v1.6.0-release.apk release/v1.6.0/
    cp VERSION_NOTES_v1.6.0.md release/v1.6.0/
    
    echo ""
    echo "📋 v1.6.0 版本更新内容:"
    echo "• 🎨 对话界面优化 - 新增消息发送者标识和时间戳"
    echo "• 🔄 连续对话支持 - AI现在能记住上下文"
    echo "• 📝 文字选择和复制优化 - 智能长按复制"
    echo "• 🔧 界面细节优化 - 输入框提示修复"
    echo ""
    echo "🎉 ReadAssist v1.6.0 构建完成!"
    echo "📁 发布文件已保存到: release/v1.6.0/"
    
else
    echo "❌ APK 构建失败!"
    echo "请检查构建日志中的错误信息。"
    exit 1
fi 