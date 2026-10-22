import {expect, test} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>, request?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

test('English settings translate plugin controls and preserve saved Chinese content', async ({page}, testInfo) => {
  await page.setViewportSize({width: 520, height: 960});
  await page.goto('/tools/ui-preview.html?lang=en');
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.getByRole('textbox', {name: 'Message', exact: true})).toBeVisible();
  await page.locator('[data-action="settings"]').click();
  await expect(page.getByRole('heading', {name: 'General settings'})).toBeVisible();
  expect(await page.locator('.settings-content').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.locator('[data-basic-tab="behavior"]').click();
  await expect(page.getByText('Preserve existing line endings', {exact: true})).toBeVisible();
  expect(await page.locator('.settings-content').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.screenshot({path: testInfo.outputPath('english-settings.png'), fullPage: true});

  await page.locator('[data-settings-tab="providers"]').click();
  await page.locator('[data-provider-channel="codex"]').click();
  await expect(page.getByText('Codex local configuration', {exact: true})).toBeVisible();
  await expect(page.getByText('团队网关', {exact: true})).toBeVisible();
  await page.locator('[data-action="newProvider"]').click();
  await expect(page.getByRole('heading', {name: 'Add provider'})).toBeVisible();
  expect(await page.locator('.modal-card').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.getByRole('button', {name: 'Cancel', exact: true}).click();

  await page.locator('[data-settings-tab="instructions"]').click();
  await expect(page.getByRole('heading', {name: 'Prompt library'})).toBeVisible();
  await expect(page.locator('#globalInstructions')).toHaveValue('始终使用中文回复，并先给出结论。');
  await page.locator('[data-settings-tab="agents"]').click();
  await expect(page.getByText('代码审查员', {exact: true})).toBeVisible();
  await page.locator('[data-action="newAgent"]').click();
  await expect(page.getByRole('heading', {name: 'New agent'})).toBeVisible();
  expect(await page.locator('.modal-card').innerText()).not.toMatch(/\p{Script=Han}/u);
});

test('English tabs number correctly and Chinese messages and file names stay unchanged', async ({page}) => {
  await page.goto('/tools/ui-preview.html?lang=en&references');
  const editor = page.getByRole('textbox', {name: 'Message', exact: true});
  await editor.fill('保留我的中文草稿');
  await page.locator('[data-action="newTab"]').click();
  await expect(page.locator('.session-tab.active')).toContainText('New chat 2');
  await page.locator('[data-action="newTab"]').click();
  await expect(page.locator('.session-tab.active')).toContainText('New chat 3');
  await page.locator('[data-session-tab="default"]').click();
  await expect(editor).toHaveText('保留我的中文草稿');
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('message', {entry: {kind: 'user', title: '你', body: '保持已有文件换行', itemId: 'locale-user'}}));
    CodexGui.receive(previewEnvelope('message', {entry: {kind: 'assistant', title: 'Codex', body: '中文回复：`项目/订单.java`', itemId: 'locale-assistant'}}));
    CodexGui.receive(previewEnvelope('fileContext', {path: 'D:\\项目\\订单.java', startLine: 2, endLine: 3}));
  });
  await expect(page.locator('[data-item-id="locale-user"]')).toContainText('保持已有文件换行');
  await expect(page.locator('[data-item-id="locale-assistant"]')).toContainText('中文回复');
  await expect(page.locator('[data-item-id="locale-assistant"] [data-copy-message]')).toHaveAttribute('title', 'Copy message');
  await page.locator('[data-action="settings"]').click();
  await page.locator('[data-basic-tab="behavior"]').click();
  await page.locator('[data-behavior-toggle="sendOpenedFilePath"]').click();
  await expect(page.getByText('Send editor file context', {exact: true})).toBeVisible();
});

test('Chinese locale and bootstrap English locale use consistent controls', async ({page}) => {
  await page.goto('/tools/ui-preview.html?lang=zh-TW');
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN');
  await expect(page.getByRole('textbox', {name: '消息', exact: true})).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('bootstrap', {state: {uiLocale: 'en'}})));
  await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  await expect(page.getByRole('textbox', {name: 'Message', exact: true})).toBeVisible();
  await expect(page.locator('[data-action="settings"]')).toHaveAttribute('title', 'Settings');
});

test('English Skills, MCP, validation, and question dialogs keep model content intact', async ({page}) => {
  await page.goto('/tools/ui-preview.html?lang=en');
  await page.locator('[data-action="settings"]').click();
  await page.locator('[data-settings-tab="skills"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('skills', {items: [], errors: []})));
  await expect(page.getByText('No skills available in this workspace', {exact: true})).toBeVisible();
  expect(await page.locator('.settings-content').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.locator('[data-settings-tab="mcp"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('mcpServers', {items: []})));
  await expect(page.getByText('No MCP servers configured', {exact: true})).toBeVisible();
  expect(await page.locator('.settings-content').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.locator('[data-action="mcpHelp"]').click();
  await expect(page.getByRole('heading', {name: 'What is MCP?'})).toBeVisible();
  expect(await page.locator('.modal-card').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.getByRole('button', {name: 'Got it', exact: true}).click();
  await page.locator('[data-action="newMcp"]').click();
  await expect(page.getByRole('heading', {name: 'Add MCP server'})).toBeVisible();
  expect(await page.locator('.modal-card').innerText()).not.toMatch(/\p{Script=Han}/u);
  await page.locator('[data-action="closeMcpEditor"]').first().click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('question', {
    requestId: 'locale-question', questions: [{id: 'choice', header: '模型的问题', question: '是否保留中文文件名？',
      options: [{label: '保留', description: '文件内容也保持原文'}], isOther: true}],
  })));
  await expect(page.getByRole('heading', {name: 'Codex has some questions for you'})).toBeVisible();
  await expect(page.getByText('是否保留中文文件名？', {exact: true})).toBeVisible();
  await expect(page.getByRole('button', {name: '保留 文件内容也保持原文'})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Submit', exact: true})).toBeVisible();
});
