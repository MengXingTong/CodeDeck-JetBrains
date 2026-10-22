import {expect, test, type Page} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>, request?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

async function captureRequests(page: Page) {
  await page.evaluate(() => {
    const original = Reflect.get(window, 'codexHost') as (payload: string) => void;
    const requests: unknown[] = [];
    Reflect.set(window, 'historyTestRequests', requests);
    Reflect.set(window, 'codexHost', (payload: string) => {
      requests.push(JSON.parse(payload));
      original(payload);
    });
  });
}

test('自动改名只更新目标页签，保留前台设置页和两个页签的草稿', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {id: 'thread-a', title: '首轮提示'})));
  await editor.fill('后台草稿');
  await page.locator('[data-action="newTab"]').click();
  await editor.fill('前台草稿');
  await page.locator('[data-action="settings"]').click();
  await expect(page.locator('.settings-page')).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'thread-a', title: '后台自动标题', titleOnly: true,
  }, {sessionId: 'default'})));
  await expect(page.locator('[data-session-tab="default"]')).toHaveAttribute('title', '后台自动标题');
  await expect(page.locator('.session-tab.active')).not.toContainText('后台自动标题');
  await expect(page.locator('.settings-page')).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'thread-b', title: '前台自动标题', titleOnly: true,
  }, {sessionId: document.querySelector('.session-tab.active')?.getAttribute('data-session-tab')})));
  await expect(page.locator('.session-tab.active')).toContainText('前台自动标题');
  await expect(page.locator('.settings-page')).toBeVisible();
  await page.locator('[data-action="back"]').click();
  await expect(editor).toHaveText('前台草稿');
  await page.locator('[data-session-tab="default"]').click();
  await expect(editor).toHaveText('后台草稿');
});

test('MCP 状态未确认时保留配置操作并在后续刷新恢复连接', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  await page.locator('[data-action="settings"]').click();
  await page.locator('[data-settings-tab="mcp"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('mcpServers', {items: [{
    name: 'slow-server', enabled: true, connectionStatus: 'unknown', config: {command: 'slow-tool'}, tools: {},
  }]})));
  const card = page.locator('.mcp-server-card');
  await expect(card.getByTitle('状态待确认', {exact: true})).toBeVisible();
  await expect(card.locator('[data-mcp-edit]')).toBeEnabled();
  await expect(card.locator('[data-mcp-toggle]')).toBeEnabled();
  await expect(card.getByText('连接状态待确认，可稍后刷新重试', {exact: true})).toBeVisible();
  await expect(page.locator('.mcp-empty')).toHaveCount(0);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('mcpServers', {items: [{
    name: 'slow-server', enabled: true, connectionStatus: 'connected', config: {command: 'slow-tool'},
    serverInfo: {name: 'slow-server'}, tools: {search: {description: '搜索'}},
  }]})));
  await expect(card.getByTitle('连接成功', {exact: true})).toBeVisible();
  await expect(card.locator('.mcp-tool-row')).toContainText('search');
});

test('重连提示保留运行和排队状态，后续回复继续显示', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('busy', {busy: true, queuedCount: 1}));
    CodexGui.receive(previewEnvelope('message', {entry: {
      kind: 'notice', title: '正在重连', body: 'Reconnecting... 2/5\ntls handshake eof', itemId: 'retry',
    }}));
    CodexGui.receive(previewEnvelope('appendMessage', {
      kind: 'assistant', title: 'Codex', itemId: 'resumed', delta: '恢复后的回复',
    }));
  });

  await expect(page.getByTitle('停止')).toBeVisible();
  await expect(page.getByRole('status')).toContainText('已排队 1 条');
  await expect(page.locator('[data-item-id="resumed"]')).toContainText('恢复后的回复');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('busy', {busy: false, queuedCount: 0})));
  await expect(page.getByTitle('停止')).toHaveCount(0);
});

test('共享历史区分工作目录并按原会话 ID 打开', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  await page.locator('[data-action="history"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('history', {provider: 'codex', items: [
    {id: 'shared-thread-a', title: '桌面会话', cwd: 'D:/Other Project/<shared>', time: '10-09 10:00'},
    {id: 'shared-thread-b', title: 'CLI 会话', cwd: 'D:/ProjectB', time: '10-08 10:00'},
  ]})));

  await expect(page.getByPlaceholder('搜索本机共享会话')).toBeVisible();
  await expect(page.locator('.history-item')).toHaveCount(2);
  await expect(page.locator('[data-thread="shared-thread-a"]')).toContainText('D:/Other Project/<shared>');
  await expect(page.locator('[data-thread="shared-thread-b"]')).toContainText('D:/ProjectB');
  await expect(page.locator('.history-meta shared')).toHaveCount(0);
  await page.locator('[data-thread="shared-thread-a"]').click();
  const sent = await page.evaluate(() => JSON.parse(document.body.dataset.previewLastPayload || '{}'));
  expect(sent).toMatchObject({type: 'openThread', payload: {id: 'shared-thread-a'}});
});

test('历史定位运行中的原页签并保留两个页签的草稿和后台输出', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await captureRequests(page);
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('thread', {id: 'local-running', title: '原运行会话', provider: 'codex'}));
    CodexGui.receive(previewEnvelope('busy', {busy: true}));
  });
  await editor.fill('运行页签草稿');
  await page.locator('[data-action="newTab"]').click();
  await editor.fill('另一个页签草稿');
  await page.locator('[data-action="history"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('history', {provider: 'codex', items: [
    {id: 'local-running', title: '原运行会话'},
  ]}, {sessionId: document.querySelector('.session-tab.active')?.getAttribute('data-session-tab')})));
  await page.locator('[data-thread="local-running"]').click();

  await expect(page.locator('.session-tab.active')).toContainText('原运行会话');
  await expect(page.getByTitle('停止', {exact: true})).toBeVisible();
  await expect(editor).toHaveText('运行页签草稿');
  await expect(page.locator('.session-tab')).toHaveCount(2);
  const requests = await page.evaluate(() => Reflect.get(window, 'historyTestRequests'));
  expect(requests.some((request: {type: string}) => request.type === 'activateSession')).toBe(true);
  expect(requests.some((request: {type: string}) => request.type === 'openThread')).toBe(false);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('appendMessage', {
    kind: 'assistant', title: 'Codex', itemId: 'still-running', delta: '后台继续输出',
  }, {sessionId: 'default'})));
  await expect(page.locator('[data-item-id="still-running"]')).toContainText('后台继续输出');
  await page.locator('.session-tab').nth(1).click();
  await expect(editor).toHaveText('另一个页签草稿');
});

test('在当前运行页签点击自身历史正常返回，打开其他历史显示提示', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
  await captureRequests(page);
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('thread', {id: 'local-running', title: '运行会话', provider: 'codex'}));
    CodexGui.receive(previewEnvelope('busy', {busy: true}));
  });
  await page.locator('[data-action="history"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('history', {provider: 'codex', items: [
    {id: 'local-running', title: '运行会话'}, {id: 'other-history', title: '其他历史'},
  ]})));
  await page.locator('[data-thread="other-history"]').click();
  await expect(page.locator('.toast')).toContainText('当前页签有任务正在运行');
  await expect(page.locator('.history-list')).toBeVisible();
  const requests = await page.evaluate(() => Reflect.get(window, 'historyTestRequests'));
  expect(requests.some((request: {type: string}) => request.type === 'openThread')).toBe(false);
  await page.locator('[data-thread="local-running"]').click();
  await expect(page.getByTitle('停止', {exact: true})).toBeVisible();
});

test('外部占用历史显示只读快照，禁止发送且重新打开后恢复输入', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'external-writer', title: '外部运行会话', provider: 'codex', historyReadOnly: true,
  })));
  await expect(page.locator('.history-read-only')).toContainText('其他客户端占用');
  await expect(page.getByTitle('发送', {exact: true})).toBeDisabled();
  await expect(page.getByTitle('回溯', {exact: true})).toBeDisabled();
  await editor.fill('等待释放的草稿');
  await page.evaluate(() => {document.body.dataset.previewLastPayload = '{}';});
  await editor.press('Enter');
  await expect(editor).toHaveText('等待释放的草稿');
  expect(await page.evaluate(() => document.body.dataset.previewLastPayload)).toBe('{}');
  await page.getByRole('button', {name: '重新打开', exact: true}).click();
  const sent = await page.evaluate(() => JSON.parse(document.body.dataset.previewLastPayload || '{}'));
  expect(sent).toMatchObject({type: 'openThread', payload: {id: 'external-writer'}});
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'external-writer', title: '外部运行会话', provider: 'codex', historyReadOnly: false,
  })));
  await expect(page.locator('.history-read-only')).toHaveCount(0);
  await expect(page.getByTitle('发送', {exact: true})).toBeEnabled();
  await expect(editor).toHaveText('等待释放的草稿');
});

test('后端显式定位会话只切换目标页签，后台 thread 更新不抢焦点', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await editor.fill('原页签草稿');
  await page.locator('[data-action="newTab"]').click();
  await editor.fill('当前页签草稿');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'local-running', title: '后台会话', provider: 'codex',
  }, {sessionId: 'default'})));
  await expect(editor).toHaveText('当前页签草稿');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'local-running', title: '后台会话', provider: 'codex', activate: true,
  }, {sessionId: 'default'})));
  await expect(page.locator('.session-tab.active')).toContainText('后台会话');
  await expect(editor).toHaveText('原页签草稿');
});
