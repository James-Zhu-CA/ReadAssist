#!/bin/bash

echo "🎨 开始复制ReadAssist v1.5.1 新图标..."

# 检查源文件是否存在
echo "📋 检查源文件..."
for size in 48 72 96 144 192 432; do
    if [ ! -f "ic_launcher_${size}.png" ]; then
        echo "❌ 缺少文件: ic_launcher_${size}.png"
        exit 1
    else
        echo "✅ 找到: ic_launcher_${size}.png"
    fi
done

echo ""
echo "📁 开始复制图标文件到各密度目录..."

# 复制到mdpi目录 (48x48)
echo "📱 复制到mdpi (48x48)..."
cp ic_launcher_48.png app/src/main/res/mipmap-mdpi/ic_launcher.png
cp ic_launcher_48.png app/src/main/res/mipmap-mdpi/ic_launcher_round.png
cp ic_launcher_432.png app/src/main/res/mipmap-mdpi/ic_launcher_foreground.png

# 复制到hdpi目录 (72x72) 
echo "📱 复制到hdpi (72x72)..."
cp ic_launcher_72.png app/src/main/res/mipmap-hdpi/ic_launcher.png
cp ic_launcher_72.png app/src/main/res/mipmap-hdpi/ic_launcher_round.png
cp ic_launcher_432.png app/src/main/res/mipmap-hdpi/ic_launcher_foreground.png

# 复制到xhdpi目录 (96x96)
echo "📱 复制到xhdpi (96x96)..." 
cp ic_launcher_96.png app/src/main/res/mipmap-xhdpi/ic_launcher.png
cp ic_launcher_96.png app/src/main/res/mipmap-xhdpi/ic_launcher_round.png
cp ic_launcher_432.png app/src/main/res/mipmap-xhdpi/ic_launcher_foreground.png

# 复制到xxhdpi目录 (144x144)
echo "📱 复制到xxhdpi (144x144)..."
cp ic_launcher_144.png app/src/main/res/mipmap-xxhdpi/ic_launcher.png
cp ic_launcher_144.png app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png
cp ic_launcher_432.png app/src/main/res/mipmap-xxhdpi/ic_launcher_foreground.png

# 复制到xxxhdpi目录 (192x192)
echo "📱 复制到xxxhdpi (192x192)..."
cp ic_launcher_192.png app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
cp ic_launcher_192.png app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png  
cp ic_launcher_432.png app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png

echo ""
echo "✅ 图标文件复制完成！"
echo ""
echo "📋 验证复制结果..."

# 验证文件是否成功复制
for dir in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
    echo "📁 检查 mipmap-${dir}:"
    ls -la app/src/main/res/mipmap-${dir}/ic_launcher*.png 2>/dev/null || echo "  ❌ 目录不存在或文件复制失败"
done

echo ""
echo "🎉 ReadAssist新图标安装完成！"
echo "🔄 下一步：运行 ./gradlew assembleDebug 测试构建" 