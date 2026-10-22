# CodexToolWindowController 拆分方案

## 目标

`CodexToolWindowController` 当前同时负责 Bridge 命令分发、会话状态、Provider 生命周期、历史记录、附件与文件引用、修改审阅、编辑器上下文、Skills、MCP、设置和 app-server 事件展示。拆分目标不是按方法数量机械分文件，而是建立可替换的宿主边界，便于 JetBrains 插件继续演进和独立验证各项职责。

完成后的控制器只承担三项职责：组装依赖、把 Bridge 命令路由到对应协作对象、协调少量跨领域流程。Bridge v1 的命令和事件格式、用户可见行为以及现有会话隔离语义在拆分期间保持不变。

## 约束

- 每批只迁移一个内聚职责，迁移后运行完整 `check`，避免大规模移动掩盖行为变化。
- 已有 `conversation`、`provider`、`service` 和 `bridge` 层继续作为业务边界，不再包一层无实际价值的接口。
- 新协作对象显式接收依赖，不读取控制器内部状态；需要当前会话时由调用方传入 Session ID 或不可变数据。
- 不引入通用事件总线、Service Locator 或细粒度“一个方法一个类”。
- JetBrains API 集中在 `ui` 协调器和现有平台服务中，平台无关的解析与状态逻辑不得新增 JetBrains 依赖。
- 每个阶段完成后同步 README、CHANGELOG 和本文件状态。

## 目标结构

```text
CodexToolWindowController
  |-- BridgeProtocolCodec / command routing
  |-- ConversationCoordinator       会话创建、恢复、历史与回合调度
  |-- ProviderSettingsCoordinator   供应商、模型与运行时切换
  |-- WorkspaceInputCoordinator     附件、文件引用、拖放与项目文件搜索
  |-- ChangeReviewCoordinator       修改接受、撤销、Diff 与文件定位
  |-- CodexFeatureCoordinator       Skills、MCP、用量、压缩与回溯
  `-- BridgeEventPresenter          事件构建、页面发送与 JSON 映射
```

这些名称描述职责边界，不要求一次性全部创建。若后续迁移发现两个候选对象共享大量状态，应保留为一个较粗粒度协调器。

## 分阶段实施

### 第一阶段：修改审阅边界（已完成）

- 新增 `ChangeReviewCoordinator`。
- 迁移修改索引解析、接受、撤销、Diff 创建与复用、文件管理器定位。
- 协调器独立拥有 Diff 页签映射，并接收 Session ID 和 Provider 名称，不反向访问控制器。
- 控制器继续发布 `changes` Bridge 事件，确保协议层和 UI 数据不变。

验收：接受/撤销单文件和全部文件、重复打开 Diff、手动关闭后重新打开、关闭/新建会话清理 Diff 的行为保持不变。

### 第二阶段：工作区输入边界（已完成）

- 新增 `WorkspaceInputCoordinator`，抽出附件选择、图片判定、原生拖放、文件引用增删排序和项目文件候选缓存。
- 将 JCEF 拖放坐标与 JetBrains Project View 拖放封装在同一协调器中，并由控制器生命周期统一释放。
- 保留 `ConversationSession` 对附件及引用的所有权，协调器只执行输入操作并通过窄监听接口请求发布状态。
- 将引用顺序校验测试迁到工作区协调器，发送阶段的引用同步失败由会话协调器定向提示。

验收：文件选择、图片选择、项目树拖入、系统拖入、`@` 搜索、引用排序及撤销恢复稳定 ID 的行为不变。

### 第三阶段：供应商与设置边界（已完成）

- 新增 `ProviderSettingsCoordinator`，抽出 Provider 配置校验、模型目录缓存、启用/删除流程及运行时重启。
- 普通界面偏好和 Agent/Prompt 持久化归入同一协调器；提示音配置由协调器管理，实际通知与播放仍由 `AttentionService` 承担。
- 将纯模型选择规则迁出控制器并保留独立单元测试。

验收：Codex/Claude 渠道切换、自定义供应商启用、失败回滚、模型记忆和密钥存储行为不变。

### 第四阶段：Codex 扩展能力边界（已完成）

- 新增 `CodexFeatureCoordinator`，抽出 Skills 导入与启停、MCP 配置与登录、用量、压缩、Review 和回溯。
- 文件打开动作通过明确的宿主操作入口调用，不让业务解析依赖具体编辑器实现。
- 扩展协调器通过窄监听接口输出功能结果，Bridge payload 由第五阶段新增的 Presenter 统一构建。

验收：Skills 和 MCP 页面全部命令、日志、错误提示以及 Codex 专属操作保持兼容。

### 第五阶段：会话编排与事件展示（已完成）

- 新增 `ConversationCoordinator`，迁移页签激活、新建、关闭、历史加载与恢复、重命名、导出及关闭会话的迟到通知隔离状态。
- 输入发送、立即引导、队列推进、Codex/Claude 回合启动与事件处理、命令增量缓冲、审批问题和任务完成通知统一由会话协调器管理。
- app-server 监听器的注册、通知过滤、协议错误处理以及 Provider 资源释放随回合状态一并收归会话协调器，控制器不再直接实现 `CodexEventListener`。
- 新增 `BridgeEventPresenter`，将 `ConversationEntry`、附件、引用、修改、设置、会话状态等 Bridge payload 组装以及协议编码、页面发送集中到 Presenter。
- 控制器保留为 composition root、Bridge 命令入口及少量 IDE 上下文桥接，当前规模约 800 行，不以继续机械拆小为目标。

验收：多页签并发、后台流式输出、迟到事件过滤、排队发送、立即引导、审批和结构化提问通过现有测试及手动回归。

## 测试策略

- 每批运行 `gradlew.bat check`，覆盖 Java、Bridge fixture、TypeScript、Vitest 与 Playwright。
- 纯选择、校验、路径和 JSON 映射逻辑使用单元测试，不依赖 IDE fixture。
- 涉及 JetBrains Diff、拖放和文件选择器的协调器保持薄层，通过既有服务测试和插件内手动回归验证。
- 任何 Bridge 类型或字段调整都必须同时更新 Java、TypeScript、Schema 和 fixture；本次拆分原则上不调整协议。

## 完成标准

- 控制器不再直接实现可独立描述的业务流程。
- 各协调器拥有自己的临时状态和生命周期，并在控制器 `dispose` 时释放。
- 不出现双向依赖、跨协调器共享可变集合或仅为转发单个方法而创建的抽象。
- README 描述当前架构，CHANGELOG 记录每个已落地阶段。
