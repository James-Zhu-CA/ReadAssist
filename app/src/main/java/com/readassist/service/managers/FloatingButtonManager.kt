package com.readassist.service.managers

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.content.res.ColorStateList
import com.readassist.R
import com.readassist.ReadAssistApplication
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 管理悬浮按钮的创建、显示、交互和状态
 */
class FloatingButtonManager(
    private val context: Context,
    private val windowManager: WindowManager,
    private val preferenceManager: com.readassist.utils.PreferenceManager,
    private val callbacks: FloatingButtonCallbacks
) {
    companion object {
        private const val TAG = "FloatingButtonManager"
        private const val FLOATING_BUTTON_SIZE = 45 // dp
    }
    
    /**
     * 悬浮按钮回调接口
     */
    interface FloatingButtonCallbacks {
        fun onFloatingButtonClick()
    }
    
    // 视图和布局参数
    private var floatingButton: View? = null
    private var floatingButtonParams: WindowManager.LayoutParams? = null
    
    // 状态变量
    private var isButtonMoved = false
    private var isButtonAtEdge = true
    private var edgeButtonX = 0
    private var edgeButtonY = 0
    private var originalButtonX = 0
    private var originalButtonY = 0
    
    /**
     * 创建并显示悬浮按钮
     */
    fun createButton() {
        Log.e(TAG, "createButton called")
        if (floatingButton != null) return
        
        try {
            // 判断是否为掌阅设备，动态调整按钮尺寸
            val isIReader = com.readassist.utils.DeviceUtils.isIReaderDevice()
            val sizeDp = if (isIReader) (FLOATING_BUTTON_SIZE * 0.6f).toInt() else FLOATING_BUTTON_SIZE

            // 创建按钮视图
            floatingButton = LayoutInflater.from(context).inflate(R.layout.floating_button, null)
            Log.e(TAG, "floatingButton inflated")
            
            // 动态设置按钮宽高（防止布局文件覆盖）
            floatingButton?.let { btn ->
                // 创建新的布局参数
                val params = android.widget.FrameLayout.LayoutParams(
                    dpToPx(sizeDp),
                    dpToPx(sizeDp)
                )
                btn.layoutParams = params
            }
            
            // 设置透明度为1.0（完全不透明，透明度由XML背景控制）
            floatingButton?.alpha = 1.0f
            
            // 设置按钮点击事件
            floatingButton?.setOnClickListener {
                Log.e(TAG, "Floating button clicked")
                callbacks.onFloatingButtonClick()
            }
            Log.e(TAG, "setOnClickListener set")
            
            // 设置按钮拖拽功能
            setupButtonDrag()
            Log.e(TAG, "setupButtonDrag called")
            
            // 创建布局参数
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.e(TAG, "Using TYPE_APPLICATION_OVERLAY for window type")
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                Log.e(TAG, "Using TYPE_PHONE for window type")
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            
            floatingButtonParams = WindowManager.LayoutParams(
                dpToPx(sizeDp),
                dpToPx(sizeDp),
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                
                val displayMetrics = context.resources.displayMetrics
                
                // 计算边缘位置（屏幕右侧边缘中间，一半在屏幕外）
                edgeButtonX = displayMetrics.widthPixels - dpToPx(sizeDp / 2)
                edgeButtonY = displayMetrics.heightPixels / 2 - dpToPx(sizeDp / 2)
                
                // 从偏好设置恢复位置
                val savedX = preferenceManager.getFloatingButtonX()
                val savedY = preferenceManager.getFloatingButtonY()
                
                if (savedX >= 0 && savedY >= 0) {
                    // 有保存的位置，使用保存的位置
                    x = savedX
                    y = savedY
                    
                    // 判断保存的位置是否为边缘位置
                    val isAtEdgePosition = (savedX == edgeButtonX && savedY == edgeButtonY)
                    isButtonAtEdge = isAtEdgePosition
                    isButtonMoved = !isAtEdgePosition
                    
                    Log.e(TAG, "📍 恢复保存位置: ($savedX, $savedY), 是否在边缘: $isButtonAtEdge")
                } else {
                    // 没有保存位置，使用默认边缘位置
                    x = edgeButtonX
                    y = edgeButtonY
                    isButtonAtEdge = true
                    isButtonMoved = false
                    
                    Log.e(TAG, "📍 使用默认边缘位置: ($x, $y)")
                }
                
                // 保存原始位置（用于其他逻辑）
                originalButtonX = x
                originalButtonY = y
            }
            
            // 添加到窗口管理器
            windowManager.addView(floatingButton, floatingButtonParams)
            Log.e(TAG, "addView success, Floating button created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create floating button", e)
        }
    }
    
    /**
     * 移除悬浮按钮
     */
    fun removeButton() {
        floatingButton?.let { button ->
            try {
                windowManager.removeView(button)
                floatingButton = null
                floatingButtonParams = null
                Log.e(TAG, "Floating button removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove floating button", e)
            }
        }
    }
    
    /**
     * 设置按钮拖拽功能
     */
    private fun setupButtonDrag() {
        Log.e(TAG, "setupButtonDrag entry")
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var lastTapTime = 0L
        var touchStartTime = 0L
        
        floatingButton?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 记录初始位置和触摸坐标
                    initialX = floatingButtonParams?.x ?: 0
                    initialY = floatingButtonParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    
                    // 立即显示按下状态反馈
                    v.animate()
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .alpha(1.0f)
                        .setDuration(100)
                        .start()
                    
                    true
                }
                
                MotionEvent.ACTION_MOVE -> {
                    // 计算移动距离
                    val moveDistance = sqrt(
                        (event.rawX - initialTouchX).toDouble().pow(2.0) +
                        (event.rawY - initialTouchY).toDouble().pow(2.0)
                    )
                    
                    // 如果移动距离超过阈值，视为拖拽
                    if (moveDistance > 10) {
                        val newX = initialX + (event.rawX - initialTouchX).toInt()
                        val newY = initialY + (event.rawY - initialTouchY).toInt()
                        
                        floatingButtonParams?.apply {
                            x = newX
                            y = newY
                        }
                        
                        try {
                            windowManager.updateViewLayout(floatingButton, floatingButtonParams)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to update floating button position", e)
                        }
                    }
                    true
                }
                
                MotionEvent.ACTION_UP -> {
                    // 恢复按钮视觉状态
                    v.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .alpha(1.0f)  // 完全不透明，透明度由XML背景控制
                        .setDuration(150)
                        .start()
                    
                    // 计算触摸持续时间
                    val touchDuration = System.currentTimeMillis() - touchStartTime
                    
                    // 计算移动距离
                    val moveDistance = sqrt(
                        (event.rawX - initialTouchX).toDouble().pow(2.0) +
                        (event.rawY - initialTouchY).toDouble().pow(2.0)
                    )
                    
                    if (moveDistance < 10 && touchDuration < 300) {
                        // 检测双击
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastTapTime < 300) {
                            // 双击：触发一次节点快照采集广播
                            lastTapTime = 0
                            try {
                                val intent = android.content.Intent("com.readassist.DEBUG_CAPTURE_UI_TREE")
                                intent.setPackage(context.packageName)
                                context.sendBroadcast(intent)
                                Log.e(TAG, "DEBUG_CAPTURE_UI_TREE broadcast sent")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send DEBUG_CAPTURE_UI_TREE", e)
                            }
                        } else {
                            // 单击
                            lastTapTime = currentTime
                            v.performClick()
                        }
                    } else if (moveDistance >= 10) {
                        // 用户进行了拖拽操作，更新按钮状态
                        Log.e(TAG, "📍 用户拖拽了按钮，更新位置状态")
                        
                        // 保存新位置到偏好设置
                        floatingButtonParams?.let { params ->
                            preferenceManager.setFloatingButtonPosition(params.x, params.y)
                            Log.e(TAG, "📍 保存拖拽后位置: (${params.x}, ${params.y})")
                        }
                        
                        // 更新按钮状态：不再在边缘
                        isButtonAtEdge = false
                        isButtonMoved = true
                        
                        Log.e(TAG, "📍 按钮状态已更新: isButtonAtEdge=$isButtonAtEdge, isButtonMoved=$isButtonMoved")
                    }
                    true
                }
                
                else -> false
            }
        }
    }
    
    /**
     * 显示截屏分析状态
     */
    fun showScreenshotAnalysisState() {
        try {
            // 更新悬浮按钮外观，显示分析状态
            floatingButton?.apply {
                // 移除所有动画效果，直接设置状态
                // 不使用缩放动画
                scaleX = 1.0f
                scaleY = 1.0f
                alpha = 1.0f // 完全不透明，透明度由XML背景控制
                
                // 不再设置背景色，使用XML中定义的白色背景和圆圈边框
                if (this is Button) {
                    text = "AI"
                }
                
                // 尝试产生振动反馈（如果有振动权限）
                if (context.checkSelfPermission(android.Manifest.permission.VIBRATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val vibrator = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(50)
                    }
                }
                
                Log.e(TAG, "📸 截屏分析指示器已显示")
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示截屏分析指示器失败", e)
        }
    }
    
    /**
     * 恢复按钮默认状态
     */
    fun restoreDefaultState() {
        try {
            floatingButton?.apply {
                // 先确保视图可见
                if (visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                }
                
                // 移除动画效果，直接设置状态
                scaleX = 1.0f
                scaleY = 1.0f
                alpha = 1.0f
                
                // 不再设置背景色，使用XML中定义的白色背景和圆圈边框
                if (this is Button) {
                    text = "AI"
                }
                
                Log.e(TAG, "🔄 按钮已恢复默认状态")
            }
        } catch (e: Exception) {
            Log.e(TAG, "恢复按钮默认状态失败", e)
        }
    }
    
    /**
     * 设置按钮可见性
     */
    fun setButtonVisibility(visible: Boolean) {
        try {
            // 仅改变可见性，不要移除或重新创建按钮
            floatingButton?.let { button ->
                if (visible) {
                        Log.e(TAG, "设置按钮可见")
                        button.visibility = View.VISIBLE
                    // 总是恢复默认样式，确保按钮状态正确
                        restoreDefaultState()
                } else {
                        Log.e(TAG, "设置按钮不可见")
                        button.visibility = View.INVISIBLE // 使用INVISIBLE而不是GONE，保留布局位置
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "设置按钮可见性失败", e)
        }
    }
    
    /**
     * 移动按钮到选择区域
     */
    fun moveToSelectionArea(selectionPosition: Pair<Int, Int>?) {
        if (floatingButtonParams == null || floatingButton == null) return
        
        // 只有在边缘状态时才移动按钮
        if (!isButtonAtEdge) {
            Log.e(TAG, "📍 按钮不在边缘状态，跳过移动到选择区域")
            return
        }
        
        try {
            val displayMetrics = context.resources.displayMetrics
            
            val newX: Int
            val newY: Int
            
            if (selectionPosition != null) {
                // 如果能获取到选择位置，在选择区域附近显示按钮
                newX = (selectionPosition.first + dpToPx(60)).coerceIn(
                    dpToPx(60), 
                    displayMetrics.widthPixels - dpToPx(60)
                )
                newY = (selectionPosition.second - dpToPx(30)).coerceIn(
                    dpToPx(60), 
                    displayMetrics.heightPixels - dpToPx(60)
                )
                Log.e(TAG, "📍 根据选择位置移动按钮: 选择位置(${selectionPosition.first}, ${selectionPosition.second}) -> 按钮位置($newX, $newY)")
            } else {
                // 如果无法获取选择位置，移动到屏幕中心偏右
                newX = (displayMetrics.widthPixels * 0.75).toInt()
                newY = (displayMetrics.heightPixels * 0.4).toInt()
                Log.e(TAG, "📍 使用默认位置移动按钮: ($newX, $newY)")
            }
            
            floatingButtonParams?.apply {
                x = newX
                y = newY
            }
            
            windowManager.updateViewLayout(floatingButton, floatingButtonParams)
            isButtonMoved = true
            isButtonAtEdge = false
            
            Log.e(TAG, "📍 按钮已移动到选择区域: ($newX, $newY)")
        } catch (e: Exception) {
            Log.e(TAG, "移动按钮失败", e)
        }
    }
    
    /**
     * 恢复按钮到原始边缘位置
     */
    fun restoreToEdge() {
        if (floatingButtonParams == null || floatingButton == null) return
        
        // 只有在按钮被移动过的情况下才恢复
        if (!isButtonMoved) {
            Log.e(TAG, "📍 按钮未被移动，无需恢复")
            return
        }
        
        try {
            floatingButtonParams?.apply {
                x = edgeButtonX
                y = edgeButtonY
            }
            
            windowManager.updateViewLayout(floatingButton, floatingButtonParams)
            isButtonMoved = false
            isButtonAtEdge = true
            
            Log.e(TAG, "📍 按钮已恢复到边缘位置: ($edgeButtonX, $edgeButtonY)")
        } catch (e: Exception) {
            Log.e(TAG, "恢复按钮位置失败", e)
        }
    }
    
    /**
     * 更新按钮外观以指示选择状态
     */
    fun updateAppearanceForSelection(isSelectionMode: Boolean) {
        floatingButton?.let { button ->
            if (isSelectionMode) {
                // 选择模式：移除动画效果，直接设置状态
                button.alpha = 1.0f // 完全不透明，透明度由XML背景控制
                button.scaleX = 1.0f
                button.scaleY = 1.0f
                
                // 不再设置背景色，使用XML中定义的白色背景和圆圈边框
                
                Log.e(TAG, "🎨 按钮外观已更新为选择模式")
            } else {
                // 普通模式：移除动画效果，直接设置状态
                button.alpha = 1.0f // 完全不透明，透明度由XML背景控制
                button.scaleX = 1.0f
                button.scaleY = 1.0f
                
                // 不再设置背景色，使用XML中定义的白色背景和圆圈边框
                
                Log.e(TAG, "🎨 按钮外观已恢复为普通模式")
            }
        }
    }
    
    /**
     * dp转px
     */
    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
    
    /**
     * 获取按钮是否在边缘
     */
    fun isAtEdge(): Boolean = isButtonAtEdge
    
    /**
     * 获取按钮是否已移动
     */
    fun isMoved(): Boolean = isButtonMoved
} 