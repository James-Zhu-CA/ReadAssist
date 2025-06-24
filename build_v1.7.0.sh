#!/bin/bash

# ReadAssist v1.7.0 构建脚本
# 日期: 2025-01-22

echo "🚀 开始构建 ReadAssist v1.7.0..."

# 设置Java 17环境
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
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
if [ -f "app/build/outputs/apk/release/ReadAssist-v1.7.0-release.apk" ]; then
    echo "✅ APK 构建成功!"
    echo "📍 文件位置: app/build/outputs/apk/release/ReadAssist-v1.7.0-release.apk"
    
    # 显示文件信息
    ls -lh app/build/outputs/apk/release/ReadAssist-v1.7.0-release.apk
    
    # 创建发布目录
    mkdir -p release/v1.7.0
    
    # 复制APK到发布目录
    cp app/build/outputs/apk/release/ReadAssist-v1.7.0-release.apk release/v1.7.0/
    
    # 创建版本说明文件
    cat > release/v1.7.0/VERSION_NOTES_v1.7.0.md << 'EOF'
# ReadAssist v1.7.0 版本说明

## 🎯 主要更新

### 🔧 核心功能优化
- **双重弹窗问题修复**: 解决掌阅设备截屏触发两次对话窗口的问题
- **监控机制优化**: 移除SAF监控，统一使用FileObserver机制，提高响应速度
- **截屏时间显示修复**: 修复通过系统截屏触发时时间显示不一致的问题

### 🤖 AI模型升级
- **Gemini模型更新**: 从实验版本升级到正式版本
  - `gemini-2.5-flash-preview-05-20` → `gemini-2.5-flash`
  - `gemini-2.5-pro-preview-05-06` → `gemini-2.5-pro`
- **动态模型选择**: 支持用户在设置中切换不同AI模型
- **API稳定性提升**: 使用正式版API，减少服务中断

### 📱 设备兼容性改进
- **Supernote设备优化**: 完善截屏目录权限管理
- **掌阅设备优化**: 解决双重监控导致的性能问题
- **权限检查改进**: 使用正确的SAF权限验证方法

### 🎨 用户体验提升
- **权限授权流程优化**: Supernote设备直接进入授权界面
- **设置界面完善**: 新增多平台AI模型选择功能
- **错误处理改进**: 更好的错误提示和处理机制

## 🔄 技术改进

### 性能优化
- 移除重复的监控机制，降低CPU使用率
- 优化文件监控防抖机制，减少无效触发
- 改进截屏文件处理逻辑，确保数据完整性

### 代码质量
- 重构截屏管理器，提高代码可维护性
- 统一设备类型判断逻辑
- 完善日志记录，便于问题排查

## 🐛 问题修复

1. **掌阅设备双重弹窗**: 移除SAF监控，只保留FileObserver
2. **截屏时间不一致**: 移除重复的截屏信息更新
3. **权限检查错误**: 修正Supernote设备的权限验证逻辑
4. **API模型过期**: 更新到Gemini正式版模型

## 📋 兼容性说明

- **最低Android版本**: Android 5.0 (API 21)
- **推荐设备**: Supernote A5X, 掌阅设备
- **支持的AI平台**: Gemini, SiliconFlow

## 🔗 下载链接

- [GitHub Releases](https://github.com/James-Zhu-CA/ReadAssist/releases/tag/v1.7.0)
- [APK直接下载](https://github.com/James-Zhu-CA/ReadAssist/releases/download/v1.7.0/ReadAssist-v1.7.0-release.apk)

---

**发布日期**: 2025-01-22
**构建版本**: versionCode 8
EOF
    
    echo ""
    echo "📋 v1.7.0 版本更新内容:"
    echo "• 🔧 修复掌阅设备双重弹窗问题"
    echo "• 🤖 升级Gemini模型到正式版本"
    echo "• 📱 优化Supernote设备权限管理"
    echo "• ⚡ 提升监控机制性能"
    echo "• 🎨 完善设置界面功能"
    echo ""
    echo "🎉 ReadAssist v1.7.0 构建完成!"
    echo "📁 发布文件已保存到: release/v1.7.0/"
    
else
    echo "❌ APK 构建失败!"
    echo "请检查构建日志中的错误信息。"
    exit 1
fi 