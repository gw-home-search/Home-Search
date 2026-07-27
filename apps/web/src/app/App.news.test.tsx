import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  resolveApiUrl,
  unmount,
} from './appTestHarness';

describe('App 부동산 뉴스 통합', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    resetAppTestState();
  });

  it('/insights/news는 지도 shell과 URL 상태, 안전한 원문 링크를 유지한다', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    window.history.pushState({}, '', '/insights/news');
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/v1/insights/news')) {
        return Promise.resolve(jsonResponse({
          snapshotId: '123e4567-e89b-12d3-a456-426614174000',
          generatedAt: '2026-07-24T09:30:00Z',
          dataCutoff: '2026-07-24T09:20:00Z',
          dataStatus: 'FRESH',
          scope: { type: 'SIDO', regionCode: '11' },
          category: 'ALL',
          items: [{
            articleId: 1,
            category: 'POLICY',
            title: '서울 아파트 정책 뉴스',
            providedAt: '2026-07-24T09:00:00Z',
            url: 'https://news.example.com/1',
            region: { code: '11', name: '서울특별시' },
            relationType: null,
          }],
          nextCursor: null,
        }));
      }
      return Promise.resolve(jsonResponse([]));
    }));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights/news');
    expect(new URLSearchParams(window.location.search).get('scope')).toBe('SIDO');
    expect(new URLSearchParams(window.location.search).get('regionCode')).toBe('11');
    expect(new URLSearchParams(window.location.search).get('category')).toBe('ALL');
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-sidebar-mode="news"]')).not.toBeNull();
    const newsModeLink = rootElement.querySelector<HTMLAnchorElement>('a[aria-label="부동산 뉴스"]');
    expect(newsModeLink?.querySelector('.map-mode-news-icon')).toBeNull();
    expect(newsModeLink?.querySelector('svg')).not.toBeNull();
    const link = rootElement.querySelector<HTMLAnchorElement>(
      'a[aria-label="서울 아파트 정책 뉴스 원문 새 창 열기"]',
    );
    expect(link?.target).toBe('_blank');
    expect(link?.rel).toContain('noopener');
    expect(rootElement.textContent).toContain('최근 30일 수집 뉴스');

    const policyTab = rootElement.querySelector<HTMLButtonElement>(
      '[role="tab"][aria-selected="false"]',
    );
    await act(async () => policyTab?.click());
    expect(new URLSearchParams(window.location.search).has('cursor')).toBe(false);

    unmount(root);
  });

  it('전국 뉴스 URL에 남은 regionCode를 제거해 유효한 API query를 만든다', async () => {
    window.history.pushState(
      {},
      '',
      '/insights/news?scope=NATIONWIDE&regionCode=11&category=ALL',
    );
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      snapshotId: null,
      generatedAt: null,
      dataCutoff: null,
      dataStatus: 'UNAVAILABLE',
      scope: { type: 'NATIONWIDE', regionCode: null },
      category: 'ALL',
      items: [],
      nextCursor: null,
    })));

    const { root } = await renderApp();
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights/news');
    expect(new URLSearchParams(window.location.search).get('scope')).toBe('NATIONWIDE');
    expect(new URLSearchParams(window.location.search).has('regionCode')).toBe(false);

    unmount(root);
  });

  it('단지 상세의 지역 뉴스 링크는 상세를 닫고 해당 시도 뉴스 rail을 연다', async () => {
    window.history.replaceState({}, '', '/?complexId=501');
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/complex/501')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5,
          longitude: 127,
          address: '서울특별시 강남구 테스트로',
          name: '테스트 단지',
          unitCnt: 740,
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 25,
          totalElements: 0,
          totalPages: 0,
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/news')) {
        return Promise.resolve(jsonResponse([{
          articleId: 1,
          category: 'REDEVELOPMENT',
          title: '강남구 테스트 단지 재건축 뉴스',
          providedAt: '2026-07-24T09:00:00Z',
          url: 'https://news.example.com/complex-501',
          region: { code: '11680', name: '강남구' },
          relationType: 'SAME_SIGUNGU',
        }]));
      }
      if (url.includes('/api/v1/insights/news?scope=SIDO&regionCode=11&category=ALL')) {
        return Promise.resolve(jsonResponse({
          snapshotId: '123e4567-e89b-12d3-a456-426614174000',
          generatedAt: '2026-07-24T09:30:00Z',
          dataCutoff: '2026-07-24T09:20:00Z',
          dataStatus: 'FRESH',
          scope: { type: 'SIDO', regionCode: '11' },
          category: 'ALL',
          items: [],
          nextCursor: null,
        }));
      }
      if (url.includes('/trend') || url.endsWith('/complexes')) {
        return Promise.resolve(jsonResponse([]));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: false });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    const regionalNewsLink = [...rootElement.querySelectorAll<HTMLAnchorElement>('a')]
      .find((link) => link.textContent === '이 지역 뉴스 모두 보기') ?? null;
    expect(regionalNewsLink).not.toBeNull();

    await act(async () => regionalNewsLink?.click());
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights/news');
    expect(new URLSearchParams(window.location.search).get('scope')).toBe('SIDO');
    expect(new URLSearchParams(window.location.search).get('regionCode')).toBe('11');
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).toBeNull();
    expect(rootElement.querySelector('[data-sidebar-mode="news"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('최근 30일 수집 뉴스');
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/insights/news?scope=SIDO&regionCode=11&category=ALL'),
      expect.objectContaining({ method: 'GET' }),
    );

    unmount(root);
  });
});
