# 贡献指南

感谢您对 ReadAssist 项目的关注！我们欢迎所有形式的贡献，包括但不限于：

- 🐛 Bug 报告
- 💡 功能建议
- 📝 文档改进
- 🔧 代码贡献
- 🌍 本地化翻译

## 🚀 快速开始

### 开发环境准备

1. **安装必要工具**
   - Android Studio Arctic Fox 或更高版本
   - JDK 17
   - Android SDK (API 21-34)
   - Git

2. **克隆项目**
   ```bash
   git clone https://github.com/James-Zhu-CA/ReadAssist.git
   cd ReadAssist
   ```

3. **导入项目**
   - 使用 Android Studio 打开项目
   - 等待 Gradle 同步完成
   - 连接 Android 设备或启动模拟器

### 项目结构了解

请先阅读以下文档：
- [README.md](README.md) - 项目概述
- [DEVELOPMENT.md](DEVELOPMENT.md) - 详细开发文档

## 📋 贡献流程

### 1. 创建 Issue

在开始编码之前，请先创建一个 Issue 来描述：
- 要修复的 Bug
- 要添加的功能
- 要改进的文档

### 2. Fork 和分支

```bash
# Fork 项目到您的 GitHub 账户
# 然后克隆您的 Fork

git clone https://github.com/James-Zhu-CA/ReadAssist.git
cd ReadAssist

# 创建功能分支
git checkout -b feature/your-feature-name
# 或者修复分支
git checkout -b fix/your-bug-fix
```

### 3. 开发和测试

- 遵循现有的代码风格
- 添加必要的注释和文档
- 确保代码在 Supernote 设备上正常工作
- 使用项目提供的调试工具进行测试

```bash
# 构建和安装
./build_and_install.sh

# 监听日志进行调试
./monitor_logs.sh
```

### 4. 提交更改

```bash
# 添加更改
git add .

# 提交更改（使用有意义的提交信息）
git commit -m "feat: 添加新的文本过滤规则"

# 推送到您的 Fork
git push origin feature/your-feature-name
```

### 5. 创建 Pull Request

1. 在 GitHub 上创建 Pull Request
2. 填写 PR 模板中的所有必要信息
3. 等待代码审查和反馈

## 📝 代码规范

### Kotlin 代码风格

- 使用 4 个空格缩进
- 类名使用 PascalCase
- 函数和变量名使用 camelCase
- 常量使用 UPPER_SNAKE_CASE
- 遵循 [Kotlin 官方编码规范](https://kotlinlang.org/docs/coding-conventions.html)

### 注释规范

```kotlin
/**
 * 文本提取服务
 * 
 * 负责从辅助功能事件中提取和过滤文本内容
 * 支持多种应用的文本选择检测
 */
class TextAccessibilityService : AccessibilityService() {
    
    /**
     * 处理 Supernote 应用的内容变化事件
     * 
     * @param event 辅助功能事件
     * @return 是否成功处理事件
     */
    private fun handleSupernoteContentChange(event: AccessibilityEvent): Boolean {
        // 实现逻辑...
    }
}
```

### 日志规范

使用统一的 emoji 标识符：

```kotlin
Log.d(TAG, "🚀🚀🚀 服务启动")
Log.d(TAG, "🔥🔥🔥 Supernote事件: ${event.eventType}")
Log.d(TAG, "📝📝📝 提取文本: $text")
Log.e(TAG, "❌❌❌ 错误: ${e.message}")
```

## 🧪 测试指南

### 单元测试

```bash
# 运行单元测试
./gradlew test
```

### 集成测试

在真实设备上测试：

1. **Supernote A5 X2** - 主要目标设备
2. **其他 Android 设备** - 兼容性测试
3. **不同 Android 版本** - API 21-34

### 测试检查清单

- [ ] 文本选择检测正常工作
- [ ] 悬浮窗显示和交互正常
- [ ] AI 分析功能正常
- [ ] 权限请求流程正常
- [ ] 设置保存和读取正常
- [ ] 历史记录功能正常

## 🐛 Bug 报告

请使用以下模板报告 Bug：

```markdown
## Bug 描述
简要描述遇到的问题

## 复现步骤
1. 打开应用
2. 执行某个操作
3. 观察到的问题

## 预期行为
描述您期望发生的情况

## 实际行为
描述实际发生的情况

## 环境信息
- 设备型号：
- Android 版本：
- 应用版本：
- 其他相关信息：

## 日志信息
如果可能，请提供相关的日志输出
```

## 💡 功能建议

请使用以下模板提出功能建议：

```markdown
## 功能描述
简要描述建议的功能

## 使用场景
描述这个功能的使用场景和价值

## 实现建议
如果有想法，可以描述可能的实现方式

## 替代方案
是否有其他可行的解决方案
```

## 📚 文档贡献

文档改进同样重要：

- 修正错别字和语法错误
- 改进说明的清晰度
- 添加使用示例
- 翻译成其他语言

## 🌍 本地化

我们欢迎将应用翻译成其他语言：

1. 复制 `app/src/main/res/values/strings.xml`
2. 创建对应语言的目录（如 `values-en` 用于英语）
3. 翻译所有字符串资源
4. 测试翻译后的界面

## 📞 联系方式

如果您有任何问题或建议，可以通过以下方式联系我们：

- 创建 [GitHub Issue](https://github.com/James-Zhu-CA/ReadAssist/issues)
- 参与 [GitHub Discussions](https://github.com/James-Zhu-CA/ReadAssist/discussions)

## 🙏 致谢

感谢所有为 ReadAssist 项目做出贡献的开发者！

您的贡献将被记录在项目的贡献者列表中。 