import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createFakeKakaoSdk,
  deferred,
  errorResponse,
  flushAsyncState,
  flushLazyRoute,
  jsonResponse,
  renderApp,
  resetAppTestState,
  resolveApiUrl,
  unmount,
  waitForMillis,
} from './appTestHarness';

describe('App 단지 상세', () => {
  afterEach(resetAppTestState);

  it('상세와 marker가 함께 실패하면 한 번만 안내하고 하나의 action으로 둘 다 복구한다', async () => {
    window.history.replaceState({}, '', '/?complexId=501');
    let markerCalls = 0;
    let detailCalls = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) {
        markerCalls += 1;
        return Promise.resolve(markerCalls === 1
          ? errorResponse(500)
          : jsonResponse([{
              parcelId: 1001,
              complexId: 501,
              name: '복구 단지',
              lat: 37.5,
              lng: 127,
              latestDealAmount: 125000,
              unitCntSum: 740,
            }]));
      }
      if (url === resolveApiUrl('/api/v1/complex/501')) {
        detailCalls += 1;
        return Promise.resolve(detailCalls === 1
          ? errorResponse(500)
          : jsonResponse({
              parcelId: 1001,
              complexId: 501,
              latitude: 37.5,
              longitude: 127,
              address: '서울시 복구로',
              name: '복구 단지',
              unitCnt: 740,
            }));
      }
      if (url.includes('/trades')) {
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
      if (url.endsWith('/trade-areas')) return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.9)));
      if (url.includes('/trade-trend')) return Promise.resolve(jsonResponse([]));
      if (url.endsWith('/complexes')) return Promise.resolve(jsonResponse([]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelectorAll('.request-state-warning')).toHaveLength(1);
    const retry = [...rootElement.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent === '단지 다시 불러오기') ?? null;
    expect(retry).not.toBeNull();

    await act(async () => retry?.click());
    await flushAsyncState();
    await flushAsyncState();

    expect(detailCalls).toBe(2);
    expect(markerCalls).toBe(2);
    expect(rootElement.textContent).toContain('복구 단지');
    unmount(root);
  });

  it('거래 다음 페이지 실패 시 기존 거래를 유지하고 같은 페이지를 다시 불러온다', async () => {
    let nextPageCalls = 0;
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([{
          parcelId: 1001,
          complexId: 501,
          name: '거래 복구 단지',
          lat: 37.5,
          lng: 127,
          latestDealAmount: 125000,
          unitCntSum: 740,
        }]));
      }
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5,
          longitude: 127,
          address: '서울시 거래로',
          name: '거래 복구 단지',
          unitCnt: 740,
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.9, 2)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.9')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [{
            tradeId: 1,
            dealDate: '2026-06-01',
            exclArea: 84.9,
            dealAmount: 125000,
            aptDong: '101',
            floor: 12,
          }],
          page: 0,
          size: 25,
          totalElements: 2,
          totalPages: 2,
        }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?page=1&size=25&exclArea=84.9')) {
        nextPageCalls += 1;
        return Promise.resolve(nextPageCalls === 1
          ? errorResponse(503)
          : jsonResponse({
              parcelId: 1001,
              complexId: 501,
              content: [{
                tradeId: 2,
                dealDate: '2026-05-01',
                exclArea: 84.9,
                dealAmount: 120000,
                aptDong: '102',
                floor: 8,
              }],
              page: 1,
              size: 25,
              totalElements: 2,
              totalPages: 2,
            }));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=84.9')) {
        return Promise.resolve(jsonResponse([]));
      }
      if (url.endsWith('/complexes')) return Promise.resolve(jsonResponse([]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>(
        'button[aria-label="필지 1001 단지 501 상세 열기"]',
      )?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    await act(async () => {
      [...rootElement.querySelectorAll<HTMLButtonElement>('button')]
        .find((button) => button.textContent === '더보기')?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('12억 5,000만원');
    expect(rootElement.textContent).toContain('거래를 더 불러오지 못했어요');

    await act(async () => {
      [...rootElement.querySelectorAll<HTMLButtonElement>('button')]
        .find((button) => button.textContent === '거래 이어서 불러오기')?.click();
    });
    await flushAsyncState();

    expect(nextPageCalls).toBe(2);
    expect(rootElement.textContent).toContain('12억');
    expect(rootElement.textContent).not.toContain('거래를 더 불러오지 못했어요');
    unmount(root);
  });

  it('새 단지 상세가 대기 중이면 이전 단지 identity를 표시하지 않는다', async () => {
    const secondDetail = deferred<Response>();
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);
      if (requestUrl === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([
          { parcelId: 1001, complexId: 501, lat: 37.51, lng: 127.04, latestDealAmount: 125000 },
          { parcelId: 1002, complexId: 502, lat: 37.52, lng: 127.06, latestDealAmount: 130000 },
        ]));
      }
      if (requestUrl === resolveApiUrl('/api/v1/detail/1001?complexId=501')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          address: '이전 단지 주소',
          name: '이전 단지 이름',
        }));
      }
      if (requestUrl === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.9)));
      }
      if (requestUrl === resolveApiUrl('/api/v1/detail/1002?complexId=502')) {
        return secondDetail.promise;
      }
      if (requestUrl.includes('/api/v1/complex/') && (requestUrl.includes('/trades') || requestUrl.includes('/trade-trend'))) {
        return Promise.resolve(jsonResponse(requestUrl.includes('/trend') ? [] : {
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      }
      if (requestUrl.endsWith('/complexes')) return Promise.resolve(jsonResponse([]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>(
        'button[aria-label="필지 1001 단지 501 상세 열기"]',
      )?.click();
    });
    await flushAsyncState();
    expect(rootElement.textContent).toContain('이전 단지 이름');

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>(
        'button[aria-label="필지 1002 단지 502 상세 열기"]',
      )?.click();
    });
    await act(async () => Promise.resolve());

    expect(rootElement.textContent).not.toContain('이전 단지 이름');
    expect(rootElement.textContent).not.toContain('이전 단지 주소');
    unmount(root);
  });

  it('상세의 확정 complexId로 면적을 먼저 고르고 선택·더보기에 exact area를 유지한다', async () => {
    const areasDeferred = deferred<Response>();
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) return Promise.resolve(jsonResponse([{
        parcelId: 1001, complexId: 501, lat: 37.5, lng: 127,
        latestDealAmount: 88000, unitCntSum: 740,
      }]));
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) return Promise.resolve(jsonResponse({
        parcelId: 1001, complexId: 501, address: '면적 테스트로', name: '면적 테스트 단지', unitCnt: 740,
      }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) return areasDeferred.promise;
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([]));
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.94')) {
        return Promise.resolve(jsonResponse(tradePage(501, 84.94, 1, 1)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=97.9')) {
        return Promise.resolve(jsonResponse(tradePage(501, 97.9, 2, 2)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?page=1&size=25&exclArea=97.9')) {
        return Promise.resolve(jsonResponse({ ...tradePage(501, 97.9, 2, 2), page: 1,
          content: [{ tradeId: 3, dealDate: '2026-05-01', exclArea: 97.9,
            dealAmount: 87000, aptDong: null, floor: 8 }] }));
      }
      if (url.includes('/trade-trend?exclArea=')) return Promise.resolve(jsonResponse([]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="필지 1001 단지 501 상세 열기"]',
    )?.click());
    await flushAsyncState();

    expect(fetchMock.mock.calls.map(([url]) => String(url)).some((url) => url.includes('/trades?'))).toBe(false);
    expect(fetchMock.mock.calls.map(([url]) => String(url)).some((url) => url.includes('/trade-trend?'))).toBe(false);

    areasDeferred.resolve(jsonResponse({ complexId: 501, defaultExclArea: 84.94, areas: [
      { exclArea: 84.94, tradeCount: 1, latestDealDate: '2026-07-16' },
      { exclArea: 97.9, tradeCount: 2, latestDealDate: '2026-06-01' },
    ] }));
    await flushAsyncState();
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.94'),
      expect.objectContaining({ method: 'GET' }),
    );

    const selector = rootElement.querySelector<HTMLSelectElement>('#detail-excl-area');
    await act(async () => {
      if (selector) {
        selector.value = '97.9';
        selector.dispatchEvent(new Event('change', { bubbles: true }));
      }
    });
    await flushAsyncState();
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=97.9'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('97.90㎡ 거래 내역');

    await act(async () => [...rootElement.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent === '더보기')?.click());
    await flushAsyncState();
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?page=1&size=25&exclArea=97.9'),
      expect.objectContaining({ method: 'GET' }),
    );
    unmount(root);
  });

  it('면적 변경은 이전 거래·추이 요청을 중단하고 늦은 응답을 반영하지 않는다', async () => {
    const oldTrades = deferred<Response>();
    const oldTrend = deferred<Response>();
    let oldTradeSignal: AbortSignal | undefined;
    let oldTrendSignal: AbortSignal | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) return Promise.resolve(jsonResponse([{
        parcelId: 1001, complexId: 501, lat: 37.5, lng: 127, latestDealAmount: 88000, unitCntSum: 740,
      }]));
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) return Promise.resolve(jsonResponse({
        parcelId: 1001, complexId: 501, address: '중단 테스트로', name: '중단 테스트 단지', unitCnt: 740,
      }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) return Promise.resolve(jsonResponse({
        complexId: 501, defaultExclArea: 84.94, areas: [
          { exclArea: 84.94, tradeCount: 1, latestDealDate: '2026-07-16' },
          { exclArea: 97.9, tradeCount: 1, latestDealDate: '2026-06-01' },
        ],
      }));
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([]));
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.94')) {
        oldTradeSignal = init?.signal ?? undefined;
        return oldTrades.promise;
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=84.94')) {
        oldTrendSignal = init?.signal ?? undefined;
        return oldTrend.promise;
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=97.9')) {
        return Promise.resolve(jsonResponse(tradePage(501, 97.9, 1, 1)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=97.9')) {
        return Promise.resolve(jsonResponse([]));
      }
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="필지 1001 단지 501 상세 열기"]',
    )?.click());
    await flushAsyncState();
    const selector = rootElement.querySelector<HTMLSelectElement>('#detail-excl-area');
    await act(async () => {
      if (selector) {
        selector.value = '97.9';
        selector.dispatchEvent(new Event('change', { bubbles: true }));
      }
    });
    await flushAsyncState();
    expect(oldTradeSignal?.aborted).toBe(true);
    expect(oldTrendSignal?.aborted).toBe(true);

    oldTrades.resolve(jsonResponse(tradePage(501, 84.94, 1, 1)));
    oldTrend.resolve(jsonResponse([{ month: '2026-07', avgAmount: 999999,
      count: 1, minAmount: 999999, maxAmount: 999999 }]));
    await flushAsyncState();
    expect(rootElement.textContent).toContain('97.90㎡ 거래 내역');
    expect(rootElement.textContent).not.toContain('99억 9,999만원');
    unmount(root);
  });

  it('단지 선택은 왼쪽 sidebar를 상세 모드로 바꾸고 뒤로가기로 지역 탐색에 복귀한다', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([
          {
            parcelId: 1001,
            complexId: 501,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
        ]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/detail/1001?complexId=501')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
          unitCnt: 740,
        }));
      }
      if (requestUrl === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.9)));
      }

      if (requestUrl === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.9')) {
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/detail/1001/complexes')) {
        return Promise.resolve(jsonResponse([]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    document.body.append(rootElement);
    await flushAsyncState();

    const markerButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="필지 1001 단지 501 상세 열기"]',
    );
    await act(async () => {
      markerButton?.click();
    });
    await flushAsyncState();

    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    expect(explorationPanel?.dataset.sidebarMode).toBe('detail');
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-sidebar-mode')).toBe('detail');
    expect(rootElement.querySelector('[data-ui-layer="detail-sidebar"]')).not.toBeNull();
    expect(rootElement.querySelector('.detail-drawer')).toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');

    const backButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="상세에서 뒤로가기"]',
    );
    await act(async () => {
      backButton?.click();
    });

    expect(explorationPanel?.dataset.sidebarMode).toBe('region');
    expect(rootElement.querySelector('#exploration-panel-region')?.hasAttribute('hidden')).toBe(
      false,
    );

    await act(async () => {
      markerButton?.click();
    });
    await flushAsyncState();
    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="상세 닫기"]')?.click();
    });
    await act(async () => Promise.resolve());

    expect(explorationPanel?.hasAttribute('hidden')).toBe(true);
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-exploration-open')).toBe('false');
    expect(document.activeElement).toBe(
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="검색 패널 열기"]'),
    );

    unmount(root);
    rootElement.remove();
  });

  it('complex marker에서 detail sidebar를 열고 documented detail/trade data를 load한다', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) return Promise.resolve(jsonResponse([{
        parcelId: 1001, complexId: 501, lat: 37.5123, lng: 127.0456,
        latestDealAmount: 125000, unitCntSum: 740,
      }]));
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
          dongCnt: 8,
          unitCnt: 740,
          useDate: '2015-03-20',
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.93, 2)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.93')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [
            {
              tradeId: 9001,
              dealDate: '2025-12-01',
              exclArea: 84.93,
              dealAmount: 125000,
              aptDong: '101',
              floor: 12,
            },
            {
              tradeId: 9000,
              dealDate: '2025-10-15',
              exclArea: 84.93,
              dealAmount: 118000,
              aptDong: '101',
              floor: 9,
            },
          ],
          page: 0,
          size: 20,
          totalElements: 2,
          totalPages: 1,
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=84.93')) return Promise.resolve(jsonResponse([
          { month: '2025-10', avgAmount: 118000, count: 1, minAmount: 118000, maxAmount: 118000 },
          { month: '2025-12', avgAmount: 125000, count: 1, minAmount: 125000, maxAmount: 125000 },
        ]));
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample complex name',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
            unitCnt: 740,
          },
        ]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const markerButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="필지 1001 단지 501 상세 열기"]',
    );
    expect(markerButton).not.toBeNull();

    await act(async () => {
      markerButton?.click();
    });
    await flushAsyncState();
    await flushLazyRoute();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.93'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001/complexes'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="detail-sidebar"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-detail-section="identity"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-detail-section="trade-history"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('Sample address');
    expect(rootElement.textContent).toContain('2025-12-01');
    expect(rootElement.textContent).toContain('12억 5,000만원');
    const chartSection = rootElement.querySelector<HTMLElement>(
      '[aria-label="84.93㎡ 거래가 차트"]',
    );

    expect(chartSection).not.toBeNull();
    expect(chartSection?.textContent).toContain('실거래가 흐름');
    expect(
      Array.from(chartSection?.querySelectorAll('.trade-range-button') ?? []).map(
        (button) => button.textContent,
      ),
    ).toEqual(['전체', '최근 3년']);
    expect(
      Array.from(rootElement.querySelectorAll('[data-trade-cell="amount"]')).map((cell) =>
        cell.textContent,
      ),
    ).toEqual(['12억 5,000만원', '11억 8,000만원']);

    unmount(root);
  });

  it('prediction PENDING이면 detail API만 polling해서 READY 예상가를 표시한다', async () => {
    let detailCalls = 0;
    let tradeCalls = 0;
    const detailUrl = resolveApiUrl('/api/v1/detail/1001?complexId=501');
    const tradeUrl = resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.69');
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([
          {
            parcelId: 1001,
            complexId: 501,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
        ]));
      }

      if (requestUrl === detailUrl) {
        detailCalls += 1;
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          name: 'Sample complex name',
          unitCnt: 740,
          prediction: detailCalls <= 2
            ? {
                status: 'PENDING',
                modelVersion: 'deployment__F37_monthly_anchor_prev3_rolling_huber_010',
                predictedDealAmount: null,
                predictedPricePerM2: null,
                predictedPricePerPyeong: null,
                intervalLow: null,
                intervalHigh: null,
                intervalBasis: 'recent_holdout_p95',
                targetAreaM2: 84.69,
                targetFloor: 6,
                basisTradeId: 9001,
                basisDealDate: '2026-01-01',
                generatedAt: '2026-06-25T07:05:38Z',
                message: null,
              }
            : {
                status: 'READY',
                modelVersion: 'deployment__F37_monthly_anchor_prev3_rolling_huber_010',
                predictedDealAmount: 179163,
                predictedPricePerM2: 2115.5,
                predictedPricePerPyeong: 6993.4,
                intervalLow: 139425,
                intervalHigh: 218900,
                intervalBasis: 'recent_holdout_p95',
                targetAreaM2: 84.69,
                targetFloor: 6,
                basisTradeId: 9001,
                basisDealDate: '2026-01-01',
                generatedAt: '2026-06-25T07:05:38Z',
                message: null,
              },
        }));
      }

      if (requestUrl === tradeUrl) {
        tradeCalls += 1;
        return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.69)));
      }

      if (requestUrl === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=84.69')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/detail/1001/complexes')) {
        return Promise.resolve(jsonResponse([]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const markerButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="필지 1001 단지 501 상세 열기"]',
    );
    await act(async () => {
      markerButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('AI 예상가 계산 중');
    expect(detailCalls).toBe(1);
    expect(tradeCalls).toBe(1);

    await waitForMillis(2100);
    await flushAsyncState();

    expect(rootElement.textContent).toContain('AI 예상가 계산 중');
    expect(detailCalls).toBe(2);
    expect(tradeCalls).toBe(1);

    await waitForMillis(2100);
    await flushAsyncState();

    expect(rootElement.textContent).toContain('AI 예상 거래가');
    expect(rootElement.textContent).toContain('17억 9,163만원');
    expect(rootElement.textContent).toContain('예상 범위 13억 9,425만원 ~ 21억 8,900만원');
    expect(rootElement.querySelector('[aria-label="AI 예상가 계산 방식 안내"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('최근 실거래를 기준으로 면적, 층, 지역 정보를 반영해 계산한 예상가입니다.');
    expect(rootElement.textContent).toContain('예상 범위는 최근 검증 데이터의 오차를 기준으로 산정했습니다.');
    expect(detailCalls).toBe(3);
    expect(tradeCalls).toBe(1);

    unmount(root);
  });

  it('Kakao CustomOverlay complex marker에서 detail sidebar를 연다', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/complexes')) return Promise.resolve(jsonResponse([{
        parcelId: 1001, complexId: 501, lat: 37.5123, lng: 127.0456,
        latestDealAmount: 125000, unitCntSum: 740,
      }]));
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
          dongCnt: 8,
          unitCnt: 740,
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 84.93)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.93')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=84.93')) {
        return Promise.resolve(jsonResponse([]));
      }
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample complex name',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]));
      return Promise.resolve(errorResponse(404));
    });
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 4,
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();

    expect(sdk.overlays[0]?.content.getAttribute('aria-label')).toBe(
      '필지 1001 단지 501 상세 열기',
    );
    expect(sdk.overlays[0]?.yAnchor).toBe(1);

    await act(async () => {
      sdk.overlays[0]?.content.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=84.93'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('표시할 거래가 없습니다');
    expect(rootElement.querySelector('[data-detail-section="trade-history"]')).not.toBeNull();

    unmount(root);
  });

  it('complexId query parameter로 direct detail/trade를 열고 같은 parcel complex를 전환한다', async () => {
    window.history.pushState({}, '', '/?complexId=502');
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/regions')) return Promise.resolve(jsonResponse([]));
      if (url === resolveApiUrl('/api/v1/complex/502')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 502,
          latitude: 37.6123,
          longitude: 127.1456,
          address: 'Sample address',
          tradeName: 'Tower B',
          name: 'Sample Tower B',
        }));
      if (url === resolveApiUrl('/api/v1/complex/502/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(502, 84.94)));
      }
      if (url === resolveApiUrl('/api/v1/complex/502/trades?exclArea=84.94')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 502,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      if (url === resolveApiUrl('/api/v1/complex/502/trade-trend?exclArea=84.94')) {
        return Promise.resolve(jsonResponse([]));
      }
      if (url === resolveApiUrl('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Tower A',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
            unitCnt: 320,
          },
          {
            complexId: 502,
            complexName: 'Tower B',
            parcelId: 1001,
            latitude: 37.6123,
            longitude: 127.1456,
            address: 'Sample address',
            unitCnt: 410,
          },
        ]));
      if (url === resolveApiUrl('/api/v1/detail/1001?complexId=501')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          name: 'Sample Tower A',
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(501, 59.93)));
      }
      if (url === resolveApiUrl('/api/v1/complex/501/trades?exclArea=59.93')) return Promise.resolve(jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }));
      if (url === resolveApiUrl('/api/v1/complex/501/trade-trend?exclArea=59.93')) {
        return Promise.resolve(jsonResponse([]));
      }
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trades?exclArea=84.94'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001/complexes'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('Sample Tower B');
    expect(rootElement.textContent).toContain('Tower A');

    const switchButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="같은 필지 단지 선택 Tower A"]',
    );
    await act(async () => {
      switchButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/501/trades?exclArea=59.93'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('Sample Tower A');

    unmount(root);
  });

  it('null address direct detail도 실제 API 데이터 요약과 거래 내역을 표시한다', async () => {
    window.history.pushState({}, '', '/?complexId=4368');
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url === resolveApiUrl('/api/v1/map/regions')) return Promise.resolve(jsonResponse([]));
      if (url === resolveApiUrl('/api/v1/complex/4368')) return Promise.resolve(jsonResponse({
          parcelId: 4669,
          complexId: 4368,
          latitude: 37.5681,
          longitude: 126.9976,
          address: null,
          tradeName: '힐스테이트세운센트럴1단지',
          name: '힐스테이트세운센트럴1단지',
          dongCnt: 1,
          unitCnt: 206,
          useDate: '2023-02-15',
        }));
      if (url === resolveApiUrl('/api/v1/complex/4368/trade-areas')) {
        return Promise.resolve(jsonResponse(tradeAreasResponse(4368, 59.98)));
      }
      if (url === resolveApiUrl('/api/v1/complex/4368/trades?exclArea=59.98')) return Promise.resolve(jsonResponse({
          parcelId: 4669,
          complexId: 4368,
          content: [
            {
              tradeId: 9901,
              dealDate: '2026-05-01',
              exclArea: 59.98,
              dealAmount: 154000,
              aptDong: null,
              floor: 12,
            },
          ],
          page: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
        }));
      if (url === resolveApiUrl('/api/v1/complex/4368/trade-trend?exclArea=59.98')) return Promise.resolve(jsonResponse([
          { month: '2026-05', avgAmount: 154000, count: 1, minAmount: 154000, maxAmount: 154000 },
        ]));
      if (url === resolveApiUrl('/api/v1/detail/4669/complexes')) return Promise.resolve(jsonResponse([
          {
            complexId: 4368,
            complexName: '힐스테이트세운센트럴1단지',
            parcelId: 4669,
            latitude: 37.5681,
            longitude: 126.9976,
            address: null,
            unitCnt: 206,
          },
        ]));
      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/4368'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/4368/trades?exclArea=59.98'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/4669/complexes'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).not.toContain('상세 완료');
    expect(rootElement.textContent).toContain('주소 정보 없음');
    expect(rootElement.textContent).toContain('15억 4,000만원');
    expect(rootElement.textContent).not.toContain('상세 정보를 불러오지 못했습니다');

    unmount(root);
  });
});

function tradeAreasResponse(complexId: number, exclArea: number, tradeCount = 1) {
  return {
    complexId,
    defaultExclArea: exclArea,
    areas: [{ exclArea, tradeCount, latestDealDate: '2026-07-16' }],
  };
}

function tradePage(complexId: number, exclArea: number, totalElements: number, totalPages: number) {
  return {
    parcelId: 1001,
    complexId,
    content: [{ tradeId: 2, dealDate: '2026-06-01', exclArea,
      dealAmount: 88000, aptDong: null, floor: 10 }],
    page: 0,
    size: 25,
    totalElements,
    totalPages,
  };
}
