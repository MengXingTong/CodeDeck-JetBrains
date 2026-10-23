import {expect, test} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>, request?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

test('新建页签的设置回推和后台旧快照不能覆盖当前文件上下文', async ({page}) => {
  await page.goto('/tools/ui-preview.html?context');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: 'Source/File.cpp', startLine: 0, endLine: 0,
    selectionLabel: 'before:L2-L3, after:L2-L4',
  })));
  await editor.fill('原页签草稿');
  await page.locator('[data-action="newTab"]').click();
  await editor.fill('新页签草稿');
  const sessionId = await page.locator('[data-session-tab].active').getAttribute('data-session-tab');
  const context = page.locator('.context-item:has([data-action="clearFileContext"]) .context-text');

  for (const target of [sessionId!, 'default']) {
    // 重放新建/激活后的设置刷新：旧逻辑会用未捕获到编辑器时的空值清掉标签。
    await page.evaluate(id => CodexGui.receive(previewEnvelope('bootstrap', {state: {
      sendOpenedFilePath: true, activeFile: '', activeFileStartLine: 0,
      activeFileEndLine: 0, activeFileSelectionLabel: '',
    }}, {sessionId: id})), target);
    await expect(context).toHaveText('File.cpp · before:L2-L3, after:L2-L4');
    await expect(editor).toHaveText('新页签草稿');
  }
  await page.locator('[data-session-tab="default"]').click();
  await expect(editor).toHaveText('原页签草稿');
  await expect(context).toHaveText('File.cpp · before:L2-L3, after:L2-L4');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('bootstrap', {state: {
    activeFile: 'Old.cpp', activeFileStartLine: 1, activeFileEndLine: 2,
    activeFileSelectionLabel: 'before:L1-L2',
  }})));
  await expect(context).toHaveText('File.cpp · before:L2-L3, after:L2-L4');

  // 真正切换或关闭编辑器仍以独立的 fileContext 事件为准。
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {path: 'Other.cpp'})));
  await expect(context).toHaveText('Other.cpp');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {path: ''})));
  await expect(context).toHaveCount(0);
});

test('Diff 选区显示原版本行号，切换普通文件时清除版本标签', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  const fileContext = page.locator('.context-item:has([data-action="clearFileContext"])');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: 'Source/<example>/File.cpp', startLine: 0, endLine: 0,
    selectionLabel: 'before:L2-L3, after:L2-L4',
  })));
  await expect(fileContext.locator('.context-text')).toContainText('File.cpp · before:L2-L3, after:L2-L4');
  await expect(fileContext).toHaveAttribute('title', 'Source/<example>/File.cpp · before:L2-L3, after:L2-L4');
  await expect(fileContext.locator('example')).toHaveCount(0);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {path: 'Other.cpp', startLine: 7, endLine: 9})));
  await expect(fileContext.locator('.context-text')).toHaveText('Other.cpp · L7-L9');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {path: '', startLine: 0, endLine: 0})));
  await expect(fileContext).toHaveCount(0);
});

test('新建页签后，前一页签发出的当前文件更新立即显示', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  await page.locator('[data-action="newTab"]').click();
  await page.getByRole('textbox', {name: '消息'}).fill('新页签草稿');
  const activeTab = page.locator('[data-session-tab].active');
  const sessionId = await activeTab.getAttribute('data-session-tab');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: 'Source/LevelPreviewerUIController.cpp', startLine: 7, endLine: 9,
  })));
  const context = page.locator('.context-item:has([data-action="clearFileContext"])');
  await expect(context.locator('.context-text')).toHaveText('LevelPreviewerUIController.cpp · L7-L9');
  await expect(page.getByRole('textbox', {name: '消息'})).toHaveText('新页签草稿');
  await expect(activeTab).toHaveAttribute('data-session-tab', sessionId!);
  await page.locator('[data-close-session="default"]').click();
  // IDE 文件上下文属于整个工作区；已关闭页签发出的迟到更新也不能被丢弃。
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: 'Source/Other.cpp', startLine: 0, endLine: 0,
  })));
  await expect(context.locator('.context-text')).toHaveText('Other.cpp');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: '', startLine: 0, endLine: 0,
  })));
  await expect(context).toHaveCount(0);
});

test('当前文件和 Diff 选区跨新建、切换页签保留，草稿相互独立', async ({page}) => {
  await page.goto('/tools/ui-preview.html?context');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('fileContext', {
    path: 'Source/LevelPreviewerUIController.cpp', startLine: 0, endLine: 0,
    selectionLabel: 'before:L2-L3, after:L2-L4',
  })));
  await editor.fill('原页签草稿');
  await page.locator('[data-action="newTab"]').click();
  const context = page.locator('.context-item:has([data-action="clearFileContext"]) .context-text');
  await expect(context).toHaveText('LevelPreviewerUIController.cpp · before:L2-L3, after:L2-L4');
  await expect(editor).toBeEmpty();
  await editor.fill('新页签草稿');
  await page.locator('[data-session-tab="default"]').click();
  await expect(editor).toHaveText('原页签草稿');
  await expect(context).toHaveText('LevelPreviewerUIController.cpp · before:L2-L3, after:L2-L4');
});

test('移除上下文后新页签保持关闭，重新开启会捕获当前文件', async ({page}) => {
  await page.goto('/tools/ui-preview.html?context');
  const context = page.locator('.context-item:has([data-action="clearFileContext"])');
  await expect(context.locator('.context-text')).toContainText('BoundaryDreamlandClientConnectionManager.java');
  await page.locator('[data-action="clearFileContext"]').click();
  await expect(context).toHaveCount(0);
  await page.locator('[data-action="newTab"]').click();
  await expect(context).toHaveCount(0);
  await page.locator('[data-action="showFileContextPrompt"]').click();
  await page.locator('[data-action="enableFileContext"]').click();
  await expect(context.locator('.context-text')).toHaveText('BoundaryDreamlandClientConnectionManager.java · L128-L146');
});
