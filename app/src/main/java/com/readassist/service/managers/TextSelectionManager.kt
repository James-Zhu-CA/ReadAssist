package com.readassist.service.managers

import android.graphics.Rect
import android.util.Log

/**
 * 管理文本选择和处理
 */
class TextSelectionManager {
    companion object {
        private const val TAG = "TextSelectionManager"
    }
    
    // 文本和上下文
    private var lastDetectedText: String = ""
    private var currentAppPackage: String = ""
    private var currentBookName: String = ""
    
    // 文本选择状态
    private var isTextSelectionActive = false
    private var textSelectionBounds: Rect? = null
    private var lastSelectionPosition: Pair<Int, Int>? = null
    
    // 回调接口
    private var callbacks: TextSelectionCallbacks? = null
    
    /**
     * 设置回调
     */
    fun setCallbacks(callbacks: TextSelectionCallbacks) {
        this.callbacks = callbacks
    }
    
    /**
     * 处理检测到的文本
     */
    fun handleTextDetected(text: String, appPackage: String, bookName: String) {
        lastDetectedText = text
        currentAppPackage = appPackage
        currentBookName = bookName
        
        Log.d(TAG, "Text detected: ${text.take(100)}... from $appPackage")
        
        // 通知回调
        callbacks?.onTextDetected(text, appPackage, bookName)
    }
    
    /**
     * 处理选中的文本
     */
    fun handleTextSelected(text: String, appPackage: String, bookName: String, 
                         selectionX: Int, selectionY: Int, 
                         selectionWidth: Int, selectionHeight: Int) {
        
        currentAppPackage = appPackage
        currentBookName = bookName
        
        // 保存选择位置信息
        if (selectionX >= 0 && selectionY >= 0 && selectionWidth > 0 && selectionHeight > 0) {
            textSelectionBounds = Rect(selectionX, selectionY, 
                selectionX + selectionWidth, selectionY + selectionHeight)
            lastSelectionPosition = Pair(selectionX + selectionWidth / 2, selectionY + selectionHeight / 2)
            Log.d(TAG, "📍 保存文本选择位置: $textSelectionBounds")
        }
        
        Log.d(TAG, "Text selected: ${text.take(100)}... from $appPackage")
        
        // 更严格的文本过滤
        val isValidSelectedText = text.isNotEmpty() && 
            text.length > 10 &&
            !text.contains("输入问题或点击分析") &&
            !text.contains("请输入") &&
            !text.contains("点击") &&
            !text.contains("发送") &&
            !text.contains("取消") &&
            !text.contains("确定") &&
            !text.contains("设置") &&
            !text.contains("菜单")
        
        if (isValidSelectedText) {
            // 保存有效的选中文本
            Log.d(TAG, "📝📝📝 准备保存选中文本: ${text.take(100)}...")
            
            // 保存有效文本
            lastDetectedText = text
            Log.d(TAG, "✅ 有效选中文本已保存: ${text.take(50)}...")
            
            // 通知回调
            callbacks?.onValidTextSelected(text, appPackage, bookName, textSelectionBounds, lastSelectionPosition)
        } else {
            Log.d(TAG, "❌ 忽略无效的选中文本: $text")
            Log.d(TAG, "❌ 文本长度: ${text.length}")
            Log.d(TAG, "❌ 包含UI占位符: ${text.contains("输入问题或点击分析")}")
            
            // 通知回调文本无效
            callbacks?.onInvalidTextSelected(text)
        }
    }
    
    /**
     * 处理文本选择激活
     */
    fun handleTextSelectionActive() {
        Log.d(TAG, "🎯 文本选择激活")
        isTextSelectionActive = true
        
        // 通知回调
        callbacks?.onTextSelectionActive()
    }
    
    /**
     * 处理文本选择取消
     */
    fun handleTextSelectionInactive() {
        Log.d(TAG, "❌ 文本选择取消")
        isTextSelectionActive = false
        
        // 通知回调
        callbacks?.onTextSelectionInactive()
    }
    
    /**
     * 请求从辅助功能服务获取选中文本
     */
    fun requestSelectedTextFromAccessibilityService() {
        Log.d(TAG, "📤 请求获取选中文本")
        
        // 通知回调
        callbacks?.onRequestTextFromAccessibilityService()
    }
    
    /**
     * 清除选中文本
     */
    fun clearSelectedText() {
        lastDetectedText = ""
        textSelectionBounds = null
        lastSelectionPosition = null
    }
    
    /**
     * 获取最近选中的文本
     */
    fun getLastDetectedText(): String {
        return lastDetectedText
    }
    
    /**
     * 获取当前应用包名
     */
    fun getCurrentAppPackage(): String {
        return currentAppPackage
    }
    
    /**
     * 获取当前书籍名称
     */
    fun getCurrentBookName(): String {
        return currentBookName
    }
    
    /**
     * 获取文本选择边界
     */
    fun getTextSelectionBounds(): Rect? {
        return textSelectionBounds
    }
    
    /**
     * 获取文本选择位置
     */
    fun getTextSelectionPosition(): Pair<Int, Int>? {
        return lastSelectionPosition
    }
    
    /**
     * 是否有有效文本
     */
    fun hasValidText(): Boolean {
        return lastDetectedText.isNotEmpty() &&
               lastDetectedText.length > 10 &&
               !lastDetectedText.contains("输入问题或点击分析")
    }
    
    /**
     * 是否处于文本选择激活状态
     */
    fun isSelectionActive(): Boolean {
        return isTextSelectionActive
    }
    
    /**
     * 文本选择回调接口
     */
    interface TextSelectionCallbacks {
        fun onTextDetected(text: String, appPackage: String, bookName: String)
        fun onValidTextSelected(text: String, appPackage: String, bookName: String, 
                              bounds: Rect?, position: Pair<Int, Int>?)
        fun onInvalidTextSelected(text: String)
        fun onTextSelectionActive()
        fun onTextSelectionInactive()
        fun onRequestTextFromAccessibilityService()
    }
} 