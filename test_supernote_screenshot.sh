#!/bin/bash

# Supernote设备截屏目录兼容性测试脚本

echo "🔍 Supernote设备截屏目录兼容性测试"
echo "=================================="

# 检查ADB连接
if ! command -v adb &> /dev/null; then
    echo "❌ 未找到adb命令"
    exit 1
fi

if [ "$(adb devices | grep -c 'device$')" -eq 0 ]; then
    echo "❌ 未检测到设备连接"
    exit 1
fi

echo "✅ 设备连接正常"

# 获取设备信息
echo ""
echo "📱 设备信息:"
echo "型号: $(adb shell getprop ro.product.model)"
echo "制造商: $(adb shell getprop ro.product.manufacturer)"
echo "品牌: $(adb shell getprop ro.product.brand)"

# 检查各个截屏目录
echo ""
echo "📁 截屏目录检查:"

echo "1. 标准截屏目录 (/storage/emulated/0/Pictures/Screenshots/):"
adb shell ls -la /storage/emulated/0/Pictures/Screenshots/ 2>/dev/null | head -5
if [ $? -eq 0 ]; then
    echo "   ✅ 目录存在"
else
    echo "   ❌ 目录不存在或无权限"
fi

echo ""
echo "2. DCIM截屏目录 (/storage/emulated/0/DCIM/Screenshots/):"
adb shell ls -la /storage/emulated/0/DCIM/Screenshots/ 2>/dev/null | head -5
if [ $? -eq 0 ]; then
    echo "   ✅ 目录存在"
else
    echo "   ❌ 目录不存在或无权限"
fi

echo ""
echo "3. iReader截屏目录 (/storage/emulated/0/iReader/saveImage/tmp/):"
adb shell ls -la /storage/emulated/0/iReader/saveImage/tmp/ 2>/dev/null | head -5
if [ $? -eq 0 ]; then
    echo "   ✅ 目录存在"
else
    echo "   ❌ 目录不存在或无权限"
fi

echo ""
echo "4. Supernote应用私有目录 (/storage/emulated/0/Android/data/com.readassist/files/):"
adb shell ls -la /storage/emulated/0/Android/data/com.readassist/files/ 2>/dev/null | head -10
if [ $? -eq 0 ]; then
    echo "   ✅ 目录存在"
    # 检查是否有截屏文件
    SCREENSHOT_COUNT=$(adb shell ls /storage/emulated/0/Android/data/com.readassist/files/ 2>/dev/null | grep -i screenshot | wc -l)
    echo "   📸 截屏文件数量: $SCREENSHOT_COUNT"
else
    echo "   ❌ 目录不存在或无权限"
fi

# 检查最近的截屏文件
echo ""
echo "🔍 最近截屏文件检查:"

# 在所有目录中查找最近的截屏文件
echo "正在搜索最近的截屏文件..."

# 创建临时脚本在设备上执行
adb shell 'find /storage/emulated/0/Pictures/Screenshots/ /storage/emulated/0/DCIM/Screenshots/ /storage/emulated/0/iReader/saveImage/tmp/ /storage/emulated/0/Android/data/com.readassist/files/ -name "*screenshot*" -o -name "*Screenshot*" 2>/dev/null | head -10'

echo ""
echo "📊 应用日志中的截屏相关信息:"
adb logcat -d | grep -i "screenshot\|截屏" | tail -10

echo ""
echo "✅ 测试完成"
echo ""
echo "💡 如果在Supernote设备上看到截屏文件保存在应用私有目录中，"
echo "   说明代码修改已经生效，应用现在可以正确检测到这些截屏文件。" 