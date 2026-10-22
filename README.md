# CodeDeck

** AI时代的Review神器 For JetBrains。**

CodeDeck 把变更列表、原生 Diff、AI 讲解和对话放在同一个 IDE 里。无论代码是你写的、同事写的，还是 AI 刚改的，都能对着代码看变化、问原因，再决定留下哪些修改。

## 能做什么

- **让 AI 给 Diff 逐段打注释。** 点击“AI 注释”，AI 会逐个代码段分析修改，把“改了什么、为什么这样改、会影响什么”直接标在对应代码段旁边。边看差异边读注释，不用在聊天记录和代码之间反复对照。
- **选中代码，接着问。** 在 Diff 中选一段代码，就能追问逻辑、讨论边界条件，或者让 AI 补测试。文件、行号和选区会一起带过去。
- **集中审阅项目改动。** 用变更树查看 Git / Perforce 等版本控制中的修改，双击就进 IDE 原生 Diff；也可以批量生成 AI 注释。
- **逐项确认 AI 写的代码。** 每个对话都有自己的修改列表，支持查看差异、接受和撤销，不用靠聊天回复猜它到底动了哪些文件。
- **把文件直接拖进聊天。** 从变更树或项目树拖入文件，也可以用 `@` 搜索引用；图片可直接作为附件发送。
- **几个任务同时做。** 多个对话页签独立运行，支持排队发送；Codex 还可以在执行过程中接收新的引导。
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

1. **安装插件 ZIP。** 当前版本为 `2.0`，尚未上架 Marketplace。在 IDE 的 **Settings → Plugins → 齿轮 → Install Plugin from Disk** 中选择 `CodeDeck-2.0.zip`，然后重启 IDE。
2. **连接 CLI。** 先确保本机 `codex` 或 `claude` 已安装并能正常使用。需要指定可执行文件时，打开 **Settings → Tools → CodeDeck** 填写路径。
3. **打开变更。** 在 **View → Tool Windows → CodeDeck Changes** 中找到文件，双击打开 Diff，点击上方的 **AI 注释**。
4. **带着问题继续。** 打开右侧 **CodeDeck**，开启“文件上下文”，在 Diff 里选中代码，再提问。让 AI 修改后，从对话的修改列表继续检查、接受或撤销。

可以从这些问题开始：

> 这次修改解决了什么问题？有没有漏掉的边界条件？
>
> 这个判断为什么要放在这里？用一个具体输入说明。
>
> 给选中的逻辑补上边界测试，先不要改业务规则。

## 需要准备什么

- **JetBrains IDE 2024.3 或更新版本**，使用支持 JCEF 的运行时。
- **Codex CLI 或 Claude Code CLI**，以及你自己的模型服务配置；插件不附带 CLI、模型额度或账号。
- 使用 Claude 渠道时，需要 **Claude Code 2.1.210+**。Claude 渠道仍在完善，当前建议优先使用 Codex。

AI 注释使用 **Codex / GPT 渠道**的当前供应商和模型，独立运行，不会把注释请求塞进聊天历史。Git / Perforce 连接沿用 IDE 的现有配置。

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

修改捕获和撤销有文件大小与工具范围限制；详细说明见 [功能、配置与已知边界](docs/usage-details.md)。从独立 ChangeList 插件迁移时，请先禁用旧的 **VCS Changelist Sidebar**。

## 从源码构建

需要 JDK 21。在仓库根目录执行：

```shell
./gradlew check buildPlugin
```

Windows 使用 `gradlew.bat`。产物为 `build/distributions/CodeDeck-2.0.zip`；开发运行使用 `./gradlew runIde`。

本仓库只维护 JetBrains 插件。测试、兼容性验证和工程说明见 [开发文档](docs/development.md)，版本变化见 [CHANGELOG](CHANGELOG.md)。

## 开源许可

[MIT License](LICENSE)。CodeDeck 原创部分版权归 `MengXingTong` 所有。部分界面设计与实现参考 MIT 许可的 [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui)，版权与许可声明随源码和插件一同保留。
