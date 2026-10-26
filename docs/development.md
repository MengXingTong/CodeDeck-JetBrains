# 开发与验证

## 从源码运行

根工程提供 JetBrains 插件，`shared-core` 是其内部纯 Java 模块，负责会话模型、协议和文件修改等通用逻辑。插件构建只依赖本仓库源码；桌面端在独立目录维护自己的核心与前端源码副本。

`CodexSessionStorageTest` 验证首次配置继承、后续登录/配置独立与工具资源共用；`CodeDeckHistoryMcpTest` 验证只读工具、标题检索、长元数据、分页、运行中残行和路径边界。`historyBridgeJar` 将只读入口与 Gson 打成自带 Java 可运行的独立 JAR，插件连接时导出注册。可运行 `python tools/test-session-isolation.py --codex <本机 codex 可执行文件>`，使用本地 HTTP 模型 fixture 和真实 app-server 验证：跳过占用会话、按来源迁移、原生回合及模型上下文保留、新建/恢复独立会话、桌面端扫描不发现插件会话、原配置与桌面会话保留。不会请求外部模型。

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

`CodexChangeCaptureLiveTest` 在独立项目中让真实模型逐组执行文件变更，并通过原生 app-server 事件调用插件生产捕获代码。启用方式：设置 `CODEDECK_REAL_CAPTURE_CODEX` 为本机 `codex.exe` 的完整路径，执行 `./gradlew :test --tests com.codexgui.ui.CodexChangeCaptureLiveTest`。它会实际请求模型并写入专用测试项目，普通 `check` 未设置变量时跳过。默认结果保存到仓库相邻的 `CodeDeck-Capture-Lab/<UTC时间>/`，可用 `CODEDECK_CAPTURE_LAB` 指定其他位置；`project/` 保留模型修改后的文件，`events.jsonl` 保留原始协议，`report.md` 和 `report.json` 逐组列出独立磁盘快照与捕获结果、遗漏、误报、修改前后字节不符、未执行项及实际撤销后的差异。报告由测试进程生成，不能以模型声称完成代替验收。覆盖补丁新增/更新/删除/移动（包括覆盖已有目标）、PowerShell cmdlet/重定向、.NET、Node、Python、cmd、中文与空格重命名、空文件、二进制、动态路径、原子替换、目录复制/移动/递归删除、外部脚本、同一文件连续修改、失败命令已落盘的部分修改和纯读取。此有限矩阵用于发现真实工具链的遗漏，不代表任意外部程序、超限目录或并发用户编辑均可无损捕获。

同时有其他构建使用当前工作区时，文件捕获验收可使用独立构建输出：`./gradlew --no-configuration-cache -I tools/capture-validation.init.gradle :test --tests com.codexgui.ui.CodexChangeCaptureLiveTest`。设置 `CODEDECK_CAPTURE_SCENARIOS` 为英文逗号分隔的场景名可单独复验，例如 `rename-paths,patch-overwrite`；不设置时运行完整矩阵。

其中 `line-endings` 场景让真实模型故意通过 shell 和补丁置换已有文件的 LF / CRLF、移除 UTF-8 BOM；测试独立检查恢复原格式后仍保留代码修改，以及仅换行变化不产生净差异，并继续核对原始基线与实际撤销结果。

真实 VCS 复验使用 `tools/setup-vcs-capture-fixture.ps1 -P4dPath <本地 p4d.exe> -RunId <新名称>` 准备隔离的 Helix Core 2026.1 服务和 Git 仓库。它只监听随机 localhost 端口，不使用业务 P4 服务；服务根目录、测试仓库、PID、端口和独立 ticket 文件记录在输出的 `fixture.json`。设置 `CODEDECK_CAPTURE_VCS=1`、`CODEDECK_CAPTURE_RUN_ID=<同一名称>`、`CODEDECK_CAPTURE_P4PORT=<fixture.p4port>`、`P4TICKETS=<fixture.tickets>` 与 `CODEDECK_REAL_CAPTURE_CODEX` 后运行同一验收测试，覆盖四文件 P4 revert、Git 单提交 revert、仓库换行、只读文件撤销、内部数据库排除及逐字节恢复。测试后核对 PID 的可执行文件确为 fixture 中的 p4d，再停止该测试进程；保留报告目录。对话撤销只恢复工作文件，不回退 Git 提交与 P4 服务状态。

独立构建脚本同时将浏览器预览绑定到该构建实际打包的前端文件，并自动选择临时空闲端口，避免浏览器回归误读默认构建的旧包、端口冲突或复用其他预览进程；需要固定端口时设置 `CODEDECK_TEST_PREVIEW_PORT`。普通手动预览仍默认使用 4173 端口。

`src/test/browser/prompt-ime.spec.ts` 使用 Chromium 的 `Input.imeSetComposition` 驱动原生组合编辑，覆盖 `wen → we → w → 空`、三次 Backspace、已有文字、后台更新和中文提交后立即发送。提前 `compositionend` 的分支注入宿主通知先于最后一次原生取消的时序，验证页面重绘不会抢先移走输入框。此测试不等同于直接驱动 Windows 微软拼音的候选窗；微软拼音在 Rider / JCEF 中的最终体验仍应通过安装包手动核对。

`AiAnnotationLayoutIntegrationTest` 在真实 IntelliJ 编辑器中检查注释占位、源码行坐标、换行、字体缩放、水平滚动和折叠：设置 `CODEDECK_REAL_EDITOR_LAYOUT=1` 后，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.changelist.ai.AiAnnotationLayoutIntegrationTest`。此独立检查加载完整 IDE 测试运行时，不请求模型；普通测试默认跳过。

`ConversationChangeTrackerIdeTest` 验证真实 VFS、打开的编辑器文档与磁盘字节的撤销结果：设置 `CODEDECK_REAL_IDE_ROLLBACK=1`，执行 `./gradlew --no-configuration-cache -I tools/capture-validation.init.gradle -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.service.ConversationChangeTrackerIdeTest`。覆盖单项 / 全部撤销、CRLF、LF、混合换行、UTF-8 BOM 与未保存编辑；可用 `CODEDECK_ROLLBACK_BEFORE` 和 `CODEDECK_ROLLBACK_AFTER` 指定四个 NPC 文件的副本目录复现真实问题。此检查不修改业务项目、不请求模型，普通测试默认跳过。

`WorkspaceDropIntegrationTest` 检查 ChangeList 实际导出的文件和目录经过原生 IDE 拖放管理器后进入聊天输入框：设置 `CODEDECK_REAL_IDE_DND=1`，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.ui.WorkspaceDropIntegrationTest`。覆盖中文与空格路径、目录引用、已删除文件、图片附件、落点、重复拖入及离开时清理，同时验证原生代码编辑器拒绝打开文件或插入路径文字，不请求模型；普通测试默认跳过。

`ConversationConnectionIntegrationTest` 在真实 IDE 事件队列中重放首条消息先启动、“已连接”通知后到达的顺序：设置 `CODEDECK_REAL_CONNECTION_STATE=1`，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.ui.ConversationConnectionIntegrationTest`。检查首条回复与会话绑定、重复连接通知、真正断开时的清理及 Claude 隔离；同时验证运行中消息只排队、后台按顺序出队时才显示、文件引用与附件快照、编辑暂停与保存、稳定身份的删除及迟到操作保护、供应商消息回推去重。通过内存 JSON-RPC 传输检查队首引导请求、重复点击和失败恢复，不启动 CLI 或请求模型；普通测试默认跳过。

`src/test/browser/queued-inputs.spec.ts` 覆盖输入框上方的消息队列、队首文字引导、编辑/取消/删除、主输入与跨页签草稿隔离、后台刷新期间的编辑文本和光标、中文组合输入，以及窄栏与英文布局。

`BridgeEventPresenterTest`、`store.test.ts` 和 `diff-context.spec.ts` 验证编辑器上下文独立于设置回推：新建、切换页签以及前后台迟到的空/旧设置快照不覆盖当前文件、行号和 Diff 版本标签；浏览器回归同时检查跨页签草稿、关闭文件、移除上下文和重新开启。页面初始化与开关上下文使用独立的 `fileContext` 事件，设置 `bootstrap` 不携带编辑器状态。

## 持续集成与工程结构

### 界面国际化

界面默认跟随 `DynamicBundle.getLocale()` 返回的 IDE 语言：`zh` 系列使用简体中文，其余使用英文。Java 原生界面通过 `UiMessages.message` 读取文案，共享核心通过 `Messages.message` 使用同一语言；JCEF 加载前注入 `window.codedeckLocale`，bootstrap 同步 `uiLocale`。英文资源集中在 `shared-core/src/main/resources/i18n/en.json`，中文源文案作为资源键，前后端共用资源，新增或修改文案时同步维护。无 IDE 应用实例时按 JVM locale 运行，便于独立测试。

前端普通静态文案用 `t('中文文案')`，含 HTML 或插值的模板用 `ui` 标签；仅翻译模板静态片段，插值中的用户消息、代码、路径、供应商名称及已保存提示词不参与翻译。不要对渲染后的 DOM 或整条模型消息做文本替换。代码中的凭据服务名、协议字段、存储标识与历史解析模式须保持稳定；历史命令状态同时识别中英文标记，新会话自动标题同时兼容两种默认名称。Diff 注释提示词按界面语言选择，聊天回复语言仍由用户输入和指令决定。

`MessagesTest`、`i18n.test.ts` 和浏览器国际化回归覆盖语言回退、静态模板边界、历史状态、中文用户内容与英文设置。既有 Java 记录 fixture 固定使用中文测试 locale，新语言用例显式传入 locale。手动预览英文界面可打开 `/tools/ui-preview.html?lang=en`。README 的默认版本为英文，中文版本为 `README.zh-CN.md`；两者应同步更新。

`ProviderModelOptionsIntegrationTest` 在真实 IDE 事件队列中验证聊天模型缓存、页签复用、供应商隔离、接口目录优先级和旧项目提示词迁移：设置 `CODEDECK_REAL_MODEL_OPTIONS=1`，执行 `./gradlew --no-configuration-cache -I tools/annotation-layout-test.init.gradle :test --tests com.codexgui.ui.ProviderModelOptionsIntegrationTest`。不启动 CLI 或请求模型；普通测试默认跳过。

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
