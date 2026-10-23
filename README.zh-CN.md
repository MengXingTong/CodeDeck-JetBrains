# CodeDeck

[English](README.md) | 简体中文

当前构建版本：**2.1.4**。

**借助 AI，在 JetBrains IDE 中读懂并审阅代码。**

集中查看项目改动，借助 AI 的逐段讲解和上下文问答，理解修改、核对逻辑，并由你决定是否保留。

## 主要功能

- **读懂每段改动。** 在 Diff 中阅读 AI 对“改了什么、为什么改”的讲解，辅助你审阅代码，支持单文件和批量生成。
- **项目 ChangeList。** 左侧查看 Git / Perforce 改动，双击文件打开原生 Diff。
- **带着代码聊天。** 在 Diff 中选中代码、拖入文件或目录，也可用 `@` 引用后提问。
- **按对话审阅 AI 修改。** 聊天框内列出本次改动，支持查看差异、接受和撤销，逐项操作后保持列表滚动位置。
- **保持原有换行。** 默认保留已捕获文件的 LF / CRLF 和 UTF-8 BOM；需要转换格式时，可在行为设置中关闭。
- **使用自己的模型和工具。** 支持 Codex / Claude Code、自定义供应商、多会话并行、消息排队和提示词；Codex 另支持执行中引导、Skills 和 MCP。

支持简体中文和英文，切换 IDE 语言并重启后生效；其他 IDE 语言默认使用英文。

文件上下文跟随 IDE 当前编辑器，新建或切换聊天页签时保留文件、选区行号和 Diff 版本信息，各页签的输入草稿独立保存。关闭编辑器或手动移除文件上下文后清除对应标签。

任务运行中，普通发送及发送快捷键会将多条消息依次列在输入框上方。可逐条编辑或删除未发送消息，也可点击队首的**立即引导**调整当前 Codex 回合。正在编辑的消息等待保存或取消后再发送，保留排队时的文件、附件和编辑器上下文；真正发送时才加入聊天记录。

命令推测捕获默认过滤日志、构建产物，并复用项目的 **ChangeList → 忽略列表 / 恢复跟踪**规则。已识别的只读命令不会把同期文件变化计入会话；明确的补丁、文件编辑和已识别写入命令中的显式文件路径仍会捕获，包括被忽略文件。规则变更从后续回合生效。

Codex 历史默认以已保存的快照打开，不占用原会话。可点击“刷新历史”读取更新、“继续原会话”尝试恢复，或在其他客户端仍占用时“复制为新会话继续”。这些操作会保留输入草稿、附件和文件引用。

## 功能截图

截图来自独立 Java 示例项目 **Checkout Review Demo**，红框标出对应功能。

### 查看项目改动，选中代码追问

**左侧 ChangeList** 列出项目改动，双击打开 Diff；**右侧聊天框** 可携带当前文件和选区，直接讨论代码。

![红框标出左侧项目 ChangeList 和右侧聊天框](docs/images/readme/review-workspace.png)

### 对照代码理解改动

在 **Diff 顶部工具栏** 选择模型、生成 AI 注释，再对照代码核对修改逻辑和影响。单文件任务运行时，状态提示与后台任务标题仅显示文件名。

![红框标出 Diff 顶部 AI 注释工具栏和代码段讲解](docs/images/readme/inline-annotations.png)

### 在聊天框中审阅 AI 修改

展开**聊天框内的 ChangeList**，查看本次对话修改的文件、增删行数及接受／撤销按钮；点击文件打开 Diff。

![红框标出展开的聊天 ChangeList 和修改审阅按钮](docs/images/readme/conversation-changes.png)

## 开始使用

1. 从 [最新版本](https://github.com/MengXingTong/CodeDeck-JetBrains/releases/latest) 下载插件 ZIP。在 IDE 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 ZIP，重启 IDE。
2. 安装并配置 **Codex CLI** 或 **Claude Code CLI**。如需指定可执行文件路径，打开 **Settings → Tools → CodeDeck**。
3. 打开 **View → Tool Windows → CodeDeck Changes**，双击改动文件，点击 Diff 上方的 **AI 注释**。
4. 打开右侧 **CodeDeck**，开启**文件上下文**，选中 Diff 中的代码，追问逻辑和影响。需要 AI 修改时，再从聊天框的 ChangeList 逐项检查结果。

例如：“解释这段修改的逻辑和影响”或“用具体输入说明边界条件”。

## 环境与设置

- **JetBrains IDE 2024.3+**，使用支持 JCEF 的运行时。
- **Codex CLI 或 Claude Code CLI**，以及自己的模型服务配置。Claude 需要 **Claude Code 2.1.210+**；相关功能测试较少，建议优先使用 Codex。
- AI Diff 注释使用 **Codex / GPT 供应商**。聊天、单文件注释和批量注释分别保存模型设置；Git / Perforce 沿用 IDE 的连接。

| 设置内容 | 入口 |
| --- | --- |
| 供应商接口与凭据 | CodeDeck 设置 → 供应商 |
| 模型、思考程度、审批与工作区权限 | 聊天输入框下方工具栏 |
| 主题、字号、快捷键、通知与换行保护 | CodeDeck 设置 → 基础设置 |
| 常用指令与工具 | 提示词库、Agent 身份、Skills、MCP |

自定义 GPT 接口支持 Responses API 和 Chat Completions API。凭据保存在 **JetBrains PasswordSafe**；修改供应商配置后请新建对话。

修改捕获、撤销范围和渠道差异见 [功能与使用细节](docs/usage-details.md)。从独立 ChangeList 插件迁移时，请先禁用 **VCS Changelist Sidebar**。

## 从源码构建

需要 **JDK 21**。在仓库根目录执行：

```shell
./gradlew check buildPlugin verifyPlugin
```

Windows 使用 `gradlew.bat`。安装包输出至 `build/distributions/CodeDeck-<版本号>.zip`；开发运行使用 `./gradlew runIde`。

[开发文档](docs/development.md) · [更新记录](CHANGELOG.md) · [版本下载](https://github.com/MengXingTong/CodeDeck-JetBrains/releases)

## 开源许可

[MIT License](LICENSE)。CodeDeck 原创部分版权归 `MengXingTong` 所有。部分界面设计与实现参考 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，版权与许可声明随源码和插件一同保留。
