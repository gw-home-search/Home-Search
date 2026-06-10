import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../features/map/api/resolveApiUrl';
import { App } from './App';

describe('App map-first shell 화면', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    window.sessionStorage.clear();
    window.history.pushState({}, '', '/');
  });

  it('public surface admin coordinate route는 관리자 화면을 노출하지 않는다', async () => {
    window.history.pushState({}, '', '/admin/coordinates');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();

    expect(rootElement.textContent).toContain('페이지를 찾을 수 없습니다');
    expect(rootElement.textContent).not.toContain('관리자 접근');
    expect(fetchMock).not.toHaveBeenCalled();

    unmount(root);
  });

  it('admin surface coordinate route는 관리자 접근 코드 입력 전 pending API를 호출하지 않는다', async () => {
    vi.stubEnv('VITE_APP_SURFACE', 'admin');
    window.history.pushState({}, '', '/admin/coordinates');
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushLazyRoute();

    expect(rootElement.textContent).toContain('관리자 접근');
    expect(rootElement.textContent).toContain('좌표 보강 관리는 관리자 전용 화면입니다');
    expect(fetchMock).not.toHaveBeenCalled();

    unmount(root);
  });

  it('admin coordinate route는 pending 조회와 override 승인을 호출한다', async () => {
    vi.stubEnv('VITE_APP_SURFACE', 'admin');
    window.history.pushState({}, '', '/admin/coordinates');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          coordinatePendingFixture(1001, '1168010300101400001', 'Pending Apartment', 'PNU_COORDINATE_MISSING'),
          coordinatePendingFixture(1002, '1168010300101400002', 'Same PNU Apartment', 'SAME_PNU_MULTI_COMPLEX'),
          ...Array.from({ length: 49 }, (_, index) =>
            coordinatePendingFixture(2000 + index, `116801030010140${String(index + 10).padStart(4, '0')}`, `Extra ${index}`, 'PNU_COORDINATE_MISSING'),
          ),
        ]),
      )
      .mockResolvedValueOnce(jsonResponse(coordinatePendingSummaryFixture()))
      .mockResolvedValueOnce(
        jsonResponse({
          pnu: '1168010300101400001',
          latitude: 37.5123,
          longitude: 127.0456,
          parcelUpdated: true,
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(jsonResponse(coordinatePendingSummaryFixture({
        totalCount: 1428,
        pnuCoordinateMissing: 320,
        samePnuMultiComplex: 1001,
        complexDisplayCoordinateMissing: 107,
      })));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushLazyRoute();

    setInputValue(rootElement, 'input[name="accessCode"]', 'test-admin');
    const accessForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="관리자 접근"]',
    );
    await act(async () => {
      submitForm(accessForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/pending?limit=51&offset=0'),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Admin-Access-Code': 'test-admin' },
      }),
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      resolveApiUrl('/api/v1/admin/coordinates/pending?limit=1&offset=0'),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Admin-Access-Code': 'test-admin' },
      }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/pending/summary'),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Admin-Access-Code': 'test-admin' },
      }),
    );
    expect(rootElement.textContent).toContain('좌표 보강 관리');
    expect(rootElement.textContent).toContain('마커 표시를 막는 보강 사유를 먼저 확인합니다');
    expect(rootElement.textContent).toContain('보강 사유 정리');
    expect(rootElement.textContent).toContain('전체 대기 항목');
    expect(rootElement.textContent).toContain('현재 페이지 항목');
    expect(rootElement.textContent).toContain('전체 사유 분포');
    expect(rootElement.textContent).toContain('1,429');
    expect(rootElement.textContent).toContain('Pending Apartment');
    expect(rootElement.textContent).toContain('PNU 좌표 없음');
    expect(rootElement.textContent).toContain('동일 PNU 다중 단지');

    const selectButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="좌표 보강 선택 1168010300101400001"]',
    );
    const displayFlowButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="좌표 보강 선택 1168010300101400002"]',
    );
    expect(displayFlowButton?.disabled).toBe(true);
    expect(rootElement.textContent).toContain('1페이지');
    expect(rootElement.textContent).toContain('다음');

    await act(async () => {
      selectButton?.click();
    });

    setInputValue(rootElement, 'input[name="latitude"]', '37.5123');
    setInputValue(rootElement, 'input[name="longitude"]', '127.0456');
    setInputValue(rootElement, 'textarea[name="reason"]', 'operator verified missing coordinate');
    const approveForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="좌표 보강 승인"]',
    );

    await act(async () => {
      submitForm(approveForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/1168010300101400001/override'),
      expect.objectContaining({
        method: 'PUT',
        headers: {
          'Content-Type': 'application/json',
          'X-Admin-Access-Code': 'test-admin',
        },
        body: JSON.stringify({
          latitude: 37.5123,
          longitude: 127.0456,
          reason: 'operator verified missing coordinate',
          approvedBy: 'local-operator',
        }),
      }),
    );
    expect(rootElement.textContent).toContain('좌표 승인이 완료되었습니다');

    unmount(root);
  });

  it('admin coordinate route는 다음 page에서 offset을 증가시켜 조회한다', async () => {
    vi.stubEnv('VITE_APP_SURFACE', 'admin');
    window.history.pushState({}, '', '/admin/coordinates');
    window.sessionStorage.setItem('home-search-admin-coordinate-access', 'granted');
    window.sessionStorage.setItem('home-search-admin-coordinate-access-code', 'test-admin');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse([
          coordinatePendingFixture(1001, '1168010300101400001', 'Pending Apartment', 'PNU_COORDINATE_MISSING'),
          ...Array.from({ length: 50 }, (_, index) =>
            coordinatePendingFixture(2000 + index, `116801030010141${String(index + 10).padStart(4, '0')}`, `Extra ${index}`, 'PNU_COORDINATE_MISSING'),
          ),
        ]),
      )
      .mockResolvedValueOnce(jsonResponse(coordinatePendingSummaryFixture()))
      .mockResolvedValueOnce(
        jsonResponse([
          coordinatePendingFixture(3001, '1168010300101500001', 'Second Page Apartment', 'PNU_COORDINATE_MISSING'),
        ]),
      )
      .mockResolvedValueOnce(jsonResponse(coordinatePendingSummaryFixture()));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushLazyRoute();

    const nextButton = Array.from(rootElement.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === '다음');
    await act(async () => {
      nextButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      resolveApiUrl('/api/v1/admin/coordinates/pending?limit=51&offset=50'),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Admin-Access-Code': 'test-admin' },
      }),
    );
    expect(rootElement.textContent).toContain('Second Page Apartment');
    expect(rootElement.textContent).toContain('2페이지');

    unmount(root);
  });

  it('admin reason route는 coordinate pending reason 설명을 한국어로 표시한다', async () => {
    vi.stubEnv('VITE_APP_SURFACE', 'admin');
    window.history.pushState({}, '', '/admin/coordinates/reasons');
    const fetchMock = vi.fn().mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushLazyRoute();

    setInputValue(rootElement, 'input[name="accessCode"]', 'test-admin');
    const accessForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="관리자 접근"]',
    );
    await act(async () => {
      submitForm(accessForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/admin/coordinates/pending?limit=1&offset=0'),
      expect.objectContaining({
        method: 'GET',
        headers: { 'X-Admin-Access-Code': 'test-admin' },
      }),
    );
    expect(rootElement.textContent).toContain('보강 사유 정리');
    expect(rootElement.textContent).toContain('PNU_COORDINATE_MISSING');
    expect(rootElement.textContent).toContain('PNU 좌표 없음');
    expect(rootElement.textContent).toContain('SAME_PNU_MULTI_COMPLEX');
    expect(rootElement.textContent).toContain('COMPLEX_DISPLAY_COORDINATE_MISSING');
    expect(rootElement.textContent).toContain('수동 승인 가능');
    expect(rootElement.textContent).toContain('단지별 표시 좌표 처리 필요');

    unmount(root);
  });

  it('collapsible exploration control과 in-map marker error가 있는 map-first shell을 rendering한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    const mapWorkspace = rootElement.querySelector('[data-layout-region="map-workspace"]');
    const mapSurface = rootElement.querySelector<HTMLElement>('[aria-label="지도 화면"]');
    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const filterPanel = rootElement.querySelector<HTMLElement>('form[aria-label="마커 필터"]');
    const explorationToggle = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="탐색 패널 접기"]',
    );
    const markerAlert = Array.from(rootElement.querySelectorAll('[role="alert"]')).find((alert) =>
      alert.textContent?.includes('마커 데이터를 불러오지 못했습니다'),
    );

    expect(mapWorkspace).not.toBeNull();
    expect(mapSurface).not.toBeNull();
    expect(mapWorkspace?.firstElementChild).toBe(mapSurface);
    expect(explorationPanel).not.toBeNull();
    expect(filterPanel?.getAttribute('data-map-overlay')).toBe('filters');
    expect(markerAlert?.closest('[aria-label="지도 화면"]')).toBe(mapSurface);
    expect(explorationToggle?.getAttribute('aria-controls')).toBe('exploration-panel');
    expect(explorationToggle?.getAttribute('aria-expanded')).toBe('true');

    await act(async () => {
      explorationToggle?.click();
    });

    expect(explorationToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(explorationPanel?.getAttribute('data-collapsed')).toBe('true');
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();

    unmount(root);
  });

  it('public map UI는 map-first design landmarks와 active filter state를 고정한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    expect(rootElement.querySelector('[data-ui-surface="map-first"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="filter-controls"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="exploration-panel"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('필터 없음');

    setInputValue(rootElement, 'input[aria-label="최소 평형"]', '20');
    setInputValue(rootElement, 'input[aria-label="최대 가격 억"]', '15');
    setInputValue(rootElement, 'input[aria-label="최소 세대수"]', '300');

    const filterForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="마커 필터"]',
    );
    await act(async () => {
      submitForm(filterForm);
    });
    await flushAsyncState();

    const filterPanel = rootElement.querySelector<HTMLElement>(
      '[data-ui-layer="filter-controls"]',
    );
    expect(filterPanel?.dataset.filterState).toBe('active');
    expect(filterPanel?.textContent).toContain('필터 3개 적용');

    const resetButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="마커 필터 초기화"]',
    );
    expect(resetButton).not.toBeNull();
    await act(async () => {
      resetButton?.click();
    });
    await flushAsyncState();

    const resetFilterPanel = rootElement.querySelector<HTMLElement>(
      '[data-ui-layer="filter-controls"]',
    );
    expect(resetFilterPanel?.dataset.filterState).toBe('idle');
    expect(resetFilterPanel?.textContent).toContain('필터 없음');
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.45,
          swLng: 126.85,
          neLat: 37.7,
          neLng: 127.2,
          pyeongMin: null,
          pyeongMax: null,
          priceEokMin: null,
          priceEokMax: null,
          ageMin: null,
          ageMax: null,
          unitMin: null,
          unitMax: null,
        }),
      }),
    );

    unmount(root);
  });

  it('map surface를 block하지 않고 marker loading state를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));

    const { root, rootElement } = await renderApp();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('마커 불러오는 중');

    unmount(root);
  });

  it('marker API가 empty list를 반환하면 empty marker state를 표시한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
      }),
    );
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('이 영역에는 마커가 없습니다');

    unmount(root);
  });

  it('detailed map level 전에는 region marker를 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root } = await renderApp({ initialMapLevel: 10 });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-do"'),
      }),
    );

    unmount(root);
  });

  it('map zoom 변경 시 marker를 refresh하고 stale response를 무시한다', async () => {
    const staleComplexResponse = deferred<Response>();
    const latestRegionResponse = deferred<Response>();
    const fetchMock = vi
      .fn()
      .mockReturnValueOnce(staleComplexResponse.promise)
      .mockReturnValueOnce(latestRegionResponse.promise);
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({ method: 'POST' }),
    );

    const zoomOutButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지도 축소"]',
    );
    expect(zoomOutButton).not.toBeNull();

    await act(async () => {
      zoomOutButton?.click();
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"eup-myeon-dong"'),
      }),
    );

    await act(async () => {
      latestRegionResponse.resolve(
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
          },
        ]),
      );
      await latestRegionResponse.promise;
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Seoul');

    await act(async () => {
      staleComplexResponse.resolve(
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
      );
      await staleComplexResponse.promise;
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Seoul');
    expect(rootElement.querySelector('[data-marker-id="1001"]')).toBeNull();

    unmount(root);
  });

  it('zoom control은 Kakao map runtime level도 변경한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 4,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const zoomOutButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지도 축소"]',
    );
    await act(async () => {
      zoomOutButton?.click();
    });

    expect(sdk.map.setLevel).toHaveBeenLastCalledWith(5);

    unmount(root);
  });

  it('map surface를 제거하지 않고 non-blocking marker error를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      '마커 데이터를 불러오지 못했습니다',
    );
    expect(rootElement.textContent).toContain('지도는 계속 사용할 수 있습니다');
    expect(rootElement.querySelectorAll('[data-marker-id]')).toHaveLength(0);

    unmount(root);
  });

  it('complex marker에서 detail drawer를 열고 documented detail/trade data를 load한다', async () => {
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
          trades: [
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
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
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
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="detail-drawer"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-detail-section="identity"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-detail-section="trade-history"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('Sample address');
    expect(rootElement.textContent).toContain('2025-12-01');
    expect(rootElement.textContent).toContain('125,000만원');
    const chartSection = rootElement.querySelector<HTMLElement>(
      '[aria-label="거래가 차트"]',
    );
    const chartPoints = Array.from(
      rootElement.querySelectorAll<HTMLElement>('[data-chart-point]'),
    );

    expect(chartSection).not.toBeNull();
    expect(chartSection?.textContent).toContain('125,000만원');
    expect(chartPoints.map((point) => point.dataset.chartDate)).toEqual([
      '2025-10-15',
      '2025-12-01',
    ]);
    expect(
      Array.from(rootElement.querySelectorAll('[data-trade-cell="amount"]')).map((cell) =>
        cell.textContent,
      ),
    ).toEqual(['125,000만원', '118,000만원']);

    unmount(root);
  });

  it('Kakao CustomOverlay complex marker에서 detail drawer를 연다', async () => {
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
          trades: [],
        }),
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

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(sdk.overlays[0]?.content.getAttribute('aria-label')).toBe(
      '필지 1001 단지 501 상세 열기',
    );

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
    const emptyChartSection = rootElement.querySelector<HTMLElement>(
      '[aria-label="거래가 차트"]',
    );

    expect(emptyChartSection).not.toBeNull();
    expect(emptyChartSection?.textContent).toContain('표시할 거래가 없습니다');

    unmount(root);
  });

  it('documented URL로 complex를 search하고 선택한 parcel detail을 연다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]),
      )
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Sample address',
          tradeName: 'Sample trade name',
          name: 'Sample complex name',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          trades: [],
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    const searchButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="단지 검색 실행"]',
    );
    const searchForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="단지 검색"]',
    );
    expect(searchInput).not.toBeNull();
    expect(searchButton).not.toBeNull();
    expect(searchForm).not.toBeNull();

    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Sample';
      }
      submitForm(searchForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );

    const searchResult = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 결과 선택 Sample Apartment"]',
    );
    expect(searchResult).not.toBeNull();

    await act(async () => {
      searchResult?.click();
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
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/trade/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"swLat":37.5023'),
      }),
    );
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');

    unmount(root);
  });

  it('좌표 대기 search result도 complexId scope를 유지하고 detail/trade drawer를 연다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 801,
            complexName: 'Coordinate Pending Complex',
            parcelId: 3001,
            latitude: null,
            longitude: null,
            address: 'Coordinate pending address',
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 3001,
          complexId: 801,
          latitude: null,
          longitude: null,
          address: 'Coordinate pending address',
          tradeName: 'Coordinate Pending Trade',
          name: 'Coordinate Pending Complex',
        }),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 3001,
          complexId: 801,
          trades: [],
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    const searchForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="단지 검색"]',
    );

    await act(async () => {
      if (searchInput) {
        searchInput.value = 'pending';
      }
      submitForm(searchForm);
    });
    await flushAsyncState();

    const searchResult = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 결과 선택 Coordinate Pending Complex"]',
    );
    expect(searchResult).not.toBeNull();

    await act(async () => {
      searchResult?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/3001?complexId=801'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/3001?complexId=801'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Coordinate Pending Complex');
    expect(rootElement.textContent).toContain('거래 내역이 없습니다');

    unmount(root);
  });

  it('region navigation을 load하고 선택한 region detail로 map context를 refresh한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          id: 1,
          name: 'Seoul',
          latitude: 37.5663,
          longitude: 126.978,
          children: [
            {
              id: 11,
              name: 'Gangnam-gu',
            },
          ],
        }),
      )
      .mockResolvedValueOnce(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const loadRegionsButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="상위 지역 불러오기"]',
    );
    expect(loadRegionsButton).not.toBeNull();

    await act(async () => {
      loadRegionsButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region'),
      expect.objectContaining({ method: 'GET' }),
    );

    const regionButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 열기 Seoul"]',
    );
    expect(regionButton).not.toBeNull();

    await act(async () => {
      regionButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );
    expect(rootElement.textContent).toContain('Gangnam-gu');

    unmount(root);
  });

  it('filter control을 documented complex marker request field에 적용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    setInputValue(rootElement, 'input[aria-label="최소 평형"]', '20');
    setInputValue(rootElement, 'input[aria-label="최대 평형"]', '34');
    setInputValue(rootElement, 'input[aria-label="최소 가격 억"]', '8.5');
    setInputValue(rootElement, 'input[aria-label="최대 가격 억"]', '15');
    setInputValue(rootElement, 'input[aria-label="최소 연식"]', '5');
    setInputValue(rootElement, 'input[aria-label="최대 연식"]', '25');
    setInputValue(rootElement, 'input[aria-label="최소 세대수"]', '300');
    setInputValue(rootElement, 'input[aria-label="최대 세대수"]', '1200');

    const applyButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="마커 필터 적용"]',
    );
    const filterForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="마커 필터"]',
    );
    expect(applyButton).not.toBeNull();
    expect(filterForm).not.toBeNull();

    await act(async () => {
      submitForm(filterForm);
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.45,
          swLng: 126.85,
          neLat: 37.7,
          neLng: 127.2,
          pyeongMin: 20,
          pyeongMax: 34,
          priceEokMin: 8.5,
          priceEokMax: 15,
          ageMin: 5,
          ageMax: 25,
          unitMin: 300,
          unitMax: 1200,
        }),
      }),
    );

    unmount(root);
  });

  it('Kakao map surface에서 same-viewport retry로 marker error를 복구한다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(errorResponse(500))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            parcelId: 1001,
            name: 'Sample Apartment',
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
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

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      '마커 데이터를 불러오지 못했습니다',
    );

    const retryButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="마커 다시 불러오기"]',
    );
    expect(retryButton).not.toBeNull();

    await act(async () => {
      retryButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
      }),
    );
    expect(rootElement.querySelector('[role="alert"]')).toBeNull();
    expect(rootElement.querySelector('[data-marker-id="1001"]')).not.toBeNull();

    unmount(root);
  });

  it('SDK가 이미 있으면 Kakao map runtime bounds와 level을 사용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.1,
        swLng: 126.7,
        neLat: 37.8,
        neLng: 127.3,
      },
      level: 10,
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.1,
          swLng: 126.7,
          neLat: 37.8,
          neLng: 127.3,
          region: 'si-do',
        }),
      }),
    );

    unmount(root);
  });

  it('Kakao idle이 새 viewport를 보고하면 marker request를 refresh한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
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

    const { root } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    sdk.setViewport({
      bounds: {
        swLat: 37.2,
        swLng: 126.8,
        neLat: 37.9,
        neLng: 127.4,
      },
      level: 7,
    });

    await act(async () => {
      sdk.triggerIdle();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.2,
          swLng: 126.8,
          neLat: 37.9,
          neLng: 127.4,
          region: 'si-gun-gu',
        }),
      }),
    );

    unmount(root);
  });

  it('key가 없으면 map surface를 유지하고 non-blocking Kakao runtime error를 보고한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      '카카오 지도를 불러오지 못했습니다',
    );

    unmount(root);
  });

  it('Kakao CustomOverlay marker를 rendering하고 unmount 시 clear한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 4,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
          {
            parcelId: 1001,
            name: 'Sample Apartment',
            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 740,
          },
        ]),
      ),
    );
    vi.stubGlobal('kakao', sdk.kakao);

    const { root } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(sdk.overlays).toHaveLength(1);
    expect(sdk.overlays[0]?.setMap).toHaveBeenLastCalledWith(sdk.map);

    unmount(root);

    expect(sdk.overlays[0]?.setMap).toHaveBeenLastCalledWith(null);
  });

  it('Kakao CustomOverlay marker는 latest deal amount와 pending unit metadata를 읽기 쉽게 표시한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 4,
    });
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse([
	          {
	            parcelId: 1001,
	            name: 'Sample Apartment',
	            lat: 37.5123,
            lng: 127.0456,
            latestDealAmount: 125000,
            unitCntSum: 0,
          },
        ]),
      ),
    );
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(sdk.overlays[0]?.content.textContent).toContain('최근 실거래');
    expect(sdk.overlays[0]?.content.textContent).toContain('12.5억');
    expect(sdk.overlays[0]?.content.textContent).toContain('Sample Apartment');
    expect(rootElement.querySelector('[data-marker-id="1001"]')?.textContent).toContain(
      'Sample Apartment',
    );
    expect(rootElement.textContent).not.toContain('세대 정보 없음');
    expect(rootElement.textContent).not.toContain('0 units');

    unmount(root);
  });

  it('Kakao SDK script가 resolve될 때까지 loading map runtime status를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: 'test-app-key' });
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );

    expect(script).not.toBeNull();
    expect(rootElement.textContent).toContain('지도 준비 중');
    expect(rootElement.textContent).not.toContain('지도 준비 완료');

    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 4,
    });
    vi.stubGlobal('kakao', sdk.kakao);

    await act(async () => {
      script?.onload?.call(script, new Event('load'));
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('지도 준비 완료');
    expect(
      rootElement
        .querySelector('[aria-label="카카오 지도 화면"]')
        ?.getAttribute('data-kakao-map-state'),
    ).toBe('ready');

    unmount(root);
    script?.remove();
  });
});

type TestAppProps = Parameters<typeof App>[0];

async function renderApp(props?: TestAppProps): Promise<{ root: Root; rootElement: HTMLDivElement }> {
  const rootElement = document.createElement('div');
  const root = createRoot(rootElement);

  await act(async () => {
    root.render(<App {...props} />);
  });

  return { root, rootElement };
}

async function flushAsyncState(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => {
      setTimeout(resolve, 0);
    });
  });
}

async function flushLazyRoute(): Promise<void> {
  await flushAsyncState();
  await flushAsyncState();
}

function unmount(root: Root): void {
  act(() => {
    root.unmount();
  });
}

function setInputValue(rootElement: HTMLElement, selector: string, value: string): void {
  const input = rootElement.querySelector<HTMLInputElement>(selector);
  expect(input).not.toBeNull();

  if (input) {
    input.value = value;
  }
}

function submitForm(form: HTMLFormElement | null): void {
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}

function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}

function errorResponse(status: number): Response {
  return {
    ok: false,
    status,
  } as Response;
}

function coordinatePendingFixture(
  id: number,
  pnu: string,
  aptName: string,
  reason: string,
): Record<string, unknown> {
  return {
    parcelId: id,
    complexId: id + 500,
    pnu,
    aptSeq: `APT-${id}`,
    aptName,
    address: `${aptName} address`,
    reason,
    tradeCount: 3,
    createdAt: '2026-06-03T00:00:00Z',
  };
}

function coordinatePendingSummaryFixture(overrides: {
  totalCount?: number;
  pnuCoordinateMissing?: number;
  samePnuMultiComplex?: number;
  complexDisplayCoordinateMissing?: number;
} = {}): Record<string, unknown> {
  return {
    totalCount: overrides.totalCount ?? 1429,
    reasonCounts: {
      PNU_COORDINATE_MISSING: overrides.pnuCoordinateMissing ?? 321,
      SAME_PNU_MULTI_COMPLEX: overrides.samePnuMultiComplex ?? 1001,
      COMPLEX_DISPLAY_COORDINATE_MISSING: overrides.complexDisplayCoordinateMissing ?? 107,
    },
  };
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
  reject: (error: unknown) => void;
} {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });

  return { promise, resolve, reject };
}

type FakeBounds = {
  swLat: number;
  swLng: number;
  neLat: number;
  neLng: number;
};

type FakeOverlay = {
  content: HTMLElement;
  setMap: ReturnType<typeof vi.fn>;
};

function createFakeKakaoSdk(options: { bounds: FakeBounds; level: number }) {
  const overlays: FakeOverlay[] = [];
  let bounds = options.bounds;
  let level = options.level;
  const idleHandlers: Array<() => void> = [];
  const map = {
    getBounds: () => ({
      getSouthWest: () => latLng(bounds.swLat, bounds.swLng),
      getNorthEast: () => latLng(bounds.neLat, bounds.neLng),
    }),
    getLevel: () => level,
    setLevel: vi.fn((nextLevel: number) => {
      level = nextLevel;
    }),
  };
  const kakao = {
    maps: {
      LatLng: vi.fn(function (this: unknown, lat: number, lng: number) {
        void this;
        return latLng(lat, lng);
      }),
      Map: vi.fn(function (this: unknown) {
        void this;
        return map;
      }),
      CustomOverlay: vi.fn(function (this: unknown, options: { content: HTMLElement }) {
        void this;
        const overlay = { content: options.content, setMap: vi.fn() };
        overlays.push(overlay);
        return overlay;
      }),
      event: {
        addListener: vi.fn((_target: unknown, eventName: string, handler: () => void) => {
          if (eventName === 'idle') {
            idleHandlers.push(handler);
          }
        }),
        removeListener: vi.fn(),
      },
    },
  };

  return {
    kakao,
    map,
    overlays,
    setViewport(nextViewport: { bounds: FakeBounds; level: number }) {
      bounds = nextViewport.bounds;
      level = nextViewport.level;
    },
    triggerIdle() {
      idleHandlers.forEach((handler) => handler());
    },
  };
}

function latLng(lat: number, lng: number) {
  return {
    getLat: () => lat,
    getLng: () => lng,
  };
}
