# ReadAssist 开发操作手册

本手册提供了在ReadAssist项目开发过程中应用项目规则的详细操作指南，确保所有开发活动遵循一致的流程和标准。

## 目录
1. [代码架构操作指南](#代码架构操作指南)
2. [文档更新操作指南](#文档更新操作指南)
3. [功能开发操作指南](#功能开发操作指南)
4. [重构操作指南](#重构操作指南)
5. [代码审查清单](#代码审查清单)
6. [规则优先级与冲突处理](#规则优先级与冲突处理)
7. [常见问题与解决方案](#常见问题与解决方案)

## 代码架构操作指南

### 添加新功能时的模块选择

1. **确定功能归属**：
   - 查看现有管理器，确定新功能属于哪个职责范围
   - 如果完全无法归类，考虑创建新的管理器类

2. **修改现有管理器**：
   ```kotlin
   // 在ChatWindowManager.kt中添加新功能
   fun addNewFeature() {
       // 功能实现
       Log.d(TAG, "✅ 新功能已添加")
       
       // 如需通知服务类，使用回调
       callbacks.onNewFeatureActivated()
   }
   ```

3. **创建新管理器**：
   ```kotlin
   // 1. 创建新管理器类
   package com.readassist.service.managers
   
   class NewFeatureManager(
       private val context: Context,
       private val callbacks: NewFeatureCallbacks
   ) {
       companion object {
           private const val TAG = "NewFeatureManager"
       }
       
       // 接口定义
       interface NewFeatureCallbacks {
           fun onSomethingHappened(data: String)
       }
       
       // 功能实现
       fun doSomething() {
           // 实现
           callbacks.onSomethingHappened("结果")
       }
   }
   
   // 2. 在FloatingWindowServiceNew中添加支持
   class FloatingWindowServiceNew : Service(),
       NewFeatureManager.NewFeatureCallbacks {
       
       private lateinit var newFeatureManager: NewFeatureManager
       
       // 初始化
       private fun initializeManagers() {
           // 其他管理器初始化...
           newFeatureManager = NewFeatureManager(this, this)
       }
       
       // 实现回调
       override fun onSomethingHappened(data: String) {
           // 处理回调
       }
   }
   ```

### 管理器间通信标准操作

1. **服务类作为中介**：
   ```kotlin
   // 在FloatingWindowServiceNew中
   override fun onTextSelected(text: String) {
       // 文本选择管理器回调服务类
       chatWindowManager.importTextToInputField(text)
   }
   ```

2. **广播通信**：
   ```kotlin
   // 发送广播
   val intent = Intent("com.readassist.ACTION_NAME")
   intent.putExtra("key", "value")
   LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
   ```

3. **状态共享**：
   ```kotlin
   // 管理器提供状态查询方法
   fun getSelectionState(): Boolean = isTextSelectionActive
   
   // 服务类查询状态
   if (textSelectionManager.getSelectionState()) {
       // 根据状态执行操作
   }
   ```

## 文档更新操作指南

### 版本更新文档流程

当完成功能开发或架构调整后，按以下步骤更新文档：

1. **更新CHANGELOG.md**：
   ```markdown
   ## v1.x.0 (yyyy-MM-dd)
   
   ### 新增功能
   - **功能名称**：简要描述功能的主要特点
   - 列出子功能和改进点
   
   ### 优化和改进
   - 列出性能优化和用户体验改进
   ```

2. **更新VERSION_NOTES.md**（如有架构变更）：
   ```markdown
   # ReadAssist v1.x.0 版本说明文档
   
   ## 背景与目标
   简要说明变更的背景和目标
   
   ## 变更概述
   详细描述变更内容
   
   ## 架构详解
   详细说明新架构的组成和工作原理
   
   ## 效果与收益
   说明变更带来的好处和改进
   ```

3. **更新DEVELOPER_GUIDE.md**：
   ```markdown
   # 新功能开发指南
   
   ## 使用方法
   详细说明如何使用新功能或新架构进行开发
   
   ## 示例代码
   提供具体的示例代码
   ```

4. **更新README.md**：
   ```markdown
   ## 最新更新 (v1.x.0)
   
   简要描述最新版本的主要变更和新功能。
   ```

### 文档验证清单

在提交文档更新前，确保：

- [ ] 版本号和日期正确
- [ ] 所有功能变更都已记录
- [ ] 文档格式统一，无语法错误
- [ ] 开发指南与当前代码保持一致
- [ ] README中包含简要的更新说明

## 功能开发操作指南

### 新功能开发流程

1. **需求分析与设计**：
   - 明确功能需求和使用场景
   - 确定影响范围和涉及组件
   - 绘制简单的流程图或类图

2. **开发环境准备**：
   ```bash
   # 确保代码最新
   git pull origin main
   
   # 创建功能分支
   git checkout -b feature/feature-name
   ```

3. **代码实现**：
   - 遵循模块化设计原则
   - 添加适当的日志和注释
   - 确保错误处理完善

4. **测试**：
   ```kotlin
   // 单元测试示例
   @Test
   fun testNewFeature() {
       // 准备测试数据
       val manager = NewFeatureManager(mockContext, mockCallbacks)
       
       // 执行测试
       val result = manager.doSomething()
       
       // 验证结果
       assertThat(result).isEqualTo(expectedValue)
   }
   ```

5. **文档更新**：
   - 按文档更新流程更新相关文档
   - 添加功能使用说明和示例

6. **代码提交**：
   ```bash
   # 提交代码
   git add .
   git commit -m "feat: 添加新功能XXX"
   git push origin feature/feature-name
   ```

7. **合并请求**：
   - 创建Pull Request
   - 等待代码审查和测试
   - 解决反馈问题
   - 合并到主分支

## 重构操作指南

### 大型重构标准流程

1. **规划阶段**：
   - 明确重构目标和范围
   - 制定详细的重构计划
   - 识别潜在风险和缓解措施

2. **实施重构**：
   ```bash
   # 创建重构分支
   git checkout -b refactor/component-name
   ```

3. **保留旧代码**：
   ```kotlin
   // 保留原有代码
   class OriginalClass {
       // 原有实现
   }
   
   // 创建新实现
   class RefactoredClass {
       // 新实现
   }
   ```

4. **实现迁移策略**：
   ```kotlin
   // 在合适的地方添加迁移代码
   if (shouldUseNewImplementation()) {
       // 使用新实现
       newImpl.doSomething()
   } else {
       // 使用旧实现
       oldImpl.doSomething()
   }
   ```

5. **全面测试**：
   - 单元测试确保功能正确性
   - 集成测试验证组件交互
   - 端到端测试验证用户场景

6. **文档更新**：
   - 详细记录架构变更
   - 更新开发指南
   - 准备迁移指南

7. **平稳过渡**：
   - 逐步推出新实现
   - 监控性能和稳定性
   - 收集用户反馈
   - 解决发现的问题

8. **完成迁移**：
   - 在确认稳定后移除旧实现
   - 更新所有相关文档
   - 完成最终代码清理

## 代码审查清单

在进行代码审查时，使用以下清单确保代码质量：

### 架构合规性

- [ ] 遵循模块化设计原则
- [ ] 管理器职责清晰，不包含不相关功能
- [ ] 依赖注入正确实现
- [ ] 接口设计合理，无冗余方法

### 代码质量

- [ ] 代码风格统一，符合项目规范
- [ ] 命名清晰，符合命名约定
- [ ] 注释充分，说明复杂逻辑
- [ ] 错误处理完善，包含适当的日志

### 性能考量

- [ ] 无明显性能问题（如主线程阻塞）
- [ ] 资源正确释放（如关闭数据库连接）
- [ ] 内存使用合理，无明显泄漏风险
- [ ] 异步操作正确处理

### 文档完整性

- [ ] 文档已更新，反映当前变更
- [ ] API文档完整，包含方法说明
- [ ] 示例代码与实际实现一致
- [ ] 版本号和日期正确

## 规则优先级与冲突处理

ReadAssist项目使用多个规则文件来定义开发标准，如何正确处理这些规则之间的关系非常重要。

### 规则文件关系图

```
规则层次结构：
┌────────────────────────────┐
│  10-project-rules.yaml     │ ◄── 高层项目规则与流程规范
├────────────────────────────┤
│                            │
│  01-09 技术领域规则        │ ◄── 具体技术规范
│  (.cursor/rules/*)         │
└────────────────────────────┘
        │
        ▼
┌────────────────────────────┐
│  实际应用文档              │
│  (DEVELOPER_GUIDE.md等)    │ ◄── 规则应用和实施
└────────────────────────────┘
```

### 规则优先级

当不同规则之间存在潜在冲突时，遵循以下优先级原则：

1. **最高优先级**：领域特定的技术规则（01-09号规则文件）
   - 例如：UI相关开发必须首先符合`01-eink-constraints.yaml`中的墨水屏约束

2. **中等优先级**：`10-project-rules.yaml`中的项目规则
   - 这些规则为整个项目提供了统一的开发流程和规范

3. **参考优先级**：实施文档中的具体指导
   - 如DEVELOPER_GUIDE.md和本操作手册中的指导

### 冲突处理流程

当发现规则之间存在冲突时，按照以下步骤处理：

1. **识别冲突**：
   ```
   # 例如：两个规则对同一问题有不同要求
   # 01-eink-constraints.yaml: 不允许使用动画
   # 某个UI库指南：推荐使用过渡动画提升体验
   ```

2. **查阅优先级**：
   ```
   # 确定哪个规则优先级更高
   # 在这个例子中，01-eink-constraints.yaml的规则优先级更高
   ```

3. **记录决策**：
   ```
   # 在代码注释中说明决策
   // 注意：根据墨水屏约束(01-eink-constraints)，
   // 此处不使用标准的过渡动画，而是使用静态切换
   ```

4. **报告冲突**：
   ```bash
   # 通过项目管理系统报告冲突
   # 创建一个问题："规则冲突：动画使用约束与UI库最佳实践冲突"
   ```

5. **寻求澄清**：
   ```
   # 项目架构师或技术负责人应当评审冲突，并更新相关规则
   # 可能的解决方案：在一个规则中明确引用另一个规则的优先性
   ```

### 实际应用示例

#### 示例1：UI组件开发

当开发墨水屏UI组件时：

```kotlin
// 首先遵循01-eink-constraints.yaml中的基本约束
// 👌 正确示例
class EinkButton : Button {
    init {
        // 无动画、无渐变、高对比度
        setBackgroundColor(Color.WHITE)
        setTextColor(Color.BLACK)
        // 禁用涟漪效果
        stateListAnimator = null
    }
}

// ❌ 错误示例
class FancyButton : Button {
    init {
        // 使用了渐变背景、动画效果
        background = GradientDrawable()
        animate().alpha(0.8f).start() // 违反墨水屏约束
    }
}
```

#### 示例2：模块化设计与架构规则

当设计新功能时：

```kotlin
// 需要同时遵循:
// - 03-arch-design.yaml中的MVVM架构规则
// - 10-project-rules.yaml中的模块化设计原则

// 👌 正确示例
// 1. 在viewmodel包中创建ViewModel
class FeatureViewModel(
    private val repository: FeatureRepository // 依赖注入
) : ViewModel() {
    // 数据和业务逻辑
}

// 2. 在service/managers包中创建管理器
class FeatureManager(
    private val context: Context,
    private val callbacks: FeatureCallbacks // 接口通信
) {
    // 功能实现
}

// ❌ 错误示例
// 在UI层直接访问数据库，违反MVVM架构
class FeatureActivity : AppCompatActivity() {
    fun loadData() {
        val database = AppDatabase.getInstance(this)
        val data = database.featureDao().getAll() // 违反MVVM架构规则
    }
}
```

### 规则维护流程

为保证规则之间的一致性，团队需要定期进行规则维护：

1. **季度规则审查**：
   - 每季度由架构师牵头，审查所有规则文件
   - 识别潜在冲突，更新规则间的引用关系

2. **规则变更流程**：
   ```
   a. 提出变更建议
   b. 评估对现有规则的影响
   c. 更新相关规则文件
   d. 在CHANGELOG.md中记录变更
   e. 通知团队变更内容
   ```

3. **规则覆盖检查**：
   - 确保每个技术领域有对应的规则文件
   - 确保规则间没有未处理的冲突

## 常见问题与解决方案

### 1. 如何决定是修改现有管理器还是创建新管理器？

**解决方案**：
- 如果功能与某个现有管理器的职责紧密相关，应修改现有管理器
- 如果功能代表一个全新的职责领域，应创建新管理器
- 如果不确定，可以先在现有管理器中实现，如果导致管理器过于臃肿，再考虑拆分

### 2. 如何处理跨多个管理器的功能？

**解决方案**：
- 在服务类中实现协调逻辑
- 将功能拆分到各个管理器中，每个管理器负责自己的部分
- 使用回调机制在管理器间传递信息
- 示例：
  ```kotlin
  // 在服务类中
  fun handleComplexFeature() {
      textSelectionManager.processText { text ->
          screenshotManager.captureWithText(text) { bitmap ->
              aiCommunicationManager.analyzeImage(bitmap, text) { result ->
                  chatWindowManager.displayResult(result)
              }
          }
      }
  }
  ```

### 3. 如何处理管理器间的循环依赖？

**解决方案**：
- 重新设计接口，消除循环依赖
- 使用服务类作为中介，解耦管理器
- 使用事件总线或观察者模式
- 考虑是否管理器职责划分不当

### 4. 测试时如何模拟管理器依赖？

**解决方案**：
- 使用依赖注入便于测试
- 使用mock框架模拟依赖
- 示例：
  ```kotlin
  @Test
  fun testFeature() {
      // 创建mock对象
      val mockDependency = mockk<Dependency>()
      every { mockDependency.someMethod() } returns expectedValue
      
      // 使用mock创建被测类
      val manager = SomeManager(mockDependency)
      
      // 执行测试
      val result = manager.featureMethod()
      
      // 验证结果和交互
      verify { mockDependency.someMethod() }
      assertThat(result).isEqualTo(expectedValue)
  }
  ```

---

通过遵循本操作手册的指导，开发团队可以确保项目的一致性、可维护性和质量。随着项目的发展，可以根据实际情况更新和完善本手册。

文档编写：ReadAssist开发团队
日期：2024年7月14日
版本：v1.0 