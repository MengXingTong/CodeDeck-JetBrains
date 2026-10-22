import {expect, test, type Page} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

async function openEditor(page: Page, prefix = '') {
  await page.goto('/tools/ui-preview.html');
  const editor = page.getByRole('textbox', {name: '消息'});
  await editor.fill(prefix);
  await editor.focus();
  await editor.evaluate(element => {
    const events: unknown[] = [];
    Reflect.set(window, 'imeTrace', events);
    for (const name of ['keydown', 'beforeinput', 'input', 'compositionstart', 'compositionupdate', 'compositionend']) {
      element.addEventListener(name, event => events.push({type: name, data: (event as InputEvent).data,
        key: (event as KeyboardEvent).key, composing: (event as InputEvent).isComposing,
        html: element.innerHTML, active: document.activeElement === element}));
    }
  });
  return editor;
}

for (const background of [false, true]) {
  for (const earlyEnd of [false, true]) {
    for (const prefix of ['', '已有文字：']) {
      test(`原生组合 wen→we→w→空，后台=${background}，提前结束=${earlyEnd}，前缀=${!!prefix}`, async ({page}) => {
        const editor = await openEditor(page, prefix);
        const cdp = await page.context().newCDPSession(page);
        try {
          await cdp.send('Input.imeSetComposition', {text: 'wen', selectionStart: 3, selectionEnd: 3});
          await expect(editor).toHaveText(prefix + 'wen');
          if (background) await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {message: 'IME 测试后台更新'})));
          for (const text of ['we', 'w']) {
            await cdp.send('Input.imeSetComposition', {text, selectionStart: text.length, selectionEnd: text.length});
            await expect(editor).toHaveText(prefix + text);
          }
          // Exercise an early host notification while Chromium still owns the native composition.
          // Reparenting the focused editor here commits 'w', making the actual cancellation ineffective.
          if (earlyEnd) await editor.evaluate(element => element.dispatchEvent(new CompositionEvent('compositionend', {bubbles: true, data: ''})));
          await cdp.send('Input.imeSetComposition', {text: '', selectionStart: 0, selectionEnd: 0});
          await expect(editor).toHaveText(prefix);
          if (background) await expect(page.getByText('IME 测试后台更新', {exact: true})).toBeVisible();
          await editor.pressSequentially('继续输入');
          await expect(editor).toHaveText(prefix + '继续输入');
          await test.info().attach('ime-events', {body: JSON.stringify(await page.evaluate(() => Reflect.get(window, 'imeTrace'))), contentType: 'application/json'});
        } finally { await cdp.detach(); }
      });
    }
  }
}

test('原生组合期间三次 Backspace 清空后仍可继续输入', async ({page}) => {
  const editor = await openEditor(page);
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('Input.imeSetComposition', {text: 'wen', selectionStart: 3, selectionEnd: 3});
    for (const text of ['we', 'w', '']) {
      await editor.press('Backspace');
      await expect(editor).toHaveText(text);
    }
    await cdp.send('Input.imeSetComposition', {text: '', selectionStart: 0, selectionEnd: 0});
    await editor.pressSequentially('正常');
    await expect(editor).toHaveText('正常');
  } finally { await cdp.detach(); }
});

test('正常选字提交后立即发送保留中文，不触发重复提交或换行', async ({page}) => {
  const editor = await openEditor(page, '前缀：');
  await page.evaluate(() => {
    const requests: unknown[] = [];
    const original = Reflect.get(window, 'codexHost') as (payload: string) => void;
    Reflect.set(window, 'imeRequests', requests);
    Reflect.set(window, 'codexHost', (payload: string) => { requests.push(JSON.parse(payload)); original(payload); });
  });
  const cdp = await page.context().newCDPSession(page);
  try {
    await cdp.send('Input.imeSetComposition', {text: 'wen', selectionStart: 3, selectionEnd: 3});
    await cdp.send('Input.insertText', {text: '文'});
    await expect(editor).toHaveText('前缀：文');
    await editor.press('Enter');
    const requests = await page.evaluate(() => Reflect.get(window, 'imeRequests'));
    expect(requests.filter((request: any) => request.type === 'send').map((request: any) => request.payload.text)).toEqual(['前缀：文']);
    await expect(editor).toHaveText('');
  } finally { await cdp.detach(); }
});
