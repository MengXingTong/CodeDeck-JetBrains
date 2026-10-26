# CodeDeck

English | [简体中文](README.zh-CN.md)

Current build: **2.1.5**.

**Understand and review code in JetBrains IDEs, with AI assistance.**

CodeDeck helps you inspect changes, understand the code through AI explanations and follow-up questions, and decide which edits to keep.

## Features

- **Understand each change.** Read AI explanations beside changed code to support your review. Generate annotations for one file or a batch.
- **Project ChangeList.** Browse Git / Perforce changes in the left sidebar; double-click a file to open its diff.
- **Chat with code context.** Select code in a diff, drag files or folders into chat, or reference them with `@`.
- **Changes per conversation.** Review AI edits in the chat's file list, open diffs, and accept or undo changes while keeping your scroll position.
- **Line ending protection.** Preserve captured files' existing LF / CRLF and UTF-8 BOM by default. Disable it in Behavior settings when converting formats.
- **Your models and tools.** Use Codex / Claude Code, custom providers, parallel chats, queued messages, and reusable prompts. Codex also supports steering a running task, Skills, and MCP.

The interface supports English and Simplified Chinese and follows the IDE language after restart. Other IDE languages use English.

File context follows the active IDE editor across new and switched chat tabs, including selected line ranges and diff versions. Each tab keeps its own draft. Close the editor or remove the file context to clear it.

During a running task, Send and the send shortcut add messages to the ordered queue above the input box. Edit or delete any unsent message, or click **Steer now** on the first item to guide the current Codex turn immediately. An item being edited waits for Save or Cancel before sending; files, attachments, and captured editor context are preserved. Messages enter the conversation when sent.

Command fallback capture filters logs, build outputs, and paths excluded by the project's **ChangeList → Ignore list / resume tracking** rules. Known read-only commands do not claim concurrent file changes. Explicit patches, file edits, and recognized writes to literal file paths remain captured, including ignored files. Rule changes apply to subsequent turns.

Inline Python scripts can also use file-name templates such as `f'Source/Ability/G{kind}GameplayAbility.cpp'`. Capture matches the template within its fixed subdirectory, preserving the original contents for Diff and undo. Long scripts in Code Mode tool calls are supported; matching keeps the same capture limits and ignore rules.

Codex history opens as a saved snapshot without claiming the original chat. Use **Refresh history** to read updates, **Continue original chat** to resume it when available, or **Copy to a new chat** to continue independently while another client holds the original. These actions preserve your input draft and attached files.

CodeDeck stores Codex chats in `$CODEX_HOME/codedeck/jetbrains` (default: `~/.codex/codedeck/jetbrains`), with separate session logs, databases and writer locks. New plugin chats stay out of the Codex desktop sidebar. On first use, local configuration and cached credentials are imported once; subsequent configuration and login changes are independent. Skills and plugin assets are shared. A bundled read-only `codedeck_history` MCP server lets the desktop agent search and read saved plugin chats; restart the desktop after its first registration. Native desktop `@chat` references are no longer supported for these chats. See [migration instructions](docs/usage-details.md#codex-会话独立存储) for existing plugin history.

## Screenshots

From the standalone **Checkout Review Demo** Java project. Red boxes mark the featured controls.

### Project changes and code discussion

The **left ChangeList** opens native diffs. The **right chat** uses the selected file and code to answer follow-up questions.

![Red boxes highlight the left project ChangeList and the right chat panel](docs/images/readme/review-workspace.png)

### Read explanations alongside the code

Use the **toolbar above the diff** to choose a model and generate annotations. While a single-file task is running, its status and background task title show only the file name. Read the annotations alongside the code to check the logic and impact of each change.

![Red boxes highlight the AI annotation toolbar and an explanation inside the diff](docs/images/readme/inline-annotations.png)

### Review AI edits from chat

The **chat ChangeList** shows files changed in this conversation, line counts, and accept / undo controls. Click a file to inspect its diff.

![Red boxes highlight the expanded chat ChangeList and its review controls](docs/images/readme/conversation-changes.png)

## Get started

1. Download the plugin ZIP from the [latest release](https://github.com/MengXingTong/CodeDeck-JetBrains/releases/latest). In the IDE, open **Settings → Plugins → gear icon → Install Plugin from Disk**, select the ZIP, and restart.
2. Install and configure **Codex CLI** or **Claude Code CLI**. Set an executable path in **Settings → Tools → CodeDeck** if needed.
3. Open **View → Tool Windows → CodeDeck Changes**, double-click a changed file, and click **AI annotations** above the diff.
4. Open **CodeDeck** on the right and enable **File context**. Select code in the diff to ask about its logic and impact. If you ask AI to edit it, review the results in the chat's ChangeList.

For example: “Explain the logic and impact of this change” or “Walk through the edge cases with concrete inputs.”

## Requirements and settings

- **JetBrains IDE 2024.3+** with a JCEF-capable runtime.
- **Codex CLI or Claude Code CLI** and your own model service. Claude requires **Claude Code 2.1.210+**; it has received less testing, so Codex is recommended.
- AI diff annotations use the **Codex / GPT provider**. Chat, single-file annotations, and batch annotations save their model preferences separately. Git / Perforce reuse the IDE's connections.

| Setting | Where to find it |
| --- | --- |
| Provider endpoints and credentials | CodeDeck settings → Providers |
| Model, reasoning effort, approvals, workspace access | Toolbar below the chat composer |
| Theme, font, shortcuts, notifications, line endings | CodeDeck settings → General |
| Reusable instructions and tools | Prompt library, Agent identities, Skills, MCP |

Custom GPT providers support Responses API and Chat Completions API. Credentials are stored in **JetBrains PasswordSafe**. Start a new conversation after changing provider configuration.

See [Usage and known limits (Chinese)](docs/usage-details.md) for change capture, undo, and provider details. When migrating from the standalone ChangeList plugin, disable **VCS Changelist Sidebar** first.

## Build from source

Requires **JDK 21**. From the repository root:

```shell
./gradlew check buildPlugin verifyPlugin
```

On Windows, use `gradlew.bat`. The ZIP is written to `build/distributions/CodeDeck-<version>.zip`. Use `./gradlew runIde` to launch a development IDE.

[Development guide (Chinese)](docs/development.md) · [Changelog](CHANGELOG.md) · [Releases](https://github.com/MengXingTong/CodeDeck-JetBrains/releases)

## License

[MIT License](LICENSE). Original CodeDeck contributions are copyright `MengXingTong`. Parts of the interface design and implementation reference the MIT-licensed [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui). Its copyright and license notices are retained in the source and plugin distribution.
