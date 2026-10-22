import {expect, test} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

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
