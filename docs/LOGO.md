# ReadAssist 徽标设计

## 当前徽标

ReadAssist 使用简洁的图标设计，体现智能阅读助手的核心理念。

### 设计元素

- **主色调**: 深蓝色 (#1976D2) - 代表专业和可信赖
- **辅助色**: 浅蓝色 (#42A5F5) - 代表智能和创新
- **图标元素**: 
  - 书本图标 - 代表阅读
  - AI 符号 - 代表智能分析
  - 墨水屏效果 - 针对 E-ink 设备优化

### 文件位置

```
app/src/main/res/drawable/
├── ic_launcher.png          # 应用图标 (48x48)
├── ic_launcher_round.png    # 圆形图标 (48x48)
└── ic_launcher_foreground.xml # 前景图标 (矢量)
```

### 尺寸规格

| 密度 | 尺寸 | 用途 |
|------|------|------|
| mdpi | 48x48 | 标准密度屏幕 |
| hdpi | 72x72 | 高密度屏幕 |
| xhdpi | 96x96 | 超高密度屏幕 |
| xxhdpi | 144x144 | 超超高密度屏幕 |
| xxxhdpi | 192x192 | 超超超高密度屏幕 |

## 品牌指南

### 使用规范

1. **保持比例**: 不要拉伸或压缩徽标
2. **清晰空间**: 徽标周围保持适当的空白区域
3. **颜色一致**: 使用标准的品牌色彩
4. **背景适配**: 确保在不同背景下的可读性

### 禁止事项

- ❌ 改变徽标的颜色
- ❌ 添加阴影或特效
- ❌ 旋转或倾斜徽标
- ❌ 在徽标上添加文字

## 适配说明

### 墨水屏优化

为了在墨水屏设备上获得最佳显示效果：

1. **高对比度**: 使用黑白或高对比度设计
2. **简洁线条**: 避免复杂的渐变和细节
3. **清晰边界**: 确保图标边界清晰可见

### 自适应图标

Android 8.0+ 支持自适应图标：

```xml
<!-- ic_launcher_foreground.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- 图标路径 -->
</vector>
```

## 未来计划

### 版本 2.0 设计

计划在未来版本中更新徽标设计：

1. **更现代的设计语言**
2. **更好的墨水屏适配**
3. **支持动态图标**
4. **多主题适配**

### 社区贡献

欢迎社区贡献徽标设计：

1. 遵循品牌指南
2. 提供多种尺寸
3. 包含设计说明
4. 通过 Pull Request 提交

---

如果您有徽标设计建议或想要贡献新的设计，请在 [GitHub Issues](https://github.com/James-Zhu-CA/ReadAssist/issues) 中提出或直接提交 Pull Request。 