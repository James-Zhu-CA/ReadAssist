# ReadAssist - 智能阅读助手

<div align="center">
  <img src="app/src/main/res/drawable/ic_launcher.png" alt="ReadAssist Logo" width="120" height="120">
  
  <h3>专为墨水屏设备设计的AI阅读助手</h3>
  
  [![Android](https://img.shields.io/badge/Android-5.0%2B-green.svg)](https://android.com)
  [![Kotlin](https://img.shields.io/badge/Kotlin-1.8-blue.svg)](https://kotlinlang.org)
  [![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
  [![API](https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=21)
</div>

## 📖 项目简介

ReadAssist 是一个专为墨水屏设备（特别是 **Supernote A5 X2**）设计的智能阅读助手应用。通过先进的文本识别技术和AI分析能力，为用户提供无缝的阅读体验和智能内容解析。

### ✨ 核心特性

- 🎯 **智能文本选择检测** - 自动识别用户在阅读应用中的文本选择操作
- 🤖 **AI内容分析** - 集成Google Gemini API，提供智能文本分析和解释
- 🎈 **悬浮窗交互** - 优雅的悬浮按钮和聊天窗口，不干扰阅读体验
- 📱 **墨水屏优化** - 专为E-ink显示屏优化的UI设计和交互逻辑
- 🔧 **辅助功能服务** - 利用Android辅助功能API实现无侵入式文本获取
- 💾 **本地数据存储** - 聊天记录本地保存，支持历史查询和导出

### 🎯 支持的应用

- **Supernote Document** (`com.supernote.document`) - 主要支持
- **Supernote Launcher** (`com.ratta.supernote.launcher`)
- **Adobe Reader** (`com.adobe.reader`)
- **WPS Office** (`com.kingsoft.moffice_eng`)

## 🚀 快速开始

### 系统要求

- Android 5.0 (API 21) 或更高版本
- 推荐设备：Supernote A5 X2 或其他墨水屏平板
- 网络连接（用于AI分析功能）

### 安装方式

#### 方式一：直接安装APK
1. 从 [Releases](https://github.com/James-Zhu-CA/ReadAssist/releases) 页面下载最新版本APK
2. 在设备上启用"未知来源"安装
3. 安装APK文件

#### 方式二：从源码构建
```bash
# 克隆项目
git clone https://github.com/James-Zhu-CA/ReadAssist.git
cd ReadAssist

# 构建并安装（需要连接Android设备）
./build_and_install.sh
```

### 初始配置

1. **授予权限**
   - 悬浮窗权限：用于显示AI助手界面
   - 辅助功能权限：用于获取文本内容
   - 网络权限：用于AI API调用

2. **设置API Key**
   - 获取 [Google Gemini API Key](https://makersuite.google.com/app/apikey)
   - 在应用设置中输入API Key

3. **启动服务**
   - 在主界面点击"启动服务"
   - 确保所有权限已正确授予

## 🎮 使用方法

### 基本使用流程

1. **启动ReadAssist服务**
2. **打开支持的阅读应用**（如Supernote Document）
3. **选择文本内容** - 应用会自动检测文本选择
4. **点击悬浮按钮** - 进入AI分析界面
5. **获取智能分析** - AI会自动分析选中的文本内容

### 高级功能

- **自定义提示模板**：在设置中自定义AI分析的提示词
- **自动分析模式**：启用后复制文本时自动调用AI分析
- **历史记录管理**：查看、搜索和导出聊天历史
- **收藏功能**：收藏重要的分析结果

## 🏗️ 技术架构

### 核心组件

```
ReadAssist/
├── 🎯 TextAccessibilityService    # 辅助功能服务 - 文本检测和提取
├── 🎈 FloatingWindowService       # 悬浮窗服务 - UI交互管理
├── 📸 ScreenshotService          # 截屏服务 - 屏幕内容捕获
├── 🗄️ Room Database              # 本地数据存储
├── 🌐 Retrofit + OkHttp          # 网络请求处理
└── 🎨 Material Design UI         # 现代化用户界面
```

### 文本提取策略

应用采用三层文本提取策略，确保准确获取用户选中的内容：

1. **选中状态检测** - 查找具有选中状态的UI节点
2. **内容区域分析** - 从ImageView、TextView、WebView等组件提取
3. **智能过滤** - 过滤UI元素和元数据，保留正文内容

### 数据流程

```mermaid
graph LR
    A[文本选择] --> B[辅助功能检测]
    B --> C[文本提取与过滤]
    C --> D[悬浮窗显示]
    D --> E[AI分析请求]
    E --> F[结果展示]
    F --> G[本地存储]
```

## 🛠️ 开发指南

### 开发环境设置

```bash
# 克隆项目
git clone https://github.com/James-Zhu-CA/ReadAssist.git
cd ReadAssist

# 使用Android Studio打开项目
# 或使用命令行构建
./gradlew assembleDebug
```

### 调试工具

项目提供了便捷的调试脚本：

```bash
# 监听文本选择事件（默认模式）
./monitor_logs.sh

# 监听所有应用事件
./monitor_logs.sh all

# 简化输出模式
./monitor_logs.sh simple
```

### 日志标识说明

| 标识 | 含义 |
|------|------|
| 🚀🚀🚀 | 服务启动和连接 |
| 🔥🔥🔥 | Supernote特定事件 |
| 📊📊📊 | 文本候选信息 |
| 📝📝📝 | 实际文本内容 |
| 🎯🎯🎯 | 文本选择状态 |

详细开发文档请参考 [DEVELOPMENT.md](DEVELOPMENT.md)

## 📁 项目结构

```
ReadAssist/
├── app/
│   ├── src/main/java/com/readassist/
│   │   ├── ui/                    # UI组件
│   │   │   ├── MainActivity.kt
│   │   │   ├── SettingsActivity.kt
│   │   │   └── ScreenshotPermissionActivity.kt
│   │   ├── service/               # 核心服务
│   │   │   ├── TextAccessibilityService.kt
│   │   │   ├── FloatingWindowService.kt
│   │   │   └── ScreenshotService.kt
│   │   ├── repository/            # 数据仓库
│   │   ├── database/              # 数据库相关
│   │   ├── network/               # 网络请求
│   │   ├── model/                 # 数据模型
│   │   ├── utils/                 # 工具类
│   │   └── viewmodel/             # ViewModel
│   └── src/main/res/              # 资源文件
├── build_and_install.sh           # 构建安装脚本
├── monitor_logs.sh                # 日志监听脚本
├── DEVELOPMENT.md                 # 开发文档
└── README.md                      # 项目说明
```

## 🔧 配置选项

### API配置

在应用设置中可以配置：

- **Gemini API Key**：Google AI服务密钥
- **提示模板**：自定义AI分析提示词
- **自动分析**：是否在文本选择时自动分析

### 高级设置

开发者可以在代码中调整：

- 文本过滤规则
- 选择检测灵敏度
- 悬浮窗行为
- 日志输出级别

## 🤝 贡献指南

我们欢迎社区贡献！请遵循以下步骤：

1. **Fork** 本项目
2. **创建特性分支** (`git checkout -b feature/AmazingFeature`)
3. **提交更改** (`git commit -m 'Add some AmazingFeature'`)
4. **推送到分支** (`git push origin feature/AmazingFeature`)
5. **创建Pull Request**

### 贡献规范

- 遵循现有的代码风格和命名规范
- 添加适当的日志输出便于调试
- 更新相关文档说明
- 确保在Supernote设备上的兼容性

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [Google Gemini API](https://ai.google.dev/) - 提供强大的AI分析能力
- [Supernote](https://supernote.com/) - 优秀的墨水屏设备
- [Android Accessibility API](https://developer.android.com/guide/topics/ui/accessibility) - 无障碍功能支持

## 📞 联系方式

- **项目主页**：[GitHub Repository](https://github.com/James-Zhu-CA/ReadAssist)
- **问题反馈**：[Issues](https://github.com/James-Zhu-CA/ReadAssist/issues)
- **功能建议**：[Discussions](https://github.com/James-Zhu-CA/ReadAssist/discussions)

---

<div align="center">
  <p>如果这个项目对您有帮助，请给我们一个 ⭐️</p>
  <p>Made with ❤️ for E-ink Reading Experience</p>
</div> 