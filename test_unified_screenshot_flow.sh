#!/bin/bash

# 统一截屏流程测试脚本

echo "🔄 统一截屏流程测试开始"
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
DEVICE_MODEL=$(adb shell getprop ro.product.model)
DEVICE_BRAND=$(adb shell getprop ro.product.brand)
echo "型号: $DEVICE_MODEL"
echo "品牌: $DEVICE_BRAND"

# 检查应用是否运行
echo ""
echo "🔍 检查应用状态:"
APP_PID=$(adb shell pidof com.readassist)
if [ -n "$APP_PID" ]; then
    echo "✅ ReadAssist应用正在运行 (PID: $APP_PID)"
else
    echo "❌ ReadAssist应用未运行"
    echo "请先启动应用，然后重新运行此测试"
    exit 1
fi

# 检查所有可能的截屏目录
echo ""
echo "📁 统一监控目录检查:"

DIRECTORIES=(
    "/storage/emulated/0/Pictures/Screenshots"
    "/storage/emulated/0/DCIM/Screenshots"
    "/storage/emulated/0/iReader/saveImage/tmp"
    "/storage/emulated/0/Android/data/com.readassist/files"
)

for dir in "${DIRECTORIES[@]}"; do
    echo ""
    echo "检查目录: $dir"
    if adb shell "[ -d '$dir' ]" 2>/dev/null; then
        echo "   ✅ 目录存在"
        FILE_COUNT=$(adb shell "ls '$dir' 2>/dev/null | wc -l" 2>/dev/null || echo "0")
        echo "   📄 文件数量: $FILE_COUNT"
        
        # 检查最近的截屏文件
        RECENT_SCREENSHOTS=$(adb shell "find '$dir' -name '*screenshot*' -o -name '*Screenshot*' -o -name '*screen*' -o -name '*capture*' 2>/dev/null | head -3")
        if [ -n "$RECENT_SCREENSHOTS" ]; then
            echo "   📸 最近的截屏文件:"
            echo "$RECENT_SCREENSHOTS" | while read -r file; do
                if [ -n "$file" ]; then
                    TIMESTAMP=$(adb shell "stat -c %Y '$file' 2>/dev/null" || echo "unknown")
                    echo "      - $(basename "$file") (时间戳: $TIMESTAMP)"
                fi
            done
        else
            echo "   📸 无截屏文件"
        fi
    else
        echo "   ❌ 目录不存在或无权限"
    fi
done

# 清除日志并开始监控
echo ""
echo "🔍 开始监控统一流程日志..."
adb logcat -c

# 启动日志监控（后台运行）
{
    adb logcat -v time | grep -E "(统一流程|FileObserver|ScreenshotManager|FloatingWindowServiceNew)" > unified_flow_test.log 2>&1 &
    LOGCAT_PID=$!
    
    echo "日志监控已启动 (PID: $LOGCAT_PID)"
    echo ""
    echo "📋 测试步骤:"
    echo "1. 现在请点击悬浮按钮进行截屏"
    echo "2. 或者使用系统截屏功能"
    echo "3. 观察是否通过目录监控统一触发弹窗"
    echo "4. 按回车键停止监控并查看结果"
    echo ""
    echo "⏳ 等待用户操作..."
    
    read -r
    
    # 停止日志监控
    kill $LOGCAT_PID 2>/dev/null
    wait $LOGCAT_PID 2>/dev/null
    
    echo ""
    echo "📊 测试结果分析:"
    
    if [ -f "unified_flow_test.log" ] && [ -s "unified_flow_test.log" ]; then
        echo ""
        echo "🔍 统一流程相关日志:"
        echo "========================"
        cat unified_flow_test.log | tail -20
        
        echo ""
        echo "📈 关键事件统计:"
        SCREENSHOT_EVENTS=$(grep -c "统一流程.*开始执行截屏" unified_flow_test.log 2>/dev/null || echo "0")
        MONITOR_EVENTS=$(grep -c "统一流程.*检测到截屏文件" unified_flow_test.log 2>/dev/null || echo "0")
        SUCCESS_EVENTS=$(grep -c "统一流程.*截屏成功" unified_flow_test.log 2>/dev/null || echo "0")
        
        echo "- 截屏开始事件: $SCREENSHOT_EVENTS"
        echo "- 目录监控事件: $MONITOR_EVENTS"
        echo "- 截屏成功事件: $SUCCESS_EVENTS"
        
        if [ "$SCREENSHOT_EVENTS" -gt 0 ] && [ "$MONITOR_EVENTS" -gt 0 ] && [ "$SUCCESS_EVENTS" -gt 0 ]; then
            echo ""
            echo "✅ 统一流程测试通过！"
            echo "   截屏 -> 目录监控 -> 弹窗 的流程正常工作"
        else
            echo ""
            echo "⚠️  统一流程可能存在问题"
            echo "   请检查日志中的详细信息"
        fi
    else
        echo "❌ 未捕获到日志，可能应用未运行或权限不足"
    fi
} &

# 等待后台任务完成
wait

echo ""
echo "✅ 统一截屏流程测试完成"
echo ""
echo "💡 测试总结:"
echo "1. 如果看到'统一流程'相关日志，说明新流程已生效"
echo "2. 所有设备类型现在都使用相同的弹窗逻辑"
echo "3. 截屏和弹窗分离，提高了系统稳定性"
echo ""
echo "📁 详细日志已保存到: unified_flow_test.log" 