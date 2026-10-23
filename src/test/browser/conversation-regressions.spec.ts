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

async function requestsOfType(page: Page, type: string) {
  return page.evaluate(type => (Reflect.get(window, 'historyTestRequests') as {
    type: string; payload: Record<string, unknown>;
  }[]).filter(request => request.type === type), type);
}

test('默认保持已有文件换行，可关闭后进行主动格式转换', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  await captureRequests(page);
  await page.locator('[data-action="settings"]').click();
  await page.locator('[data-basic-tab="behavior"]').click();
  const toggle = page.locator('[data-behavior-toggle="preserveFileLineEndings"]');
  await expect(toggle).toHaveAttribute('aria-pressed', 'true');
  await toggle.click();
  await expect(toggle).toHaveAttribute('aria-pressed', 'false');
  expect(await page.evaluate(() => (Reflect.get(window, 'historyTestRequests') as {type: string; payload: unknown}[])
    .filter(item => item.type === 'behaviorSetting').at(-1)?.payload))
    .toEqual({key: 'preserveFileLineEndings', value: false});
});

test('启动卡住的页签可以关闭，迟到事件不会复活，最后一页关闭后可继续输入', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await captureRequests(page);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('busy', {busy: true})));
  await expect(page.getByTitle('停止', {exact: true})).toBeVisible();
  await page.locator('[data-close-session="default"]').click();
  await expect(page.locator('[data-session-tab="default"]')).toHaveCount(0);
  await expect(page.locator('.session-tab')).toHaveCount(1);
  await expect(page.getByTitle('发送', {exact: true})).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('appendMessage', {
    kind: 'assistant', itemId: 'late', delta: '迟到回复',
  }, {sessionId: 'default'})));
  await expect(page.locator('[data-session-tab="default"]')).toHaveCount(0);
  await expect(page.locator('[data-item-id="late"]')).toHaveCount(0);
  await editor.fill('恢复后的草稿');
  await page.locator('[data-action="newTab"]').click();
  const active = page.locator('.session-tab.active');
  const activeId = await active.getAttribute('data-session-tab');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('busy', {busy: true})));
  await active.locator('[data-close-session]').click();
  await expect(page.locator('.session-tab')).toHaveCount(1);
  await expect(editor).toHaveText('恢复后的草稿');
  const requests = await page.evaluate(() => Reflect.get(window, 'historyTestRequests'));
  expect(requests.filter((request: any) => request.type === 'closeSession')
    .map((request: any) => request.sessionId)).toEqual(['default', activeId]);
});

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
  await captureRequests(page);
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
  // 布局通知可以在打开会话请求之后到达；明确重放 CI 中覆盖最后一条消息的时序。
  await page.evaluate(() => Reflect.get(window, 'codexHost')(JSON.stringify(previewEnvelope('composerBounds', {
    left: -1, top: -1, right: -1, bottom: -1,
  }))));
  await expect.poll(() => requestsOfType(page, 'openThread'))
    .toMatchObject([{type: 'openThread', payload: {id: 'shared-thread-a'}}]);
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

test('历史快照支持刷新和显式继续，保留草稿并在恢复成功后允许发送', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'external-writer', title: '外部运行会话', provider: 'codex', historyReadOnly: true,
  })));
  await expect(page.locator('.history-read-only')).toContainText('已保存的历史快照');
  await expect(page.getByTitle('发送', {exact: true})).toBeDisabled();
  await expect(page.getByTitle('回溯', {exact: true})).toBeDisabled();
  await captureRequests(page);
  await editor.fill('等待释放的草稿');
  await editor.press('Enter');
  await expect(editor).toHaveText('等待释放的草稿');
  expect(await requestsOfType(page, 'send')).toEqual([]);
  expect(await requestsOfType(page, 'openThread')).toEqual([]);
  await page.getByRole('button', {name: '刷新历史', exact: true}).click();
  await expect.poll(() => requestsOfType(page, 'openThread'))
    .toMatchObject([{type: 'openThread', payload: {id: 'external-writer'}}]);
  expect(await requestsOfType(page, 'resumeThread')).toEqual([]);
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('clear', {threadId: 'external-writer'}));
    CodexGui.receive(previewEnvelope('thread', {id: 'external-writer', historyReadOnly: true}));
  });
  await expect(editor).toHaveText('等待释放的草稿');
  await expect(page.getByTitle('发送', {exact: true})).toBeDisabled();
  await page.getByRole('button', {name: '继续原会话', exact: true}).click();
  await expect.poll(() => requestsOfType(page, 'resumeThread'))
    .toMatchObject([{type: 'resumeThread', payload: {id: 'external-writer'}}]);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {
    message: '原会话仍被其他客户端占用，可刷新历史或复制为新会话继续',
  })));
  await expect(page.locator('.history-read-only')).toBeVisible();
  await expect(editor).toHaveText('等待释放的草稿');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'external-writer', title: '外部运行会话', provider: 'codex', historyReadOnly: false,
  })));
  await expect(page.locator('.history-read-only')).toHaveCount(0);
  await expect(page.getByTitle('发送', {exact: true})).toBeEnabled();
  await expect(editor).toHaveText('等待释放的草稿');
});

test('复制历史走独立分叉请求，失败保留快照，成功绑定新会话并保留草稿', async ({page}) => {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await expect(editor).toBeVisible();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
    id: 'occupied-source', title: '原会话', provider: 'codex', historyReadOnly: true,
  })));
  await editor.fill('接着完成这个任务');
  await captureRequests(page);
  await page.getByRole('button', {name: '复制为新会话继续', exact: true}).click();
  await expect.poll(() => requestsOfType(page, 'forkThread'))
    .toMatchObject([{type: 'forkThread', payload: {id: 'occupied-source'}}]);
  expect(await requestsOfType(page, 'resumeThread')).toEqual([]);
  expect(await requestsOfType(page, 'send')).toEqual([]);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {message: '无法打开历史会话'})));
  await expect(page.locator('.history-read-only')).toBeVisible();
  await expect(editor).toHaveText('接着完成这个任务');
  await page.getByRole('button', {name: '复制为新会话继续', exact: true}).click();
  await page.evaluate(() => {
    CodexGui.receive(previewEnvelope('clear', {threadId: 'independent-copy'}));
    CodexGui.receive(previewEnvelope('message', {entry: {
      kind: 'assistant', title: 'Codex', body: '从原会话复制的回复', itemId: 'copied-reply',
    }}));
    CodexGui.receive(previewEnvelope('thread', {
      id: 'independent-copy', title: '原会话', provider: 'codex', historyReadOnly: false,
    }));
  });
  await expect(page.locator('.history-read-only')).toHaveCount(0);
  await expect(page.locator('#messages')).toContainText('从原会话复制的回复');
  await expect(editor).toHaveText('接着完成这个任务');
  await expect(page.getByTitle('发送', {exact: true})).toBeEnabled();
  await editor.press('Enter');
  await expect.poll(() => requestsOfType(page, 'send'))
    .toMatchObject([{type: 'send', payload: {text: '接着完成这个任务'}}]);
});

for (const locale of ['zh-CN', 'en']) {
  test('历史快照操作在窄面板中完整显示：' + locale, async ({page}, testInfo) => {
    await page.setViewportSize({width: 360, height: 800});
    await page.goto('/tools/ui-preview.html?lang=' + locale);
    await expect(page.locator('.ProseMirror')).toBeVisible();
    await page.evaluate(() => CodexGui.receive(previewEnvelope('thread', {
      id: 'snapshot', title: '历史快照', provider: 'codex', historyReadOnly: true,
    })));
    const notice = page.locator('.history-read-only');
    await expect(notice).toContainText(locale === 'en' ? 'saved history snapshot' : '已保存的历史快照');
    const buttons = notice.getByRole('button');
    await expect(buttons).toHaveCount(3);
    const bounds = await notice.boundingBox();
    expect(bounds).not.toBeNull();
    for (const button of await buttons.all()) {
      await expect(button).toBeVisible();
      const box = await button.boundingBox();
      expect(box!.x).toBeGreaterThanOrEqual(bounds!.x);
      expect(box!.x + box!.width).toBeLessThanOrEqual(bounds!.x + bounds!.width);
      expect(box!.y + box!.height).toBeLessThanOrEqual(bounds!.y + bounds!.height);
    }
    await page.screenshot({path: testInfo.outputPath('history-snapshot.png')});
  });
}

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
