#!/bin/bash

# ReadAssist 统一日志监听脚本
# 用法: ./monitor_logs.sh [模式]
# 模式: text (文本选择) | all (所有事件) | simple (简化输出) | screenshot (截屏输入功能)

MODE=${1:-text}

echo "🔍 ReadAssist 日志监听器"
echo "========================"
echo "监听模式: $MODE"
echo ""

case $MODE in
    "text")
        echo "📝 专注监听文本选择相关事件"
        echo "🔍 监控内容："
        echo "  📋 原始文本候选"
        echo "  🔍 文本过滤过程"
        echo "  🎯 文本选择状态"
        echo "  📝 实际文本内容"
        echo "  📋 剪贴板变化"
        ;;
    "all")
        echo "📱 监听所有应用事件"
        echo "🔍 监控内容："
        echo "  🚀 服务启动和连接"
        echo "  🎯 所有辅助功能事件"
        echo "  🎈 悬浮窗操作"
        echo "  👆 用户交互"
        echo "  ❌ 错误和警告"
        ;;
    "simple")
        echo "📋 简化输出模式"
        echo "🔍 监控内容："
        echo "  🎯 关键文本事件"
        echo "  ❌ 错误信息"
        ;;
    "screenshot")
        echo "📸 截屏输入功能测试模式"
        echo "🔍 监控内容："
        echo "  📸 截屏分析流程"
        echo "  📝 输入框文字添加"
        echo "  🤖 图片消息发送"
        echo "  ✅ AI分析结果"
        echo ""
        echo "📋 测试流程："
        echo "  1. 点击悬浮按钮进行截屏"
        echo "  2. 截屏完成后，输入框会自动添加提示文字"
        echo "  3. 您可以在输入框中编辑或添加更多文字"
        echo "  4. 点击发送按钮，图片和文字一起发送给AI"
        ;;
    *)
        echo "❌ 未知模式: $MODE"
        echo "支持的模式: text | all | simple | screenshot"
        exit 1
        ;;
esac

echo ""
echo "按 Ctrl+C 停止监控"
echo ""

# 清除之前的日志
adb logcat -c

# 根据模式选择不同的监听策略
case $MODE in
    "text")
        adb logcat | grep -E "(TextAccessibilityService|FloatingWindowService)" | while read line; do
            timestamp=$(date '+%H:%M:%S')
            
            if [[ "$line" == *"原始候选"* ]] || [[ "$line" == *"过滤候选"* ]]; then
                echo "[$timestamp] 📊📊📊 文本候选: $line"
            elif [[ "$line" == *"Supernote 事件文本"* ]]; then
                echo "[$timestamp] 📝📝📝 事件文本: $line"
            elif [[ "$line" == *"Text detected:"* ]]; then
                echo "[$timestamp] 🎯🎯🎯 检测到文本: $line"
            elif [[ "$line" == *"剪贴板"* ]] || [[ "$line" == *"clipboard"* ]]; then
                echo "[$timestamp] 📋📋📋 剪贴板: $line"
            elif [[ "$line" == *"选中文本"* ]] || [[ "$line" == *"选择"* ]]; then
                echo "[$timestamp] 🎯🎯🎯 文本选择: $line"
            elif [[ "$line" == *"过滤"* ]]; then
                echo "[$timestamp] 🔍🔍🔍 文本过滤: $line"
            elif [[ "$line" == *"ERROR"* ]] || [[ "$line" == *"WARN"* ]]; then
                echo "[$timestamp] ❌❌❌ 错误警告: $line"
            else
                echo "[$timestamp] 📱 其他: $line"
            fi
        done
        ;;
    "all")
        adb logcat | grep -E "(TextAccessibilityService|FloatingWindowService)" | while read line; do
            timestamp=$(date '+%H:%M:%S')
            
            if [[ "$line" == *"onCreate"* ]] || [[ "$line" == *"onServiceConnected"* ]]; then
                echo "[$timestamp] 🚀🚀🚀 服务启动: $line"
            elif [[ "$line" == *"🔥 Supernote 事件"* ]]; then
                echo "[$timestamp] 🔥🔥🔥 Supernote事件: $line"
            elif [[ "$line" == *"点击"* ]] || [[ "$line" == *"按钮"* ]]; then
                echo "[$timestamp] 👆👆👆 用户交互: $line"
            elif [[ "$line" == *"悬浮"* ]] || [[ "$line" == *"Floating"* ]]; then
                echo "[$timestamp] 🎈🎈🎈 悬浮窗: $line"
            elif [[ "$line" == *"ERROR"* ]] || [[ "$line" == *"WARN"* ]]; then
                echo "[$timestamp] ❌❌❌ 错误警告: $line"
            else
                echo "[$timestamp] 📱 应用: $line"
            fi
        done
        ;;
    "simple")
        adb logcat | grep -E "(TextAccessibilityService|FloatingWindowService)" | grep -E "(Text detected|选中文本|ERROR|WARN|原始候选|过滤候选)" | while read line; do
            timestamp=$(date '+%H:%M:%S')
            echo "[$timestamp] $line"
        done
        ;;
    "screenshot")
        adb logcat | grep -E "(FloatingWindowService|ScreenshotService|GeminiRepository)" | while read line; do
            timestamp=$(date '+%H:%M:%S')
            
            if [[ "$line" == *"📤📤📤"* ]]; then
                echo "[$timestamp] 📤📤📤 发送给AI: $line"
            elif [[ "$line" == *"📥📥📥"* ]]; then
                echo "[$timestamp] 📥📥📥 AI回复: $line"
            elif [[ "$line" == *"📸"* ]]; then
                echo "[$timestamp] 📸📸📸 截屏: $line"
            elif [[ "$line" == *"截屏成功"* ]] || [[ "$line" == *"onScreenshotSuccess"* ]]; then
                echo "[$timestamp] ✅✅✅ 截屏成功: $line"
            elif [[ "$line" == *"截屏已保存"* ]] || [[ "$line" == *"pendingScreenshotBitmap"* ]]; then
                echo "[$timestamp] 💾💾💾 图片保存: $line"
            elif [[ "$line" == *"请分析这张截屏图片"* ]] || [[ "$line" == *"输入框"* ]]; then
                echo "[$timestamp] 📝📝📝 输入框: $line"
            elif [[ "$line" == *"sendImageMessageToAI"* ]] || [[ "$line" == *"发送图片消息"* ]]; then
                echo "[$timestamp] 🚀🚀🚀 发送图片: $line"
            elif [[ "$line" == *"🤖"* ]] || [[ "$line" == *"正在分析图片"* ]]; then
                echo "[$timestamp] 🤖🤖🤖 AI分析: $line"
            elif [[ "$line" == *"AI分析成功"* ]] || [[ "$line" == *"图片分析完成"* ]]; then
                echo "[$timestamp] 🎉🎉🎉 分析完成: $line"
            elif [[ "$line" == *"ERROR"* ]] || [[ "$line" == *"❌"* ]]; then
                echo "[$timestamp] ❌❌❌ 错误: $line"
            elif [[ "$line" == *"WARN"* ]] || [[ "$line" == *"⚠️"* ]]; then
                echo "[$timestamp] ⚠️⚠️⚠️ 警告: $line"
            else
                echo "[$timestamp] 📱 其他: $line"
            fi
        done
        ;;
esac 