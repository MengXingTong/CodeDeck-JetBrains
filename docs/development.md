# 开发与验证

## 从源码运行

根工程提供 JetBrains 插件，`shared-core` 是其内部纯 Java 模块，负责会话模型、协议和文件修改等通用逻辑。插件构建只依赖本仓库源码；桌面端在独立目录维护自己的核心与前端源码副本。

在项目根目录执行：

```shell
# Linux/macOS
./gradlew runIde

# Windows PowerShell
./gradlew.bat runIde
```

运行测试并构建插件包：

```shell
./gradlew clean check buildPlugin verifyPlugin
```

Windows 也可以使用一键打包脚本：

```powershell
.\tools\package.ps1
```

脚本会执行清理、测试、插件构建和兼容性验证，成功后输出发行包路径、文件大小和 SHA-256。构建产物位于 `build/distributions/CodeDeck-<version>.zip`。

Gradle 会下载项目锁定的 Node.js，并自动执行 TypeScript 类型检查、Vitest、Playwright Chromium 回归和 esbuild。首次运行浏览器测试时，Playwright 会下载测试用 Chromium。

`CodexTitleGeneratorLiveTest` 为显式启用的真实标题生成与名称持久化检查：将 `CODEDECK_REAL_TITLE_CODEX` 设为本机 `codex.exe` 完整路径后，执行 `./gradlew :test --tests com.codexgui.service.CodexTitleGeneratorLiveTest`。它会发起一次真实模型请求，并归档用于验证名称读写的测试会话；未设置该变量时跳过。

`CodexFileReferenceLiveTest` 验证引用能否被模型实际读取：将 `CODEDECK_REAL_REFERENCE_CODEX` 设为本机 `codex.exe` 完整路径后，执行 `./gradlew :test --tests com.codexgui.service.CodexFileReferenceLiveTest`。它通过临时只读会话发起一次真实请求，引用含中文和空格路径的临时文件，检查模型返回只存于文件中的随机值；未设置该变量时跳过。

`AiAnnotationLayoutIntegrationTest` 在真实 IntelliJ 编辑器中检查注释占位、源码行坐标、换行、字体缩放、水平滚动和折叠：设置 `CODEDECK_REAL_EDITOR_LAYOUT=1` 后，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.changelist.ai.AiAnnotationLayoutIntegrationTest`。此独立检查加载完整 IDE 测试运行时，不请求模型；普通测试默认跳过。

`WorkspaceDropIntegrationTest` 检查 ChangeList 实际导出的文件经过原生 IDE 拖放管理器后进入聊天输入框：设置 `CODEDECK_REAL_IDE_DND=1`，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.ui.WorkspaceDropIntegrationTest`。覆盖中文与空格路径、已删除文件、图片附件、落点、重复拖入及离开时清理，不请求模型；普通测试默认跳过。

`ConversationConnectionIntegrationTest` 在真实 IDE 事件队列中重放首条消息先启动、“已连接”通知后到达的顺序：设置 `CODEDECK_REAL_CONNECTION_STATE=1`，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.ui.ConversationConnectionIntegrationTest`。检查首条回复与会话绑定、重复连接通知、真正断开时的清理及 Claude 隔离，不启动 CLI 或请求模型；普通测试默认跳过。

## 持续集成与工程结构

仓库在每次 push 和 Pull Request 上执行插件验证流程，包括：

- `clean check`
- `buildPlugin`
- `verifyPlugin`
- Java 单元测试
- TypeScript 类型检查
- Vitest 测试
- Playwright Chromium 输入框回归
- 协议 fixture 与插件兼容性检查

前端使用 TypeScript、esbuild 和锁定依赖构建。页面通过版本化 Bridge v1 envelope 与 Java 侧通信，未知版本、未知类型或缺失身份字段会返回结构化 `protocol.error`。

后端控制器采用粗粒度职责边界：工具窗控制器只保留依赖组装、Bridge 命令路由和少量 IDE 上下文桥接，修改审阅、工作区输入、供应商与设置、Codex 扩展能力、Bridge 事件展示及会话编排分别由独立协调器负责。输入队列、立即引导、Codex/Claude 回合事件、审批和通知也统一归入会话协调器；完整阶段与验收标准见 [控制器职责拆分](controller-refactoring-plan.md)。


界面结构与样式说明见 [界面规格](cc-gui-visual-spec.md)。
