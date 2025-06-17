#!/bin/bash

echo "🔍 开始监控ReadAssist网络请求日志..."
echo "📱 请在设备上测试ReadAssist的网络功能"
echo "⏹️  按Ctrl+C停止监控"
echo ""

# 监控网络相关的日志
adb logcat | grep -E "(ReadAssist|GeminiRepository|NetworkModule|HttpLoggingInterceptor|OkHttp|Retrofit|SSLException|UnknownHostException|SocketTimeoutException|ConnectException|NetworkError|HTTP.*[4-5][0-9][0-9])" --line-buffered | while read line; do
    echo "[$(date '+%H:%M:%S')] $line"
done 