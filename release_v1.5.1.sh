#!/bin/bash

# ReadAssist v1.5.1 发布脚本
# 自动化构建、打包和发布流程

echo "🚀 ReadAssist v1.5.1 发布流程开始..."

# 检查Java版本
echo "📋 检查Java版本..."
java -version

# 清理之前的构建
echo "🧹 清理之前的构建..."
./gradlew clean

# 构建Debug版本（测试用）
echo "🔨 构建Debug版本..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo "✅ Debug版本构建成功"
else
    echo "❌ Debug版本构建失败，请检查代码"
    exit 1
fi

# 构建Release版本
echo "🔨 构建Release版本..."

# 构建AAB文件（Play Store）
echo "📦 生成AAB文件（Play Store）..."
./gradlew bundleRelease

# 构建APK文件（直接安装）  
echo "📦 生成APK文件（直接安装）..."
./gradlew assembleRelease

if [ $? -eq 0 ]; then
    echo "✅ Release版本构建成功"
else
    echo "❌ Release版本构建失败，请检查签名配置"
    exit 1
fi

# 查找生成的文件
echo "📁 查找生成的文件..."
find . -name "*v1.5.1*.apk" -o -name "*v1.5.1*.aab" | head -10

echo ""
echo "🎉 构建完成！"
echo ""
echo "📦 生成的文件："
echo "   - APK: app/build/outputs/apk/release/ReadAssist-v1.5.1-release.apk"
echo "   - AAB: app/build/outputs/bundle/release/ReadAssist-v1.5.1-release.aab"
echo ""
echo "🔄 下一步操作："
echo "1. 测试APK文件在设备上的安装和运行"
echo "2. 创建GitHub Release (tag: v1.5.1)"
echo "3. 上传AAB到Google Play Console"
echo "4. 填写版本说明并提交审核"
echo ""
echo "📝 版本更新内容："
echo "- 全新AI书本图标设计"  
echo "- 截屏功能优化和稳定性提升"
echo "- 悬浮按钮界面改进"
echo "- 墨水屏设备兼容性增强" 