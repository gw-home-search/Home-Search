import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createFakeKakaoSdk,
  deferred,
  errorResponse,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  resolveApiUrl,
  unmount,
  waitForMillis,
} from './appTestHarness';

describe('App 단지 상세', () => {
  afterEach(resetAppTestState);

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
      if (requestUrl === resolveApiUrl('/api/v1/detail/1002?complexId=502')) {
        return secondDetail.promise;
      }
      if (requestUrl.includes('/api/v1/trade/') || requestUrl.includes('/trend')) {
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

      if (requestUrl === resolveApiUrl('/api/v1/trade/1001?complexId=501')) {
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
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse([
          {
            parcelId: 1001,
            complexId: 501,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
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
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
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
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse([
          { month: '2025-10', avgAmount: 118000, count: 1, minAmount: 118000, maxAmount: 118000 },
          { month: '2025-12', avgAmount: 125000, count: 1, minAmount: 125000, maxAmount: 125000 },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample complex name',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
            unitCnt: 740,
          },
        ]),
      );
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

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001?complexId=501'),
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
      '[aria-label="거래가 차트"]',
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
    const tradeUrl = resolveApiUrl('/api/v1/trade/1001?complexId=501');
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

      if (requestUrl === resolveApiUrl('/api/v1/trade/1001/trend?complexId=501')) {
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
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse([
          {
            parcelId: 1001,
            complexId: 501,
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
          dongCnt: 8,
          unitCnt: 740,
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample complex name',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]),
      );
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
      resolveApiUrl('/api/v1/trade/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('시세를 불러오지 못했어요');
    expect(rootElement.querySelector('[data-detail-section="trade-history"]')).not.toBeNull();

    unmount(root);
  });

  it('complexId query parameter로 direct detail/trade를 열고 같은 parcel complex를 전환한다', async () => {
    window.history.pushState({}, '', '/?complexId=502');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 502,
          latitude: 37.6123,
          longitude: 127.1456,
          address: 'Sample address',
          tradeName: 'Tower B',
          name: 'Sample Tower B',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 502,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
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
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          name: 'Sample Tower A',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          content: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trades'),
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
      resolveApiUrl('/api/v1/trade/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('Sample Tower A');

    unmount(root);
  });

  it('null address direct detail도 실제 API 데이터 요약과 거래 내역을 표시한다', async () => {
    window.history.pushState({}, '', '/?complexId=4368');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse({
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
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
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
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse([
          { month: '2026-05', avgAmount: 154000, count: 1, minAmount: 154000, maxAmount: 154000 },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 4368,
            complexName: '힐스테이트세운센트럴1단지',
            parcelId: 4669,
            latitude: 37.5681,
            longitude: 126.9976,
            address: null,
            unitCnt: 206,
          },
        ]),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/4368'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/4368/trades'),
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
