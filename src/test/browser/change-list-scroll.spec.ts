import {expect, test} from '@playwright/test';

declare const previewEnvelope: (type: string, payload?: Record<string, unknown>, request?: Record<string, unknown>) => unknown;
declare const CodexGui: {receive: (event: unknown) => void};

for (const action of ['accept', 'revert']) {
  for (const position of ['middle', 'bottom']) {
    test(`${action} 文件后保持修改列表滚动位置（${position}）`, async ({page}) => {
      await page.goto('/tools/ui-preview.html');
      await expect(page.getByRole('textbox', {name: '消息'})).toBeVisible();
      await page.evaluate(action => {
        let items = Array.from({length: 40}, (_, index) => ({
          path: `src/File${index}.java`, kind: 'MODIFIED', reversible: true,
          addedLines: 1, deletedLines: 1,
        }));
        CodexGui.receive(previewEnvelope('changes', {items}));
        const original = Reflect.get(window, 'codexHost') as (payload: string) => void;
        Reflect.set(window, 'codexHost', (payload: string) => {
          original(payload);
          const request = JSON.parse(payload);
          if (request.type !== `${action}Change`) return;
          // 模拟原生端异步完成单文件操作后发送缩短的列表。
          setTimeout(() => {
            items = items.filter((_, index) => index !== request.payload.index);
            CodexGui.receive(previewEnvelope('changes', {items}, request));
          }, 20);
        });
      }, action);
      await page.locator('[data-status="changes"]').click();
      const list = page.locator('.change-list');
      const before = await list.evaluate((element, position) => {
        element.scrollTop = position === 'bottom' ? element.scrollHeight : 420;
        const bounds = element.getBoundingClientRect();
        const row = Array.from(element.querySelectorAll<HTMLElement>('[data-change-row]'))
          .find(row => {
            const rect = row.getBoundingClientRect();
            return rect.top >= bounds.top + 4 && rect.bottom <= bounds.bottom - 4;
          })!;
        return {scrollTop: element.scrollTop, index: row.dataset.changeRow};
      }, position);
      expect(before.scrollTop).toBeGreaterThan(0);
      await page.locator(`[data-${action}="${before.index}"]`).click();
      await expect(page.locator('[data-change-row]')).toHaveCount(39);
      const expected = await list.evaluate((element, previous) =>
        Math.min(previous, element.scrollHeight - element.clientHeight), before.scrollTop);
      await expect.poll(() => list.evaluate(element => element.scrollTop)).toBe(expected);
      // 后续提示重绘也不能再次把列表滚回顶部。
      await page.evaluate(() => CodexGui.receive(previewEnvelope('toast', {message: '操作完成'})));
      await expect.poll(() => list.evaluate(element => element.scrollTop)).toBe(expected);
    });
  }
}
