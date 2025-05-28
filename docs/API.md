# API 文档

ReadAssist 主要通过 Google Gemini API 提供 AI 分析功能。本文档描述了 API 的使用方法和配置。

## Google Gemini API

### 获取 API Key

1. 访问 [Google AI Studio](https://makersuite.google.com/app/apikey)
2. 登录您的 Google 账户
3. 创建新的 API Key
4. 复制 API Key 到 ReadAssist 设置中

### API 配置

在 ReadAssist 应用中：

1. 打开设置页面
2. 找到 "Gemini API Key" 选项
3. 输入您的 API Key
4. 保存设置

### 支持的模型

ReadAssist 目前使用以下 Gemini 模型：

- **gemini-pro**: 用于文本分析和对话
- **gemini-pro-vision**: 用于图像分析（未来功能）

### API 限制

#### 免费配额
- 每分钟 60 次请求
- 每天 1,500 次请求
- 每次请求最大 30,000 字符

#### 付费配额
- 根据 Google 的定价策略
- 更高的请求频率限制
- 更大的文本长度支持

### 请求格式

ReadAssist 发送到 Gemini API 的请求格式：

```json
{
  "contents": [
    {
      "parts": [
        {
          "text": "分析以下文本内容：[用户选中的文本]"
        }
      ]
    }
  ],
  "generationConfig": {
    "temperature": 0.7,
    "topK": 40,
    "topP": 0.95,
    "maxOutputTokens": 1024
  }
}
```

### 响应处理

API 响应的处理流程：

1. **成功响应**: 解析 AI 生成的文本并显示
2. **错误响应**: 根据错误类型显示相应提示
3. **网络错误**: 显示网络连接失败提示

### 错误处理

常见错误及处理方式：

| 错误代码 | 描述 | 处理方式 |
|---------|------|---------|
| 400 | 请求格式错误 | 检查文本格式和长度 |
| 401 | API Key 无效 | 提示用户检查 API Key |
| 403 | 权限不足 | 检查 API Key 权限 |
| 429 | 请求频率超限 | 显示频率限制提示 |
| 500 | 服务器错误 | 提示稍后重试 |

## 内部 API

ReadAssist 内部组件间的通信接口。

### 服务间通信

#### TextAccessibilityService → FloatingWindowService

```kotlin
// 发送文本选择事件
val intent = Intent("com.readassist.TEXT_SELECTED")
intent.putExtra("selected_text", text)
intent.putExtra("source_app", packageName)
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
```

#### FloatingWindowService → TextAccessibilityService

```kotlin
// 请求当前选中文本
val intent = Intent("com.readassist.REQUEST_SELECTED_TEXT")
LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
```

### 数据库 API

#### 聊天记录操作

```kotlin
// 插入聊天消息
suspend fun insertMessage(message: ChatMessage): Long

// 获取会话历史
suspend fun getSessionMessages(sessionId: String): List<ChatMessage>

// 删除会话
suspend fun deleteSession(sessionId: String)

// 搜索消息
suspend fun searchMessages(query: String): List<ChatMessage>
```

#### 设置管理

```kotlin
// 保存设置
fun saveApiKey(apiKey: String)
fun savePromptTemplate(template: String)
fun saveAutoAnalyze(enabled: Boolean)

// 读取设置
fun getApiKey(): String?
fun getPromptTemplate(): String
fun isAutoAnalyzeEnabled(): Boolean
```

## 自定义提示模板

### 模板语法

用户可以自定义 AI 分析的提示词模板：

```
请分析以下文本内容：

[TEXT]

请从以下角度进行分析：
1. 主要观点
2. 关键信息
3. 相关背景
4. 个人见解
```

### 变量替换

- `[TEXT]`: 用户选中的文本内容
- `[APP]`: 来源应用名称
- `[TIME]`: 当前时间
- `[LANG]`: 系统语言

### 预设模板

ReadAssist 提供几种预设模板：

1. **通用分析**: 全面分析文本内容
2. **学术研究**: 适合学术文献分析
3. **新闻解读**: 适合新闻文章分析
4. **文学鉴赏**: 适合文学作品分析

## 隐私和安全

### 数据处理

- 所有文本分析都通过 HTTPS 加密传输
- 不存储用户的 API Key 明文
- 本地聊天记录使用加密存储

### 数据保留

- 聊天记录仅存储在本地设备
- 用户可以随时清除所有数据
- 不向第三方分享用户数据

### API Key 安全

- API Key 使用 Android Keystore 加密存储
- 不在日志中记录 API Key
- 支持 API Key 的安全删除

## 故障排除

### 常见问题

1. **API Key 无效**
   - 检查 API Key 是否正确复制
   - 确认 API Key 权限设置
   - 尝试重新生成 API Key

2. **网络连接失败**
   - 检查设备网络连接
   - 确认防火墙设置
   - 尝试切换网络环境

3. **请求频率超限**
   - 等待一段时间后重试
   - 考虑升级到付费配额
   - 减少分析频率

### 调试模式

开发者可以启用调试模式查看详细的 API 交互信息：

```bash
# 监听网络请求日志
./monitor_logs.sh network

# 查看 API 响应
adb logcat | grep "GeminiAPI"
```

## 更新和维护

### API 版本管理

ReadAssist 会跟随 Google Gemini API 的更新：

- 自动适配新的 API 版本
- 保持向后兼容性
- 及时修复 API 变更导致的问题

### 功能扩展

未来计划支持的 API 功能：

- 多模态分析（文本+图像）
- 流式响应
- 自定义模型微调
- 批量处理接口 