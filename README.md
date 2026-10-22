# CodeDeck

English | [简体中文](README.zh-CN.md)

**AI-powered code review for JetBrains IDEs.**

Source: [CodeDeck-JetBrains](https://github.com/MengXingTong/CodeDeck-JetBrains).

CodeDeck brings change lists, native diffs, AI explanations, and chat into your IDE. Whether the code was written by you, a teammate, or AI, inspect the changes, ask why they were made, and decide what to keep.

The interface supports **English and Simplified Chinese** and follows your JetBrains IDE's UI language. Non-Chinese locales use English. After changing the IDE language and restarting, chat, native panels, and AI diff explanations use the corresponding language. Messages, file names, code, and saved custom prompts keep their original text.

## What you can do

- **Read AI explanations beside each changed block.** Click **AI annotations** to explain what changed, why, and what it affects, directly beside the relevant code. Review the diff and its explanation together.
- **Select code and ask a follow-up.** Select a section in a diff to discuss its logic, explore edge cases, or ask AI to add tests. The file, line numbers, and selection travel with your question.
- **Review project changes in one place.** Browse Git / Perforce changes in the change tree and double-click a file to open its native IDE diff. Generate AI annotations for multiple files at once.
- **Review AI edits individually.** Each conversation has its own list of captured changes, with diffs, accept, and undo actions so you can see exactly which files changed.
- **Preserve LF / CRLF line endings.** Captured files keep their original LF / CRLF and UTF-8 BOM by default, reducing whole-file formatting noise. Turn this protection off in Behavior settings when intentionally converting line endings.
- **Drag files or folders into chat.** Drop files and directories from the change tree or project tree, or search with `@`. Images can be sent as attachments. ChangeList drag-and-drop targets the chat composer; it does not open files in the central code editor.
- **Work on several tasks at once.** Independent chat tabs support queued messages. Codex also accepts steering messages during execution. You can stop a task during startup. Closing a running tab cancels its turn; closing the last tab opens a blank one. Bulk close keeps running tabs open. Preparation failures show an error and restore the controls.
- **Use your own AI setup.** Connect Codex / Claude Code CLI and configure providers, models, prompts, agent identities, Codex Skills, and MCP.

## See it in action

These screenshots show the Chinese interface in **Checkout Review Demo**, a standalone Java project covering shopping cart discounts, free shipping rules, and boundary tests.

### Start with the change list

**CodeDeck Changes** on the left groups changes by directory. Double-click a file for its native diff and discuss the code on the right, all in the same workspace.

![CodeDeck workspace with change tree, native diff, and code discussion](docs/images/readme/review-workspace.jpg)

### Explanations stay beside the changes

Understand discount caps, shipping thresholds, and calculation order. Select and copy explanation text, or switch files to continue reviewing.

![AI explanations displayed between code lines in a native diff](docs/images/readme/inline-annotations.jpg)

### Let AI finish, then review its work

Ask AI to add tests after the discussion, then inspect the changed files and decide which edits to keep.

![AI-generated boundary tests, execution results, and change review controls](docs/images/readme/ai-changes.jpg)

## Start your first review

1. **Install the plugin ZIP.** The current version is `2.1.1`; it is not yet listed on JetBrains Marketplace. In your IDE, open **Settings → Plugins → gear icon → Install Plugin from Disk**, select `CodeDeck-2.1.1.zip`, and restart.
2. **Connect a CLI.** Install and configure `codex` or `claude` locally first. To specify an executable, open **Settings → Tools → CodeDeck**.
3. **Open your changes.** Go to **View → Tool Windows → CodeDeck Changes**, double-click a file, and click **AI annotations** above its diff.
4. **Keep asking questions.** Open **CodeDeck** on the right, enable **File context**, select code in a diff, and ask your question. New or switched tabs reuse the active editor file and selection automatically. After AI makes edits, inspect, accept, or undo them in the conversation's changes panel.

Try questions like:

> What problem does this change solve?
>
> Why does this condition belong here? Explain with a concrete input.
>
> Add boundary tests for the selected logic without changing the business rules.

## Requirements

- **JetBrains IDE 2024.3 or later** with a JCEF-capable runtime.
- **Codex CLI or Claude Code CLI**, with your own model service configuration. The plugin does not include a CLI, model credits, or an account.
- The Claude channel requires **Claude Code 2.1.210+**. Claude functionality has received less testing because of access limitations and is still being improved. Codex is currently recommended.

AI annotations use the active provider in the **Codex / GPT channel**. Single-file diff annotations, batch ChangeList annotations, and chat save their model, reasoning effort, and Fast preferences independently. On upgrade, annotation preferences inherit the old settings once. Annotation requests run separately from chat history. Git / Perforce use the IDE's existing connections.

## Providers and settings

Open Settings in the CodeDeck panel to use local configuration or add your own endpoint:

| To do this | Open |
| --- | --- |
| Add GPT / Claude endpoints and manage credentials | Providers |
| Select model, reasoning effort, approvals, and workspace access | Toolbar below the chat composer |
| Configure theme, font size, send shortcut, and notifications | General |
| Save review preferences, instructions, and roles | Prompt library, Agent identities |
| Manage Codex skills and tool connections | Skills, MCP |

Custom GPT endpoints support Responses API / Chat Completions API. Credentials are stored in **JetBrains PasswordSafe**. Start a new conversation after changing provider configuration.

With local Codex configuration, chat and diff model selectors share the local CLI model cache. Chat refreshes available models when connected; new tabs also load the current channel's model list.

Codex file additions, modifications, deletions, and patch moves can appear in the conversation's changes panel. Explicit small subdirectories and script directories also support joined paths and batch file operations. Captured files retain their original LF / CRLF and UTF-8 BOM by default; disable **Settings → General → Behavior → Preserve existing line endings** before intentionally converting formats. Undo restores original bytes and refreshes the editor, including Windows line endings. Reopened history restores recognizable changes from completed file events. Change capture has been validated with real model operations in a separate project, but file size and directory scope limits still apply. See [Features, configuration, and known limits (Chinese)](docs/usage-details.md). When migrating from the standalone ChangeList plugin, first disable **VCS Changelist Sidebar**.

Explicit P4 `revert` paths and Git `revert <single commit>` are also captured. Git uses commit metadata to identify affected files and excludes internal directories such as `.git`. VCS restore commands keep the repository's line endings and BOM. Undo can restore files made read-only by P4 revert. Conversation undo restores file content; it does not undo Git commits or P4 changelist state. P4 commands without explicit paths, dynamic scripts, and operations outside the size or scope limits still have capture limitations.

Conversation renaming uses the native Codex `thread/name/set` API. The Codex desktop app's `@` reference picker uses the separate `thread/search` endpoint. Local checks found that this endpoint can match the first message but miss the saved title, so plugin history and the desktop picker may return different results. This desktop search issue remains unresolved.

## Build from source

Requires JDK 21. Run from the repository root:

```shell
./gradlew check buildPlugin
```

On Windows, use `gradlew.bat`. The plugin ZIP is `build/distributions/CodeDeck-2.1.1.zip`. Run a development IDE with `./gradlew runIde`.

GitHub Actions runs checks, packaging, and plugin compatibility verification on pushes and pull requests. Browser regressions for conversation history validate actions by request type so background layout notifications do not interfere with results.

This repository maintains the JetBrains plugin. See the [development guide (Chinese)](docs/development.md) for testing, compatibility checks, and project structure, and [CHANGELOG](CHANGELOG.md) for release history.

## License

[MIT License](LICENSE). Original CodeDeck contributions are copyright `MengXingTong`. Parts of the interface design and implementation reference the MIT-licensed [CC GUI](https://github.com/zhukunpenglinyutong/jetbrains-cc-gui). Its copyright and license notices are retained in the source and plugin distribution.
