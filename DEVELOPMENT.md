# ReadAssist 开发文档

## 项目概述

ReadAssist 是一个专为墨水屏设备（特别是 Supernote A5 X2）设计的智能阅读助手，提供文本选择、AI分析等功能。

## 快速开始

### 构建和安装
```bash
# 构建并安装应用
./build_and_install.sh
```

### 日志监听
```bash
# 监听文本选择相关事件（默认模式）
./monitor_logs.sh

# 监听所有应用事件
./monitor_logs.sh all

# 简化输出模式
./monitor_logs.sh simple
```

### 测试文本提取功能
```bash
# 运行文本提取测试
./test_text_extraction_fix.sh
```

## 核心功能

### 1. 文本选择检测

**支持的应用包名:**
- `com.supernote.document` - Supernote 文档阅读器（主要）
- `com.ratta.supernote.launcher` - Supernote 启动器
- `com.adobe.reader` - Adobe Reader
- `com.kingsoft.moffice_eng` - WPS Office

**检测机制:**
- 监听 `WINDOW_CONTENT_CHANGED` 事件
- 检测 `FrameLayout` 和 `LinearLayout` 作为选择弹窗指示器
- 监听 `TYPE_VIEW_TEXT_SELECTION_CHANGED` 事件
- 剪贴板变化监听

### 2. 文本提取策略

**三层提取策略:**
1. **真正选中的文本** - 查找有 `isSelected` 或 `isAccessibilityFocused` 状态的节点
2. **内容区域文本** - 从 `ImageView`、`TextView`、`WebView`、`ScrollView` 中提取
3. **备用方案** - 过滤所有文本后选择最佳候选

**文本过滤规则:**
- 最小长度要求：10字符（调试模式）
- 过滤UI占位符：输入提示、按钮文本等
- 过滤书籍元数据：书名、作者、出版信息等（可配置）
- 正文内容识别：基于语法特征和结构分析

### 3. 悬浮按钮响应

**选择模式:**
- 按钮移动到屏幕右上角 (75%, 40%)
- 外观变化：透明度90%，放大1.1倍
- 提供视觉反馈

**取消选择:**
- 自动检测选择取消事件
- 按钮恢复到原始位置
- 外观恢复正常

## 调试指南

### 文本选择问题排查

**问题1: 检测不到文本选择**
```bash
# 使用 all 模式监听所有事件
./monitor_logs.sh all

# 查看是否有 Supernote 事件触发
# 关注: 🔥🔥🔥 Supernote事件
```

**问题2: 提取到错误文本（元数据而非正文）**
```bash
# 使用 text 模式专注文本提取
./monitor_logs.sh text

# 关注日志:
# 📊📊📊 文本候选 - 查看原始和过滤后的候选
# 📝📝📝 事件文本 - 查看事件中的文本内容
```

**问题3: 过滤后文本候选为0**
- 检查 `isValidText()` 方法的过滤条件
- 临时放宽过滤条件进行调试
- 查看原始文本候选的内容和长度

### 常见日志标识

| 标识 | 含义 |
|------|------|
| 🚀🚀🚀 | 服务启动和连接 |
| 🔥🔥🔥 | Supernote 特定事件 |
| 📊📊📊 | 文本候选信息 |
| 📝📝📝 | 实际文本内容 |
| 🎯🎯🎯 | 文本选择状态 |
| 📋📋📋 | 剪贴板事件 |
| 🔍🔍🔍 | 文本过滤过程 |
| 👆👆👆 | 用户交互 |
| 🎈🎈🎈 | 悬浮窗操作 |
| ❌❌❌ | 错误和警告 |

## 技术架构

### 核心服务

**TextAccessibilityService**
- 辅助功能服务，监听系统事件
- 文本提取和选择检测
- 与悬浮窗服务通信

**FloatingWindowService**
- 悬浮按钮管理
- 聊天窗口显示
- 用户交互处理

### 关键方法

**文本提取相关:**
- `findAllPossibleSelectedText()` - 分层文本提取
- `findActualSelectedText()` - 查找选中状态的文本
- `findContentAreaText()` - 查找内容区域文本
- `isValidText()` - 文本有效性验证
- `isBookMetadata()` - 书籍元数据识别
- `isContentText()` - 正文内容识别

**事件处理相关:**
- `handleSupernoteContentChange()` - Supernote 内容变化处理
- `checkForSupernoteTextSelectionPopup()` - 选择弹窗检测
- `extractTextFromSupernoteSelection()` - Supernote 文本提取

## 配置和优化

### 调试模式配置

在 `TextAccessibilityService.kt` 中：
```kotlin
// 降低最小长度要求便于调试
text.length > 10 && // 调试模式

// 注释掉严格的元数据过滤
// !text.contains("Homo deus") && // 过滤书籍元数据
```

### 性能优化

- 使用节点回收避免内存泄漏
- 延迟检查减少CPU占用
- 智能过滤减少无效处理

## 已知问题和解决方案

### 问题1: Java版本兼容性
**现象:** 构建失败，提示需要Java 17
**解决:** 在构建脚本中设置正确的JAVA_HOME

### 问题2: Supernote应用启动权限
**现象:** 无法自动启动Supernote应用
**解决:** 手动打开应用，或使用多种启动方式尝试

### 问题3: 文本过滤过于严格
**现象:** 过滤后文本候选为0个
**解决:** 调整过滤条件，增加调试日志观察原始候选

## 开发规范

### 脚本管理
- 使用统一的脚本命名：`功能_用途.sh`
- 分离构建、安装、监听功能
- 避免重复创建相似脚本

### 日志规范
- 使用统一的emoji标识符
- 重要事件使用三重emoji
- 包含时间戳便于追踪

### 文档管理
- 统一使用 `DEVELOPMENT.md` 记录开发信息
- 避免创建多个分散的说明文档
- 及时更新功能变更和问题解决方案

## 贡献指南

1. 遵循现有的代码风格和命名规范
2. 添加适当的日志输出便于调试
3. 更新相关文档说明
4. 测试在 Supernote A5 X2 设备上的兼容性 