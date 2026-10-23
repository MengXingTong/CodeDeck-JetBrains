import {expect, test, type Page} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>, request?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

async function openQueue(page: Page, count = 3, lang = 'zh-CN') {
  await page.goto(`/tools/ui-preview.html?lang=${lang}`);
  await expect(page.locator('#prompt')).toBeVisible();
  await page.evaluate(count => {
    let items = Array.from({length: count}, (_, index) => ({id: `q-${index}`, text: `待处理消息 ${index + 1}`,
      attachments: index === 0 ? ['参考图.png'] : [], context: index === 0 ? 'src/Main.java · L12' : ''}));
    let editingQueuedInputId: string | null = null;
    const publish = () => CodexGui.receive(previewEnvelope('queue', {
      queuedCount: items.length, queuedInputs: items, canSteer: true, editingQueuedInputId,
    }));
    CodexGui.receive(previewEnvelope('busy', {busy: true, canSteer: true}));
    publish();
    const original = Reflect.get(window, 'codexHost') as (payload: string) => void;
    const requests: unknown[] = [];
    Reflect.set(window, 'queueTestRequests', requests);
    Reflect.set(window, 'codexHost', (payload: string) => {
      const request = JSON.parse(payload);
      requests.push(request);
      original(payload);
      if (request.type !== 'queuedInput') return;
      const {id, action, text} = request.payload;
      const item = items.find(item => item.id === id);
      if (!item) return;
      if (action === 'edit') editingQueuedInputId = id;
      if (action === 'cancel') editingQueuedInputId = null;
      if (action === 'save') {item.text = text; editingQueuedInputId = null;}
      if (action === 'delete' || action === 'steer') items = items.filter(item => item.id !== id);
      if (action === 'steer') CodexGui.receive(previewEnvelope('message', {entry: {kind: 'user', title: '你', body: item.text}}));
      publish();
    });
  }, count);
}

test('消息在输入框上方逐条显示，只有队首可引导，删除后队首正确顺延', async ({page}) => {
  await page.setViewportSize({width: 700, height: 900});
  await openQueue(page);
  const rows = page.locator('[data-queued-input]');
  await expect(rows).toHaveCount(3);
  await expect(page.locator('[data-queue-action="steer"]')).toHaveCount(1);
  await expect(rows.first().getByRole('button', {name: '立即引导'})).toBeEnabled();
  await expect(page.locator('[data-action="steer"]')).toHaveCount(0);
  expect(await page.locator('.queued-inputs').evaluate(element => element.getBoundingClientRect().bottom))
    .toBeLessThanOrEqual(await page.locator('#composer').evaluate(element => element.getBoundingClientRect().top));
  await page.screenshot({path: 'build/reports/queued-inputs.png'});
  await page.locator('[data-queued-input="q-1"] [data-queue-action="delete"]').click();
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(1)).toHaveAttribute('data-queued-input', 'q-2');
  await page.locator('#prompt').fill('尚未发送的主输入草稿');
  await rows.first().getByRole('button', {name: '立即引导'}).click();
  await expect(rows).toHaveCount(1);
  await expect(rows.first()).toHaveAttribute('data-queued-input', 'q-2');
  await expect(rows.first().getByRole('button', {name: '立即引导'})).toBeEnabled();
  await expect(page.locator('#prompt')).toHaveText('尚未发送的主输入草稿');
  await expect(page.locator('#messages .message.user')).toContainText(['待处理消息 1']);
  const requests = await page.evaluate(() => Reflect.get(window, 'queueTestRequests'));
  expect(requests.filter((item: any) => item.type === 'queuedInput').map((item: any) => item.payload))
    .toEqual([{id: 'q-1', action: 'delete'}, {id: 'q-0', action: 'steer'}]);
});

test('编辑与取消保留队列顺序、附件、主输入草稿和刷新期间的编辑文本', async ({page}) => {
  await openQueue(page);
  await page.locator('#prompt').fill('主输入草稿');
  const first = page.locator('[data-queued-input="q-0"]');
  await first.locator('[data-queue-action="edit"]').click();
  const editor = page.getByRole('textbox', {name: '编辑待发送消息'});
  await editor.fill('编辑后的内容\n第二行');
  await editor.press('ArrowLeft');
  const caret = await editor.evaluate(element => (element as HTMLTextAreaElement).selectionStart);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {message: '后台刷新'})));
  await expect(editor).toHaveValue('编辑后的内容\n第二行');
  expect(await editor.evaluate(element => (element as HTMLTextAreaElement).selectionStart)).toBe(caret);
  await editor.press('Control+Enter');
  await expect(editor).toHaveCount(0);
  await expect(first.locator('.queued-input-text')).toHaveText('编辑后的内容\n第二行');
  await expect(first).toContainText('参考图.png');
  await expect(page.locator('#prompt')).toHaveText('主输入草稿');
  await first.locator('[data-queue-action="edit"]').click();
  await editor.fill('取消的修改');
  await editor.press('Escape');
  await expect(editor).toHaveCount(0);
  await expect(first.locator('.queued-input-text')).toHaveText('编辑后的内容\n第二行');
  await expect(page.locator('[data-queued-input]')).toHaveCount(3);
  await expect(page.locator('#messages')).not.toContainText('编辑后的内容');
});

test('切换页签隔离队列与未保存编辑，后台队列事件不覆盖前台', async ({page}) => {
  await openQueue(page);
  await page.locator('[data-queued-input="q-1"] [data-queue-action="edit"]').click();
  await page.locator('#queueEditText').fill('后台编辑草稿');
  await page.locator('[data-action="newTab"]').click();
  await expect(page.locator('[data-queued-input]')).toHaveCount(0);
  await page.locator('#prompt').fill('前台草稿');
  await page.evaluate(() => CodexGui.receive(previewEnvelope('queue', {queuedCount: 3,
    queuedInputs: [{id: 'q-0', text: '已刷新'}, {id: 'q-1', text: '待处理消息 2'}, {id: 'q-2', text: '待处理消息 3'}],
    editingQueuedInputId: 'q-1', canSteer: true}, {sessionId: 'default'})));
  await expect(page.locator('[data-queued-input]')).toHaveCount(0);
  await expect(page.locator('#prompt')).toHaveText('前台草稿');
  await page.locator('[data-session-tab="default"]').click();
  await page.evaluate(() => CodexGui.receive(previewEnvelope('clear', {preserveQueue: true})));
  await expect(page.locator('#queueEditText')).toHaveValue('后台编辑草稿');
  await expect(page.locator('[data-queued-input]')).toHaveCount(3);
});

test('排队编辑中的中文组合输入不会被后台刷新打断', async ({page}) => {
  await openQueue(page);
  await page.locator('[data-queued-input="q-0"] [data-queue-action="edit"]').click();
  const editor = page.locator('#queueEditText');
  await editor.fill('');
  const cdp = await page.context().newCDPSession(page);
  await editor.evaluate(element => Reflect.set(window, 'composingQueueEditor', element));
  await cdp.send('Input.imeSetComposition', {text: 'wen', selectionStart: 3, selectionEnd: 3});
  await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {message: '组合期间的后台刷新'})));
  expect(await editor.evaluate(element => element === Reflect.get(window, 'composingQueueEditor'))).toBe(true);
  await cdp.send('Input.insertText', {text: '文'});
  await expect(editor).toHaveValue('文');
  await page.getByRole('button', {name: '保存', exact: true}).click();
  await expect(page.locator('[data-queued-input="q-0"] .queued-input-text')).toHaveText('文');
});

test('窄栏及英文界面可见队首引导，未就绪时保留消息并禁用引导', async ({page}) => {
  await page.setViewportSize({width: 440, height: 800});
  await openQueue(page, 5, 'en');
  await expect(page.getByRole('region', {name: 'Queued messages'})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Steer now'})).toBeVisible();
  await expect(page.getByRole('button', {name: 'Edit queued message', exact: true})).toHaveCount(5);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(440);
  await page.evaluate(() => CodexGui.receive(previewEnvelope('queue', {queuedCount: 5, canSteer: false})));
  await expect(page.getByRole('button', {name: 'Steer now'})).toBeDisabled();
  await expect(page.locator('[data-queued-input]')).toHaveCount(5);
  await page.screenshot({path: 'build/reports/queued-inputs-narrow.png'});
});
