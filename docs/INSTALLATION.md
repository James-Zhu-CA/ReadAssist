# 安装指南

本文档提供了 ReadAssist 的详细安装步骤和配置说明。

## 📋 系统要求

### 最低要求
- **Android 版本**: 5.0 (API 21) 或更高
- **RAM**: 2GB 或更多
- **存储空间**: 50MB 可用空间
- **网络**: 用于 AI 功能的互联网连接

### 推荐配置
- **设备**: Supernote A5 X2 或其他墨水屏平板
- **Android 版本**: 8.0 或更高
- **RAM**: 4GB 或更多
- **网络**: 稳定的 Wi-Fi 连接

## 🚀 安装方式

### 方式一：APK 直接安装（推荐）

1. **下载 APK**
   - 访问 [Releases 页面](https://github.com/James-Zhu-CA/ReadAssist/releases)
   - 下载最新版本的 `ReadAssist-v1.0.0.apk`

2. **启用未知来源**
   - 进入设备设置 → 安全性
   - 开启"未知来源"或"允许安装未知应用"
   - 或在安装时选择"仍要安装"

3. **安装应用**
   - 点击下载的 APK 文件
   - 按照提示完成安装
   - 安装完成后可在应用列表中找到 ReadAssist

### 方式二：从源码构建

#### 准备开发环境

1. **安装 Android Studio**
   ```bash
   # 下载并安装 Android Studio
   # https://developer.android.com/studio
   ```

2. **安装 JDK 17**
   ```bash
   # macOS (使用 Homebrew)
   brew install openjdk@17
   
   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk
   
   # Windows
   # 从 Oracle 或 OpenJDK 官网下载安装
   ```

3. **配置环境变量**
   ```bash
   # 添加到 ~/.bashrc 或 ~/.zshrc
   export JAVA_HOME=/path/to/jdk-17
   export ANDROID_HOME=/path/to/android-sdk
   export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools
   ```

#### 克隆和构建

1. **克隆项目**
   ```bash
   git clone https://github.com/James-Zhu-CA/ReadAssist.git
   cd ReadAssist
   ```

2. **使用构建脚本**
   ```bash
   # 自动构建并安装（需要连接设备）
   ./build_and_install.sh
   ```

3. **手动构建**
   ```bash
   # 构建 Debug 版本
   ./gradlew assembleDebug
   
   # 构建 Release 版本
   ./gradlew assembleRelease
   
   # 安装到连接的设备
   ./gradlew installDebug
   ```

## ⚙️ 首次配置

### 1. 权限设置

#### 悬浮窗权限

**Android 6.0+:**
1. 打开 ReadAssist 应用
2. 点击"授予权限"按钮
3. 系统会跳转到权限设置页面
4. 找到 ReadAssist，开启"显示在其他应用上层"

**手动设置:**
1. 设置 → 应用 → 特殊应用权限
2. 显示在其他应用上层 → ReadAssist
3. 开启权限

#### 辅助功能权限

1. 设置 → 辅助功能
2. 找到"ReadAssist 文本服务"
3. 点击进入并开启服务
4. 确认"确定"授权提示

**注意**: 这是核心权限，没有此权限应用无法检测文本选择。

#### 其他权限

应用还需要以下权限（通常自动授予）：
- 网络访问权限
- 存储权限（用于保存聊天记录）

### 2. API Key 配置

#### 获取 Google Gemini API Key

1. **访问 Google AI Studio**
   - 打开 [https://makersuite.google.com/app/apikey](https://makersuite.google.com/app/apikey)
   - 使用 Google 账户登录

2. **创建 API Key**
   - 点击"Create API Key"
   - 选择项目或创建新项目
   - 复制生成的 API Key

3. **在应用中设置**
   - 打开 ReadAssist 主界面
   - 点击"设置"按钮
   - 在"Gemini API Key"字段中粘贴 API Key
   - 点击"保存"

#### API Key 安全提示

- 不要在公共场所输入 API Key
- 不要与他人分享您的 API Key
- 定期检查 API 使用情况
- 如有泄露，立即重新生成

### 3. 服务启动

1. **检查权限状态**
   - 确保所有权限显示为绿色勾选
   - 如有红色叉号，点击重新授权

2. **启动服务**
   - 点击"启动服务"按钮
   - 等待显示"服务运行中"状态

3. **测试功能**
   - 打开支持的阅读应用（如 Supernote Document）
   - 选择一段文本
   - 观察是否出现悬浮按钮

## 🔧 故障排除

### 安装问题

#### APK 安装失败

**错误**: "应用未安装"
**解决**: 
- 确保有足够的存储空间
- 检查是否启用了"未知来源"
- 尝试重启设备后再安装

**错误**: "解析包时出现问题"
**解决**:
- 重新下载 APK 文件
- 检查文件是否完整
- 确认设备架构兼容性

#### 构建失败

**错误**: "Java 版本不兼容"
**解决**:
```bash
# 检查 Java 版本
java -version

# 设置正确的 JAVA_HOME
export JAVA_HOME=/path/to/jdk-17
```

**错误**: "SDK 路径未找到"
**解决**:
```bash
# 创建 local.properties 文件
echo "sdk.dir=/path/to/android-sdk" > local.properties
```

### 权限问题

#### 悬浮窗权限无法开启

**Xiaomi/MIUI**:
1. 设置 → 应用设置 → 授权管理
2. 悬浮窗管理 → ReadAssist → 允许

**Huawei/EMUI**:
1. 设置 → 应用 → 权限管理
2. 悬浮窗 → ReadAssist → 允许

**Samsung/One UI**:
1. 设置 → 应用 → ReadAssist
2. 权限 → 显示在其他应用上层 → 允许

#### 辅助功能权限重置

某些设备可能会自动关闭辅助功能服务：

1. **加入白名单**
   - 设置 → 电池 → 应用耗电管理
   - 找到 ReadAssist → 不优化

2. **关闭自动管理**
   - 设置 → 应用 → ReadAssist
   - 电池 → 关闭"自动管理"

### 功能问题

#### 检测不到文本选择

1. **检查支持的应用**
   - 确认使用的是支持的阅读应用
   - 参考支持列表：Supernote Document、Adobe Reader、WPS Office

2. **重启服务**
   - 在 ReadAssist 中停止服务
   - 等待 5 秒后重新启动

3. **重新授权**
   - 关闭辅助功能权限
   - 重新开启并确认授权

#### AI 分析失败

1. **检查网络连接**
   ```bash
   # 测试网络连接
   ping google.com
   ```

2. **验证 API Key**
   - 确认 API Key 输入正确
   - 检查 API 配额是否用完
   - 尝试重新生成 API Key

## 📱 设备特定说明

### Supernote A5 X2

ReadAssist 专为 Supernote 设备优化：

1. **推荐设置**
   - 开启"开发者选项"
   - 允许"USB 调试"（用于日志查看）
   - 关闭"电池优化"

2. **最佳实践**
   - 使用 Supernote Document 应用阅读
   - 选择文本时稍作停顿
   - 避免快速连续选择

### 其他墨水屏设备

1. **Kindle**
   - 需要安装 Android 系统
   - 可能需要 root 权限

2. **Boox 系列**
   - 通常兼容性良好
   - 建议关闭省电模式

## 🔄 更新说明

### 自动更新检查

ReadAssist 会定期检查更新：

1. 打开应用时自动检查
2. 设置中手动检查更新
3. GitHub Releases 页面查看最新版本

### 手动更新

1. 下载新版本 APK
2. 直接安装覆盖旧版本
3. 数据和设置会自动保留

### 数据迁移

升级时数据通常会自动保留，但建议：

1. 导出重要的聊天记录
2. 记录 API Key 设置
3. 截图保存自定义配置

---

如果在安装过程中遇到问题，请查看 [故障排除指南](USER_GUIDE.md#故障排除) 或在 [GitHub Issues](https://github.com/James-Zhu-CA/ReadAssist/issues) 中报告问题。 