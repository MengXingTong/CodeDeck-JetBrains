# CodeDeck

[English](README.md) | 简体中文

**AI时代的Review神器 For JetBrains。**

源码仓库：[CodeDeck-JetBrains](https://github.com/MengXingTong/CodeDeck-JetBrains)。

CodeDeck 把变更列表、原生 Diff、AI 讲解和对话放在同一个 IDE 里。无论代码是你写的、同事写的，还是 AI 刚改的，都能对着代码看变化、问原因，再决定留下哪些修改。

界面支持简体中文和英文，默认跟随 JetBrains IDE 的界面语言；非中文语言使用英文。切换 IDE 语言并重启后，聊天界面、原生面板和 AI Diff 注释会使用对应语言。用户消息、文件名、代码和已保存的自定义提示词保留原文。

## 能做什么

- **让 AI 给 Diff 逐段打注释。** 点击“AI 注释”，AI 会逐个代码段分析修改，把“改了什么、为什么这样改、会影响什么”直接标在对应代码段旁边。边看差异边读注释，不用在聊天记录和代码之间反复对照。
- **选中代码，接着问。** 在 Diff 中选一段代码，就能追问逻辑、讨论边界条件，或者让 AI 补测试。文件、行号和选区会一起带过去。
- **集中审阅项目改动。** 用变更树查看 Git / Perforce 等版本控制中的修改，双击就进 IDE 原生 Diff；也可以批量生成 AI 注释。
- **逐项确认 AI 写的代码。** 每个对话都有自己的修改列表，支持查看差异、接受和撤销，不用靠聊天回复猜它到底动了哪些文件。
- **防止 AI 修改代码时混淆 LF/CRLF 换行。** 默认保持已捕获文件原有的 LF / CRLF 和 UTF-8 BOM，保留代码改动，减少整文件格式变化造成的无效 Diff；需要主动统一换行时，可在行为设置关闭保护。
- **把文件或文件夹直接拖进聊天。** 从变更树或项目树拖入文件、目录，也可以用 `@` 搜索引用；图片可直接作为附件发送。ChangeList 的拖拽仅用于聊天输入框，不能拖入中间代码区。
- **几个任务同时做。** 多个对话页签独立运行，支持排队发送；Codex 还可以在执行过程中接收新的引导。
  启动过程中也可点击“停止”；主动关闭单个运行页签会取消该回合，最后一个页签关闭后会自动打开空白页签。批量关闭仍保留其他运行中的任务。发送准备失败会显示错误并恢复可操作状态，不会一直停在“正在生成响应”。
- **沿用你自己的 AI 配置。** 接入 Codex / Claude Code CLI，也支持自定义供应商、模型、提示词、Agent 身份，以及 Codex 的 Skills 和 MCP。

## 看起来是什么样

以下为 **Checkout Review Demo** 独立 Java 示例项目的实拍，演示购物车折扣、免邮规则和边界测试。

### 从 ChangeList 开始审阅

左边的 **CodeDeck Changes** 按目录列出项目改动，双击文件打开原生 Diff，右边直接讨论代码。找改动、看差异、问原因，在一个工作区里完成。

![CodeDeck 示例工作区：变更树、原生 Diff 和代码讨论](docs/images/readme/review-workspace.jpg)

### AI 说明直接贴着改动

把折扣上限、免邮门槛和计算顺序讲清楚。说明文字可以选择、复制，也可以切换文件继续审阅。

![在原生 Diff 的代码行间阅读 AI 变更讲解](docs/images/readme/inline-annotations.jpg)

### 让 AI 补完，再自己确认

讨论之后可以直接让 AI 补测试。改了哪些文件、差异在哪里、是否保留，都有明确入口。

![AI 新增的边界测试、执行结果和修改审阅入口](docs/images/readme/ai-changes.jpg)

## 装好后，从一次 Review 开始

1. **安装插件 ZIP。** 当前版本为 `2.1.1`，尚未上架 Marketplace。在 IDE 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 `CodeDeck-2.1.1.zip`，然后重启 IDE。
2. **连接 CLI。** 先确保本机 `codex` 或 `claude` 已安装并能正常使用。需要指定可执行文件时，打开 **Settings → Tools → CodeDeck** 填写路径。
3. **打开变更。** 在 **View → Tool Windows → CodeDeck Changes** 中找到文件，双击打开 Diff，点击上方的 **AI 注释**。
4. **带着问题继续。** 打开右侧 **CodeDeck**，开启“文件上下文”，在 Diff 里选中代码，再提问。新建或切换聊天页签会沿用当前编辑器的文件与选区，无需切换文件刷新。让 AI 修改后，从对话的修改列表继续检查、接受或撤销。

可以从这些问题开始：

> 这次修改解决了什么问题？
>
> 这个判断为什么要放在这里？用一个具体输入说明。
>
> 给选中的逻辑补上边界测试，先不要改业务规则。

## 需要准备什么

- **JetBrains IDE 2024.3 或更新版本**，使用支持 JCEF 的运行时。
- **Codex CLI 或 Claude Code CLI**，以及你自己的模型服务配置；插件不附带 CLI、模型额度或账号。
- 使用 Claude 渠道时，需要 **Claude Code 2.1.210+**。由于 Claude 使用受限，相关功能测试较少，Claude 渠道仍在完善，当前建议优先使用 Codex。

AI 注释使用 **Codex / GPT 渠道**的当前供应商。单文件 Diff 注释、ChangeList 批量注释和聊天分别保存模型、思考程度与 Fast，互不覆盖；升级时注释配置会继承一次原有设置。注释独立运行，不会把请求塞进聊天历史。Git / Perforce 连接沿用 IDE 的现有配置。

## 供应商和常用设置

在右侧 CodeDeck 的设置页中，可以切换本机配置或添加自己的接口：

| 想做什么 | 去哪里 |
| --- | --- |
| 添加 GPT / Claude 接口、管理认证凭据 | 供应商 |
| 选择模型、推理强度、审批与工作区权限 | 聊天输入框下方工具栏 |
| 设置主题、字号、发送快捷键和通知 | 基础设置 |
| 保存审阅习惯、项目指令和角色 | 提示词库、Agent 身份 |
| 管理 Codex 技能和工具连接 | Skills、MCP |

自定义 GPT 接口支持 Responses API / Chat Completions API。认证凭据保存在 **JetBrains PasswordSafe**。修改供应商配置后，请开启新对话。

使用 Codex 本地配置时，聊天框与 Diff 的模型选择器共用本机 CLI 模型缓存，连接就绪后聊天框会刷新可用模型；新建页签也会加载当前渠道的模型列表。

Codex 的新增文件、修改、删除和补丁移动都可进入对话修改列表；命令中明确指定的小型子目录和脚本所在目录也支持路径拼接与批量文件操作。默认保持已捕获文件原有的 LF / CRLF 和 UTF-8 BOM；需要主动转换格式时，可在“设置 → 基础配置 → 行为”关闭“保持已有文件换行”。撤销直接恢复原始字节并刷新编辑器，支持 Windows 换行。重新打开历史时会从已完成的文件事件恢复可识别的修改。修改捕获经过独立项目中的真实模型操作验收，仍受文件大小与目录范围限制；详细说明见 [功能、配置与已知边界](docs/usage-details.md)。从独立 ChangeList 插件迁移时，请先禁用旧的 **VCS Changelist Sidebar**。

P4 `revert` 的明确文件路径和 Git `revert <单个提交>` 也可捕获；Git 按提交元数据查找受影响文件，排除 `.git` 等版本控制内部目录。版本控制恢复命令保留仓库输出的换行与 BOM；撤销支持恢复 P4 revert 后变为只读的文件。对话撤销恢复文件内容，不撤销 Git 提交或 P4 changelist 状态；无明确路径的 P4 命令、动态脚本和超限范围仍有捕获边界。

会话重命名使用 Codex 原生 `thread/name/set` 接口。Codex 桌面端的 `@` 引用选择器使用另一个 `thread/search` 搜索入口；本机核验发现，该入口可能按首句找到会话，却按已保存的标题找不到。插件历史列表与桌面端选择器的搜索结果因此可能不同，这一桌面端问题尚未解决。

## 从源码构建

需要 JDK 21。在仓库根目录执行：

```shell
./gradlew check buildPlugin
```

Windows 使用 `gradlew.bat`。产物为 `build/distributions/CodeDeck-2.1.1.zip`；开发运行使用 `./gradlew runIde`。

GitHub Actions 会在 push 和 Pull Request 时运行检查、打包与插件兼容性验证；历史会话的浏览器回归按请求类型核对操作，避免后台布局通知干扰结果。

本仓库只维护 JetBrains 插件。测试、兼容性验证和工程说明见 [开发文档](docs/development.md)，版本变化见 [CHANGELOG](CHANGELOG.md)。

## 开源许可

[MIT License](LICENSE)。CodeDeck 原创部分版权归 `MengXingTong` 所有。部分界面设计与实现参考 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，版权与许可声明随源码和插件一同保留。
