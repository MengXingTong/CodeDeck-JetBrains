import {afterEach, describe, expect, it} from 'vitest';
import {setUiLocale, t, ui, uiLocale} from '../../main/ts/i18n';
import {createConversationRenderer} from '../../main/ts/conversation-renderer';

afterEach(() => setUiLocale('zh-CN'));

describe('plugin localization', () => {
  it('uses Chinese for Chinese locales and English for other IDE locales', () => {
    for (const locale of ['zh-CN', 'zh-TW', 'zh-HK', 'ZH-cn']) {
      setUiLocale(locale);
      expect(uiLocale()).toBe('zh-CN');
      expect(t('新会话')).toBe('新会话');
    }
    for (const locale of ['en', 'en-US', 'en-GB', 'de-DE', 'ja-JP']) {
      setUiLocale(locale);
      expect(uiLocale()).toBe('en');
      expect(t('新会话')).toBe('New chat');
      expect(t('保持已有文件换行')).toBe('Preserve existing line endings');
    }
  });

  it('translates static HTML and attributes without touching interpolated content', () => {
    setUiLocale('en');
    const userText = '新会话', path = 'D:\\项目\\保持已有文件换行.ts';
    expect(ui`<button title="复制消息">复制</button><p>${userText}</p><code>${path}</code>`)
      .toBe(`<button title="Copy message">Copy</button><p>新会话</p><code>${path}</code>`);
    expect(t('<span>已启用</span>')).toBe('<span>Enabled</span>');
    expect(t('unknown-key')).toBe('unknown-key');
  });

  it('recognizes command status in both old Chinese history and new English history', () => {
    setUiLocale('en');
    const renderer = createConversationRenderer({icon: () => '', commandDetails: () => ({}), showThinking: () => true});
    for (const body of ['$ run\n\n退出码：0', '$ run\n\nExit code: 0']) {
      expect(renderer.commandStatus({body})).toEqual({kind: 'success', text: 'Succeeded'});
    }
    expect(renderer.commandStatus({body: '$ run\n\nExit code: 2'}).kind).toBe('failed');
    expect(renderer.commandPresentation({body: '$ run', title: 'Claude 工具'}, 0).label).toBe('Call tool');
    expect(renderer.commandPresentation({body: '$ run', title: 'Claude tool'}, 0).label).toBe('Call tool');
  });
});
