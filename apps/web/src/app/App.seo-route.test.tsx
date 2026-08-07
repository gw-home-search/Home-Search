import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  jsonResponse,
  renderApp,
  resetAppTestState,
  unmount,
} from './appTestHarness';

describe('App SEO 경로', () => {
  afterEach(resetAppTestState);

  for (const path of ['/complexes/501', '/regions/1']) {
    it(`${path} hydration 이후에도 URL을 유지한다`, async () => {
      window.history.pushState({}, '', path);
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

      const { root, rootElement } = await renderApp();

      expect(window.location.pathname).toBe(path);
      expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
      unmount(root);
    });
  }

  for (const [path, heading] of [
    ['/privacy', '개인정보처리방침'],
    ['/terms', '서비스 이용약관'],
    ['/about', '홈서치 소개'],
  ] as const) {
    it(`${path} 공개 문서를 로그인 없이 표시한다`, async () => {
      window.history.pushState({}, '', path);

      const { root, rootElement } = await renderApp();

      expect(rootElement.querySelector('h1')?.textContent).toBe(heading);
      expect(rootElement.textContent).toContain('gwangjae.kwon.99@gmail.com');
      expect(rootElement.querySelector('[aria-label="지도 화면"]')).toBeNull();
      unmount(root);
    });
  }

  it('지도 화면에서 공개 문서 링크를 제공한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp();

    expect(rootElement.querySelector('a[href="/privacy"]')).not.toBeNull();
    expect(rootElement.querySelector('a[href="/terms"]')).not.toBeNull();
    expect(rootElement.querySelector('a[href="/about"]')).not.toBeNull();
    unmount(root);
  });
});
