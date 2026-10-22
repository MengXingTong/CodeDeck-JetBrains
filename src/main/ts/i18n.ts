import english from '../../../shared-core/src/main/resources/i18n/en.json';

export type UiLocale = 'zh-CN' | 'en';
const translations: Readonly<Record<string, string>> = english;
let locale: UiLocale = 'zh-CN';

export function setUiLocale(value: unknown): void {
  locale = typeof value === 'string' && /^zh(?:-|$)/i.test(value) ? 'zh-CN' : 'en';
  if (typeof document !== 'undefined') document.documentElement.lang = locale;
}

export function uiLocale(): UiLocale { return locale; }

/** Translate source literals only. Interpolated user/model content is never looked up. */
export function t(source: string): string {
  if (locale === 'zh-CN') return source;
  if (Object.hasOwn(translations, source)) return translations[source];
  return source.replace(/[^<>"'\n\r]+/gu, part => {
    const key = part.trim();
    if (!Object.hasOwn(translations, key)) return part;
    return part.slice(0, part.indexOf(key)) + translations[key] + part.slice(part.indexOf(key) + key.length);
  });
}

/** Tagged UI templates translate only static segments, preserving HTML and dynamic values. */
export function ui(parts: TemplateStringsArray, ...values: unknown[]): string {
  return parts.reduce((result, part, index) => result + t(part) + (index < values.length ? String(values[index]) : ''), '');
}

if (typeof window !== 'undefined') {
  const injected = Reflect.get(window, 'codedeckLocale');
  if (typeof injected === 'string') setUiLocale(injected);
}
