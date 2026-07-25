import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  deferred,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  unmount,
  waitForMillis,
} from './appTestHarness';
import type { InsightTradeItem, MarketInsights } from '../features/insights/api/fetchMarketInsights';

describe('App 지도 인사이트 통합', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    resetAppTestState();
  });

  it('직접 접근한 mobile /insights에서 같은 지도와 sheet를 유지하고 취소 없이 metric 전환은 재조회하지 않는다', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    window.history.pushState({}, '', '/insights');
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/v1/insights/trades/weekly')) return Promise.resolve(jsonResponse(insightResponse()));
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights');
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('#exploration-panel')?.hasAttribute('hidden')).toBe(false);
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-sidebar-mode')).toBe('insight');
    expect(new URLSearchParams(window.location.search).get('metric')).toBe('new');
    expect(new URLSearchParams(window.location.search).get('scope')).toBe('SIDO');
    expect(new URLSearchParams(window.location.search).get('regionCode')).toBe('11');
    expect(rootElement.querySelector('[aria-current="page"]')?.textContent).toContain('신규');
    expect(rootElement.textContent).toContain('래미안 테스트');
    expect(rootElement.textContent).toContain('최근 7일 · 7.17–7.23');
    expect(rootElement.textContent).toContain('계약 7.01 · 등록 7.18');
    expect(rootElement.textContent).toContain('최근 계약 · 직전 거래 비교');
    expect(rootElement.textContent).toContain('현재 거래집계 종료일 기준 최근 1개월 이내 계약');
    expect(rootElement.textContent).toContain('직전 비교같은 단지·같은 전용면적의 6개월 이내 직전 계약');
    expect(rootElement.textContent).toContain('신고가 기준최근 직전 거래가 있는 거래 중 과거 최고가 갱신');
    expect(insightRequestCount(fetchMock)).toBe(1);

    const tradeList = rootElement.querySelector<HTMLDivElement>('.insight-trade-list');
    if (tradeList) tradeList.scrollTop = 320;

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('a[aria-label="상승 인사이트"]')?.click();
    });
    await flushAsyncState();

    expect(new URLSearchParams(window.location.search).get('metric')).toBe('rise');
    expect(tradeList?.scrollTop).toBe(0);
    expect(insightRequestCount(fetchMock)).toBe(1);
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('scope=SIDO&regionCode=11&limit=20'))).toBe(true);
    expect(rootElement.querySelector('a[aria-label="취소 인사이트"]')).toBeNull();
    expect(rootElement.querySelectorAll('nav[aria-label="지도 탐색 모드"] a')).toHaveLength(7);
    expect(rootElement.textContent).not.toContain('최근 7일 취소 거래');

    unmount(root);
  });

  it('제거된 cancellation deep link는 신규 인사이트로 정규화한다', async () => {
    window.history.pushState({}, '', '/insights?metric=cancellation&scope=SIDO&regionCode=11');
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(insightResponse()))
      : Promise.resolve(jsonResponse([]))));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(new URLSearchParams(window.location.search).get('metric')).toBe('new');
    expect(rootElement.querySelector('[aria-current="page"]')?.textContent).toContain('신규');
    expect(rootElement.querySelector('a[aria-label="취소 인사이트"]')).toBeNull();
    expect(rootElement.textContent).not.toContain('최근 7일 취소 거래');

    unmount(root);
  });

  it('탭 복귀 시 scope 캐시가 5분을 넘었으면 기존 목록을 유지하며 갱신한다', async () => {
    window.history.pushState({}, '', '/insights');
    let now = Date.parse('2026-07-23T03:00:00Z');
    vi.spyOn(Date, 'now').mockImplementation(() => now);
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    const fetchMock = vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(insightResponse()))
      : Promise.resolve(jsonResponse([])));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();
    expect(insightRequestCount(fetchMock)).toBe(1);

    now += 5 * 60 * 1000 + 1;
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(rootElement.textContent).toContain('래미안 테스트');
    await flushAsyncState();

    expect(insightRequestCount(fetchMock)).toBe(2);
    expect(rootElement.textContent).toContain('래미안 테스트');
    unmount(root);
  });

  it('STALE 거래를 숨기지 않고 내부 수집 용어 없이 상태를 설명한다', async () => {
    window.history.pushState({}, '', '/insights?metric=new');
    const staleResponse = insightResponse();
    staleResponse.dataStatus = 'STALE';
    staleResponse.quality.missingRegistrationDateCount = 2;
    staleResponse.quality.missingCancellationDateCount = 7;
    staleResponse.quality.excludedCount = 7;
    staleResponse.newTrades[0].registrationDate = null;
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(staleResponse))
      : Promise.resolve(jsonResponse([]))));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.textContent).toContain('래미안 테스트');
    expect(rootElement.textContent).toContain('최신 집계 확인 중');
    expect(rootElement.textContent).not.toContain('등록일이 없는 2건도 계약일 기준으로 순위에 반영했어요');
    expect(rootElement.textContent).toContain('계약 7.01 · 등록일 미제공 · 계약일 기준');
    expect(rootElement.textContent).toContain('최근 계약 · 직전 거래 비교');
    expect(rootElement.textContent).not.toContain('순위에서 제외됐어요');
    expect(rootElement.textContent).not.toContain('DAILY');
    expect(rootElement.textContent).not.toContain('snapshot');
    expect(rootElement.textContent).not.toContain('정상 수집');

    unmount(root);
  });

  it('인사이트 상세을 닫으면 기존 URL과 row focus를 복원한다', async () => {
    window.history.pushState({}, '', '/insights?metric=rise');
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(insightResponse()))
      : Promise.resolve(jsonResponse([]))));

    const { root, rootElement } = await renderApp();
    document.body.append(rootElement);
    await flushAsyncState();
    await flushAsyncState();
    const row = rootElement.querySelector<HTMLButtonElement>('button[aria-label="1위 래미안 테스트 상세 보기"]');
    const tradeList = rootElement.querySelector<HTMLDivElement>('.insight-trade-list');
    if (tradeList) tradeList.scrollTop = 240;

    await act(async () => row?.click());
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="상세에서 뒤로가기"]')?.click());
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights');
    expect(new URLSearchParams(window.location.search).get('metric')).toBe('rise');
    expect(tradeList?.scrollTop).toBe(240);
    expect(document.activeElement).toBe(row);

    unmount(root);
    rootElement.remove();
  });

  it('UNAVAILABLE은 목록 대신 집계 준비 상태를 표시한다', async () => {
    window.history.pushState({}, '', '/insights');
    const unavailableResponse = insightResponse();
    unavailableResponse.dataStatus = 'UNAVAILABLE';
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(unavailableResponse))
      : Promise.resolve(jsonResponse([]))));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.textContent).toContain('집계 준비 중');
    expect(rootElement.textContent).not.toContain('래미안 테스트');

    unmount(root);
  });

  it('inline 시도 선택은 scope URL과 인사이트 요청을 함께 갱신한다', async () => {
    window.history.pushState({}, '', '/insights?metric=highest&scope=SIDO&regionCode=11');
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/v1/region')) {
        return Promise.resolve(jsonResponse([
          { id: 1, name: '서울특별시', code: '11' },
          { id: 2, name: '부산광역시', code: '26' },
        ]));
      }
      if (url.endsWith('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({ id: 1, name: '서울특별시', code: '11', latitude: 37.5663, longitude: 126.978, children: [] }));
      }
      if (url.endsWith('/api/v1/region/2')) {
        return Promise.resolve(jsonResponse({ id: 2, name: '부산광역시', code: '26', latitude: 35.1796, longitude: 129.0756, children: [] }));
      }
      if (url.includes('/api/v1/insights/trades/weekly')) return Promise.resolve(jsonResponse(insightResponse()));
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();
    const changeButton = rootElement.querySelector<HTMLButtonElement>('button[aria-label="인사이트 지역 변경"]');
    expect(changeButton?.getAttribute('aria-expanded')).toBe('false');

    await act(async () => changeButton?.click());
    const busan = rootElement.querySelector<HTMLButtonElement>('button[aria-label="인사이트 지역 선택 부산광역시"]');
    expect(changeButton?.getAttribute('aria-expanded')).toBe('true');
    expect(busan).not.toBeNull();
    await act(async () => busan?.click());
    await flushAsyncState();

    const params = new URLSearchParams(window.location.search);
    expect(params.get('metric')).toBe('highest');
    expect(params.get('scope')).toBe('SIDO');
    expect(params.get('regionCode')).toBe('26');
    expect(changeButton?.getAttribute('aria-expanded')).toBe('false');
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('scope=SIDO&regionCode=26&limit=20'))).toBe(true);
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/api/v1/region/2'))).toBe(true);

    unmount(root);
  });

  it('이전 scope 응답이 늦게 도착해도 새 지역 목록을 덮어쓰지 않는다', async () => {
    window.history.pushState({}, '', '/insights?metric=new&scope=SIDO&regionCode=11');
    const seoulRequest = deferred<Response>();
    const busanResponse = insightResponse();
    busanResponse.newTrades[0].complexName = '부산 최신 단지';
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.endsWith('/api/v1/region')) {
        return Promise.resolve(jsonResponse([
          { id: 1, name: '서울특별시', code: '11' },
          { id: 2, name: '부산광역시', code: '26' },
        ]));
      }
      if (url.endsWith('/api/v1/region/2')) {
        return Promise.resolve(jsonResponse({
          id: 2,
          name: '부산광역시',
          code: '26',
          latitude: 35.1796,
          longitude: 129.0756,
          children: [],
        }));
      }
      if (url.includes('/api/v1/insights/trades/weekly') && url.includes('regionCode=26')) {
        return Promise.resolve(jsonResponse(busanResponse));
      }
      if (url.includes('/api/v1/insights/trades/weekly')) return seoulRequest.promise;
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="인사이트 지역 변경"]')?.click();
    });
    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="인사이트 지역 선택 부산광역시"]')?.click();
    });
    await flushAsyncState();
    expect(rootElement.textContent).toContain('부산 최신 단지');

    await act(async () => {
      seoulRequest.resolve(jsonResponse(insightResponse()));
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('부산 최신 단지');
    expect(rootElement.textContent).not.toContain('래미안 테스트');
    unmount(root);
  });

  it('신고가는 percentage와 이전 금액 및 금액차를 함께 표시한다', async () => {
    window.history.pushState({}, '', '/insights?metric=record-high');
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => String(input).includes('/api/v1/insights/trades/weekly')
      ? Promise.resolve(jsonResponse(insightResponse()))
      : Promise.resolve(jsonResponse([]))));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.textContent).toContain('신고가 +4.5%');
    expect(rootElement.textContent).toContain('이전 최고(6.01) 24억 4,000만원 → 25억 5,000만원');
    expect(rootElement.textContent).toContain('금액차 +1억 1,000만원');

    unmount(root);
  });

  it('인사이트 URL의 검색 결과가 활성화되면 목록을 잠시 숨기고 검색 종료 후 복원한다', async () => {
    window.history.pushState({}, '', '/insights?metric=fall');
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes('/api/v1/insights/trades/weekly')) return Promise.resolve(jsonResponse(insightResponse()));
      if (url.includes('/api/v1/search/complexes?q=Sample')) return Promise.resolve(jsonResponse([]));
      return Promise.resolve(jsonResponse([]));
    }));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    const input = rootElement.querySelector<HTMLInputElement>('input[aria-label="단지 검색"]');

    await act(async () => {
      if (input) {
        input.value = 'Sample';
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await waitForMillis(350);
    await flushAsyncState();

    expect(rootElement.querySelector<HTMLElement>('#exploration-panel')?.dataset.sidebarMode).toBe('search');
    expect(rootElement.querySelector('section[aria-label="거래 인사이트"]')?.hasAttribute('hidden')).toBe(true);

    await act(async () => {
      if (input) {
        input.value = '';
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await flushAsyncState();

    expect(window.location.pathname).toBe('/insights');
    expect(new URLSearchParams(window.location.search).get('metric')).toBe('fall');
    expect(rootElement.querySelector<HTMLElement>('#exploration-panel')?.dataset.sidebarMode).toBe('insight');
    expect(rootElement.querySelector('section[aria-label="거래 인사이트"]')?.hasAttribute('hidden')).toBe(false);

    unmount(root);
  });
});

function insightRequestCount(fetchMock: ReturnType<typeof vi.fn>): number {
  return fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/insights/trades/weekly')).length;
}

function insightResponse(): MarketInsights {
  return buildInsightResponse();
}

function buildInsightResponse(): MarketInsights {
  const item: InsightTradeItem = {
    rank: 1,
    complexId: 501,
    parcelId: 1001,
    complexName: '래미안 테스트',
    sidoName: '서울특별시',
    sigunguName: '강남구',
    exclArea: 84.99,
    dealAmount: 255000,
    dealDate: '2026-07-01',
    disclosedAt: '2026-07-22T03:14:15Z',
    registrationDate: '2026-07-18',
    cancellationDate: null,
    previousAmount: 244000,
    previousDealDate: '2026-06-01',
    deltaAmount: 11000,
    deltaRate: 4.508197,
    currentCount: 1,
    previousCount: 1,
    comparisonSampleCount: 1,
    tradeStatus: 'ACTIVE',
    canceledAt: null,
  };
  return {
    snapshotId: 'd0fb824c-938e-4cc8-a674-336262ef4206',
    periodStart: '2026-07-17',
    periodEnd: '2026-07-23',
    generatedAt: '2026-07-22T06:31:00Z',
    dataCutoff: '2026-07-22T06:30:00Z',
    dataStatus: 'FRESH',
    scope: { type: 'NATIONWIDE', regionCode: null },
    quality: {
      missingRegistrationDateCount: 0,
      invalidRegistrationDateCount: 0,
      missingCancellationDateCount: 0,
      invalidCancellationDateCount: 0,
      excludedCount: 0,
    },
    newTrades: [item],
    highestDeals: [item],
    recordHighs: [item],
    previousRises: [item],
    previousFalls: [],
    cancellations: [{
      ...item,
      cancellationDate: '2026-07-20',
      tradeStatus: 'CANCELED',
      canceledAt: '2026-07-20T03:00:00Z',
    }],
  };
}
