#!/bin/bash

# 截屏性能测试脚本

echo "🚀 截屏性能测试工具"
echo "===================="

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

# 创建测试结果目录
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
TEST_DIR="screenshot_performance_test_$TIMESTAMP"
mkdir -p "$TEST_DIR"

echo "📁 测试结果将保存到: $TEST_DIR"

# 获取设备信息
echo ""
echo "📱 设备信息:"
DEVICE_MODEL=$(adb shell getprop ro.product.model)
DEVICE_BRAND=$(adb shell getprop ro.product.brand)
ANDROID_VERSION=$(adb shell getprop ro.build.version.release)

echo "型号: $DEVICE_MODEL"
echo "品牌: $DEVICE_BRAND" 
echo "Android版本: $ANDROID_VERSION"

# 保存设备信息到文件
{
    echo "=== 设备信息 ==="
    echo "型号: $DEVICE_MODEL"
    echo "品牌: $DEVICE_BRAND"
    echo "Android版本: $ANDROID_VERSION"
    echo "测试时间: $(date)"
    echo ""
} > "$TEST_DIR/device_info.txt"

# 清理旧日志
echo ""
echo "🧹 清理旧日志..."
adb logcat -c

echo ""
echo "🎯 开始性能测试..."
echo "请在设备上触发截屏操作，脚本将监控性能数据"
echo "按 Ctrl+C 停止测试"

# 创建性能监控函数
monitor_performance() {
    local test_file="$1"
    echo "开始监控截屏性能..." > "$test_file"
    
    # 监控关键性能指标
    adb logcat | grep -E "(captureScreenUltraFast|captureScreenFast|PixelCopy|VirtualDisplay|截屏成功|截屏失败)" | while read -r line; do
        timestamp=$(date '+%H:%M:%S.%3N')
        echo "[$timestamp] $line" | tee -a "$test_file"
        
        # 检测截屏开始
        if echo "$line" | grep -q "captureScreenUltraFast.*开始"; then
            echo "🚀 [$timestamp] 检测到超快速截屏开始" | tee -a "$test_file"
            START_TIME=$(date +%s%3N)
        fi
        
        # 检测截屏成功
        if echo "$line" | grep -q "超快速截屏成功\|PixelCopy截屏成功"; then
            END_TIME=$(date +%s%3N)
            if [ -n "$START_TIME" ]; then
                DURATION=$((END_TIME - START_TIME))
                echo "✅ [$timestamp] 截屏完成，耗时: ${DURATION}ms" | tee -a "$test_file"
                
                # 性能评估
                if [ "$DURATION" -lt 1500 ]; then
                    echo "🎉 性能优秀 (<1.5秒)" | tee -a "$test_file"
                elif [ "$DURATION" -lt 2500 ]; then
                    echo "👍 性能良好 (<2.5秒)" | tee -a "$test_file"
                elif [ "$DURATION" -lt 4000 ]; then
                    echo "⚠️ 性能一般 (<4秒)" | tee -a "$test_file"
                else
                    echo "❌ 性能较差 (>4秒)" | tee -a "$test_file"
                fi
                
                START_TIME=""
            fi
        fi
        
        # 检测截屏失败
        if echo "$line" | grep -q "截屏失败\|超快速截屏失败"; then
            echo "❌ [$timestamp] 截屏失败" | tee -a "$test_file"
            START_TIME=""
        fi
    done
}

# 启动性能监控
monitor_performance "$TEST_DIR/performance_log.txt" &
MONITOR_PID=$!

# 同时监控应用状态
{
    echo "=== 应用状态监控 ==="
    while true; do
        timestamp=$(date '+%H:%M:%S')
        
        # 检查应用是否运行
        app_running=$(adb shell ps | grep com.readassist | wc -l)
        echo "[$timestamp] ReadAssist进程数: $app_running"
        
        # 检查内存使用
        if [ "$app_running" -gt 0 ]; then
            memory_info=$(adb shell dumpsys meminfo com.readassist | grep "TOTAL" | head -1)
            echo "[$timestamp] 内存使用: $memory_info"
        fi
        
        sleep 5
    done
} > "$TEST_DIR/app_status_log.txt" &
APP_MONITOR_PID=$!

# 等待用户中断
trap 'echo ""; echo "🛑 停止测试..."; kill $MONITOR_PID $APP_MONITOR_PID 2>/dev/null; exit 0' INT

echo ""
echo "📊 实时性能监控中..."
echo "提示："
echo "1. 在设备上点击悬浮按钮触发截屏"
echo "2. 观察终端输出的性能数据"
echo "3. 按 Ctrl+C 停止测试并查看报告"

# 保持脚本运行
wait $MONITOR_PID

# 生成测试报告
echo ""
echo "📋 生成测试报告..."

{
    echo "=== 截屏性能测试报告 ==="
    echo "测试时间: $(date)"
    echo "设备: $DEVICE_BRAND $DEVICE_MODEL (Android $ANDROID_VERSION)"
    echo ""
    
    echo "=== 性能统计 ==="
    
    # 统计截屏次数
    total_attempts=$(grep -c "检测到.*截屏开始" "$TEST_DIR/performance_log.txt" 2>/dev/null || echo "0")
    successful_screenshots=$(grep -c "截屏完成，耗时" "$TEST_DIR/performance_log.txt" 2>/dev/null || echo "0")
    failed_screenshots=$(grep -c "截屏失败" "$TEST_DIR/performance_log.txt" 2>/dev/null || echo "0")
    
    echo "总尝试次数: $total_attempts"
    echo "成功次数: $successful_screenshots"
    echo "失败次数: $failed_screenshots"
    
    if [ "$total_attempts" -gt 0 ]; then
        success_rate=$((successful_screenshots * 100 / total_attempts))
        echo "成功率: ${success_rate}%"
    fi
    
    echo ""
    echo "=== 性能分析 ==="
    
    # 提取耗时数据
    if [ -f "$TEST_DIR/performance_log.txt" ]; then
        grep "截屏完成，耗时" "$TEST_DIR/performance_log.txt" | while read -r line; do
            duration=$(echo "$line" | grep -o '[0-9]\+ms' | grep -o '[0-9]\+')
            echo "耗时: ${duration}ms"
        done > "$TEST_DIR/durations.txt"
        
        if [ -s "$TEST_DIR/durations.txt" ]; then
            echo "各次截屏耗时:"
            cat "$TEST_DIR/durations.txt"
            
            # 计算平均耗时
            total_time=0
            count=0
            while read -r line; do
                time=$(echo "$line" | grep -o '[0-9]\+')
                total_time=$((total_time + time))
                count=$((count + 1))
            done < "$TEST_DIR/durations.txt"
            
            if [ "$count" -gt 0 ]; then
                avg_time=$((total_time / count))
                echo ""
                echo "平均耗时: ${avg_time}ms"
                
                # 性能评级
                if [ "$avg_time" -lt 1500 ]; then
                    echo "性能评级: 🎉 优秀"
                elif [ "$avg_time" -lt 2500 ]; then
                    echo "性能评级: 👍 良好"
                elif [ "$avg_time" -lt 4000 ]; then
                    echo "性能评级: ⚠️ 一般"
                else
                    echo "性能评级: ❌ 较差"
                fi
            fi
        fi
    fi
    
    echo ""
    echo "=== 优化建议 ==="
    
    if [ "$success_rate" -lt 90 ]; then
        echo "- 成功率较低，建议检查截屏权限和设备兼容性"
    fi
    
    if [ "$avg_time" -gt 2500 ]; then
        echo "- 截屏耗时较长，建议启用超快速截屏模式"
        echo "- 检查设备性能和内存使用情况"
    fi
    
    echo "- 详细日志请查看: $TEST_DIR/performance_log.txt"
    echo "- 应用状态日志: $TEST_DIR/app_status_log.txt"
    
} > "$TEST_DIR/test_report.txt"

echo "✅ 测试完成！"
echo "📊 测试报告已保存到: $TEST_DIR/test_report.txt"
echo ""
echo "📋 快速查看报告:"
cat "$TEST_DIR/test_report.txt" 