# ReadAssist 开发者指南

## 架构概述

经过v1.1.0的重构，ReadAssist应用采用了模块化架构设计，核心服务`FloatingWindowServiceNew`作为协调者，通过多个专业化的管理器类实现各项功能。本指南将帮助开发者理解这一架构，并指导如何进行开发和维护工作。

## 项目结构

```
app/src/main/java/com/readassist/
├── service/
│   ├── FloatingWindowServiceNew.kt    // 核心协调服务
│   ├── FloatingWindowService.kt       // 旧服务（将逐步淘汰）
│   ├── ScreenshotService.kt           // 截屏服务
│   ├── TextAccessibilityService.kt    // 辅助功能服务
│   ├── ChatItem.kt                    // 聊天项数据类
│   ├── managers/                      // 管理器类目录
│   │   ├── FloatingButtonManager.kt   // 悬浮按钮管理
│   │   ├── ChatWindowManager.kt       // 聊天窗口管理
│   │   ├── SessionManager.kt          // 会话管理
│   │   ├── ScreenshotManager.kt       // 截屏功能管理
│   │   ├── AiCommunicationManager.kt  // AI通信管理
│   │   ├── AiConfigurationManager.kt  // AI配置管理
│   │   └── TextSelectionManager.kt    // 文本选择管理
├── ui/                                // 用户界面
├── repository/                        // 数据仓库
├── database/                          // 本地数据库
├── network/                           // 网络通信
├── model/                             // 数据模型
└── utils/                             // 工具类
```

## 开发指南

### 1. 修改现有功能

#### 确定修改范围

首先确定你需要修改的功能属于哪个管理器的职责范围：

- **悬浮按钮相关**：修改 `FloatingButtonManager`
- **聊天窗口相关**：修改 `ChatWindowManager`
- **会话管理相关**：修改 `SessionManager`
- **截屏功能相关**：修改 `ScreenshotManager`
- **AI通信相关**：修改 `AiCommunicationManager`
- **AI配置相关**：修改 `AiConfigurationManager`
- **文本选择相关**：修改 `TextSelectionManager`

#### 修改流程

1. 找到相应的管理器类
2. 理解其中的方法和职责
3. 进行必要的修改
4. 如果修改影响了管理器的接口，可能需要更新 `FloatingWindowServiceNew` 中的相应代码

**示例：修改悬浮按钮的外观**

```kotlin
// 在FloatingButtonManager中
fun updateButtonAppearance(color: Int) {
    floatingButton?.apply {
        setBackgroundTintList(ColorStateList.valueOf(color))
        // 其他外观调整
    }
}

// 在FloatingWindowServiceNew中调用
floatingButtonManager.updateButtonAppearance(Color.BLUE)
```

### 2. 添加新功能

#### 在现有管理器中添加

如果新功能属于某个现有管理器的职责范围，直接在该管理器中添加方法：

1. 在相应管理器中添加新方法
2. 如果需要与服务通信，考虑添加或使用现有回调
3. 在 `FloatingWindowServiceNew` 中调用新方法或处理新回调

**示例：在ChatWindowManager中添加新消息类型**

```kotlin
// 在ChatWindowManager中
fun addSystemAlertMessage(message: String) {
    val alertItem = ChatItem("", "⚠️ $message", false, false, false)
    chatAdapter?.addItem(alertItem)
    scrollToBottom()
}

// 在FloatingWindowServiceNew中调用
chatWindowManager.addSystemAlertMessage("网络连接不稳定")
```

#### 创建新的管理器

如果新功能不属于任何现有管理器的职责范围，可以创建新的管理器类：

1. 在 `managers` 目录下创建新的管理器类
2. 定义必要的接口和回调
3. 在 `FloatingWindowServiceNew` 中初始化和使用新管理器

**示例：创建语音输入管理器**

```kotlin
// 创建VoiceInputManager.kt
class VoiceInputManager(
    private val context: Context,
    private val callbacks: VoiceInputCallbacks
) {
    // 实现语音输入功能
    
    interface VoiceInputCallbacks {
        fun onVoiceInputResult(text: String)
        fun onVoiceInputError(error: String)
    }
}

// 在FloatingWindowServiceNew中
// 1. 添加接口实现
class FloatingWindowServiceNew : Service(), 
    VoiceInputManager.VoiceInputCallbacks {
    
    // 实现回调方法
    override fun onVoiceInputResult(text: String) {
        // 处理语音输入结果
    }
    
    override fun onVoiceInputError(error: String) {
        // 处理语音输入错误
    }
}

// 2. 初始化管理器
private fun initializeManagers() {
    // 其他管理器初始化...
    
    voiceInputManager = VoiceInputManager(this, this)
}
```

### 3. 模块间通信

模块间通信主要通过以下几种方式：

#### 1. 回调接口

最常用的通信方式，管理器通过回调接口通知服务类事件发生：

```kotlin
// 定义回调接口
interface MyManagerCallbacks {
    fun onSomethingHappened(data: String)
}

// 管理器中使用回调
class MyManager(private val callbacks: MyManagerCallbacks) {
    fun doSomething() {
        // 处理后通知服务
        callbacks.onSomethingHappened("结果数据")
    }
}

// 服务类实现回调
class FloatingWindowServiceNew : Service(), MyManager.MyManagerCallbacks {
    override fun onSomethingHappened(data: String) {
        // 处理回调事件
    }
}
```

#### 2. 直接方法调用

服务类可以直接调用管理器的公共方法：

```kotlin
// 在服务类中
private fun handleUserAction() {
    chatWindowManager.showChatWindow()
    aiCommunicationManager.sendMessage("Hello")
}
```

#### 3. 广播

对于跨组件或跨进程通信，使用LocalBroadcastManager：

```kotlin
// 发送广播
val intent = Intent("com.readassist.ACTION_NAME")
intent.putExtra("key", "value")
LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

// 接收广播
val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // 处理广播
    }
}
LocalBroadcastManager.getInstance(context).registerReceiver(receiver, IntentFilter("com.readassist.ACTION_NAME"))
```

### 4. 测试指南

#### 单元测试

每个管理器类都应该有相应的单元测试：

```kotlin
@Test
fun testSessionManagerCreateSession() {
    val repository = mockk<ChatRepository>()
    val sessionManager = SessionManager(repository)
    
    val sessionId = sessionManager.generateSessionId("com.example.app", "Test Book")
    
    assertThat(sessionId).contains("com.example.app")
    assertThat(sessionId).contains("Test Book")
}
```

#### 集成测试

测试管理器之间的交互：

```kotlin
@Test
fun testScreenshotAndTextSelection() {
    // 设置模拟管理器
    val screenshotManager = mockk<ScreenshotManager>()
    val textSelectionManager = mockk<TextSelectionManager>()
    
    // 配置行为
    every { screenshotManager.performScreenshot() } just Runs
    every { textSelectionManager.getTextSelectionBounds() } returns Rect(0, 0, 100, 100)
    
    // 执行测试
    service.onScreenshotButtonClick()
    
    // 验证交互
    verify { screenshotManager.performScreenshot() }
    verify { textSelectionManager.getTextSelectionBounds() }
}
```

#### UI测试

使用Espresso测试UI交互：

```kotlin
@Test
fun testChatWindowInteraction() {
    // 启动主活动
    ActivityScenario.launch(MainActivity::class.java)
    
    // 点击悬浮窗按钮
    onView(withId(R.id.btnFloatingWindow)).perform(click())
    
    // 等待服务启动并显示悬浮按钮
    Thread.sleep(1000)
    
    // 使用UiAutomator进行悬浮窗测试
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    val floatingButton = device.findObject(UiSelector().text("AI"))
    floatingButton.click()
    
    // 验证聊天窗口显示
    val chatWindow = device.findObject(UiSelector().resourceId("com.readassist:id/chatWindow"))
    assertTrue(chatWindow.exists())
}
```

### 5. 调试技巧

#### 日志标记

每个管理器类使用自己的TAG进行日志输出，便于过滤：

```kotlin
companion object {
    private const val TAG = "ChatWindowManager"
}

// 使用
Log.d(TAG, "ChatWindow created")
```

#### 状态检查

添加状态检查方法，便于调试：

```kotlin
fun dumpState(): String {
    return """
        FloatingButtonManager:
        - Button visible: ${floatingButton?.visibility == View.VISIBLE}
        - At edge: $isButtonAtEdge
        - Position: (${floatingButtonParams?.x ?: 0}, ${floatingButtonParams?.y ?: 0})
    """.trimIndent()
}
```

#### 断点技巧

在调试复杂流程时，为每个管理器设置断点，追踪事件流：

1. 在服务类的回调方法设置断点
2. 在管理器的关键方法设置断点
3. 使用条件断点过滤不需要的情况

## 最佳实践

### 1. 单一职责原则

每个管理器类只负责一项核心功能，不要在一个管理器中实现属于其他管理器职责范围的功能。

### 2. 接口隔离

为每个管理器定义最小必要的回调接口，不要在接口中包含不必要的方法。

### 3. 依赖注入

通过构造函数传入依赖，而不是在管理器内部创建：

```kotlin
// 推荐
class SessionManager(private val repository: ChatRepository)

// 不推荐
class SessionManager {
    private val repository = ChatRepository() // 直接创建依赖
}
```

### 4. 状态管理

每个管理器负责管理自己的状态，不要在服务类中维护属于管理器的状态：

```kotlin
// 在FloatingButtonManager中
private var isButtonVisible = false

fun setButtonVisibility(visible: Boolean) {
    isButtonVisible = visible
    floatingButton?.visibility = if (visible) View.VISIBLE else View.GONE
}

fun isButtonVisible(): Boolean = isButtonVisible
```

### 5. 错误处理

使用统一的错误处理模式：

```kotlin
fun performAction() {
    try {
        // 执行操作
    } catch (e: Exception) {
        Log.e(TAG, "操作失败", e)
        callbacks.onActionFailed("操作失败: ${e.message}")
    }
}
```

## 常见问题

### 1. 如何决定功能应该放在哪个管理器中？

遵循单一职责原则，根据功能的主要目的决定：
- 如果与UI展示有关，放在对应的UI管理器中
- 如果与数据处理有关，放在对应的数据管理器中
- 如果是全新的功能领域，考虑创建新的管理器

### 2. 管理器之间如何共享数据？

通过服务类作为中介：
1. 管理器A通过回调通知服务类数据已准备好
2. 服务类调用管理器B的方法，传入数据

### 3. 如何处理生命周期事件？

在服务类的生命周期方法中，调用各个管理器的相应方法：

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // 清理各个管理器
    floatingButtonManager.cleanup()
    chatWindowManager.cleanup()
    screenshotManager.cleanup()
    // 其他管理器清理
    
    // 最后清理服务自身
    serviceScope.cancel()
}
```

### 4. 如何添加新的交互流程？

1. 确定流程涉及哪些管理器
2. 在服务类中实现协调逻辑
3. 在各管理器中添加必要的方法

**示例：添加文本翻译功能**

```kotlin
// 1. 在AiCommunicationManager中添加翻译方法
fun translateText(text: String, targetLanguage: String): ApiResult<String> {
    // 实现翻译逻辑
}

// 2. 在ChatWindowManager中添加翻译UI
fun showTranslationOption(messagePosition: Int) {
    // 显示翻译选项
}

// 3. 在服务类中协调
fun handleTranslationRequest(text: String, language: String) {
    chatWindowManager.showLoading("正在翻译...")
    
    serviceScope.launch {
        val result = aiCommunicationManager.translateText(text, language)
        
        withContext(Dispatchers.Main) {
            chatWindowManager.hideLoading()
            
            when (result) {
                is ApiResult.Success -> chatWindowManager.showTranslation(result.data)
                is ApiResult.Error -> chatWindowManager.showError("翻译失败: ${result.exception.message}")
            }
        }
    }
}
```

## 后续开发计划

1. 完善单元测试覆盖
2. 添加性能监控
3. 支持更多AI平台
4. 优化用户体验
5. 添加更多功能：语音输入、文本翻译、图像识别等

---

文档编写：ReadAssist开发团队
日期：2024年7月13日
版本：v1.0 