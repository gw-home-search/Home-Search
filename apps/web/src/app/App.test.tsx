import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../features/map/api/resolveApiUrl';
import type { AuthClient } from '../features/auth/api/authClient';
import { App } from './App';

describe('App map-first shell 화면', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
    window.sessionStorage.clear();
    window.history.pushState({}, '', '/');
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
  });

  it('desktop exploration rail은 항상 표시하고 운영 상태·toggle 문구를 제거한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    const mapWorkspace = rootElement.querySelector('[data-layout-region="map-workspace"]');
    const mapSurface = rootElement.querySelector<HTMLElement>('[aria-label="지도 화면"]');
    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const filterPanel = rootElement.querySelector<HTMLElement>('form[aria-label="마커 필터"]');
    const markerAlert = Array.from(rootElement.querySelectorAll('[role="alert"]')).find((alert) =>
      alert.textContent?.includes('단지 정보를 불러오지 못했어요'),
    );

    expect(mapWorkspace).not.toBeNull();
    expect(mapSurface).not.toBeNull();
    expect(explorationPanel).not.toBeNull();
    expect(
      (explorationPanel?.compareDocumentPosition(mapSurface as Node) ?? 0)
        & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    expect(filterPanel?.getAttribute('data-map-overlay')).toBe('filters');
    expect(filterPanel?.closest('header')).toBeNull();
    expect(filterPanel?.closest('[data-layout-region="map-column"]')).not.toBeNull();
    expect(markerAlert?.closest('[aria-label="지도 화면"]')).toBe(mapSurface);
    expect(rootElement.querySelector('button[aria-label="탐색 패널 접기"]')).toBeNull();
    expect(explorationPanel?.hasAttribute('hidden')).toBe(false);
    expect(rootElement.textContent).not.toContain('지역 보기');
    expect(rootElement.textContent).not.toContain('단지 보기');
    expect(rootElement.textContent).not.toContain('상세 미선택');
    expect(rootElement.textContent).not.toContain('마커 오류');
    expect(rootElement.textContent).not.toContain('지도 준비 완료');
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();

    unmount(root);
  });

  it('auth service 장애가 public map marker 요청과 지도 사용을 막지 않는다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);
    const unavailableClient: AuthClient = {
      authenticatedRequest: async () => { throw new Error('Authentication required'); },
      authorizationUrl: () => { throw new Error('unavailable'); },
      logout: async () => { throw new Error('unavailable'); },
      restoreSession: async () => ({ kind: 'unavailable' }),
    };

    const { root, rootElement } = await renderApp({ authClient: unavailableClient });
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('.account-login-button')?.textContent).toBe('로그인');
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({ method: 'POST' }),
    );

    unmount(root);
  });

  it('mobile 검색 action은 exploration sheet를 열고 닫은 뒤 focus를 복귀한다', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp();
    document.body.append(rootElement);
    const searchButton = rootElement.querySelector<HTMLButtonElement>('button[aria-label="검색 패널 열기"]');
    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    expect(explorationPanel?.hasAttribute('hidden')).toBe(true);

    await act(async () => searchButton?.click());
    expect(explorationPanel?.hasAttribute('hidden')).toBe(false);
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-exploration-open')).toBe('true');
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-sidebar-mode')).toBe('region');
    expect(document.activeElement).toBe(rootElement.querySelector('input[aria-label="단지 검색"]'));

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="검색 패널 닫기"]')?.click();
    });
    await act(async () => Promise.resolve());

    expect(explorationPanel?.hasAttribute('hidden')).toBe(true);
    expect(rootElement.querySelector('[data-layout-region="map-workspace"]')?.getAttribute('data-exploration-open')).toBe('false');
    expect(document.activeElement).toBe(searchButton);

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
    await act(async () => window.dispatchEvent(new Event('resize')));
    expect(explorationPanel?.hasAttribute('hidden')).toBe(false);
    unmount(root);
    rootElement.remove();
  });

  it('public map UI는 map-first design landmarks와 active filter state를 고정한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    expect(rootElement.querySelector('[data-ui-surface="map-first"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="filter-controls"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="exploration-panel"]')).not.toBeNull();
    expect(rootElement.querySelector('.filter-status')).toBeNull();

    await applyFilterRange(rootElement, '평형', '20', '');
    await applyFilterRange(rootElement, '가격', '', '15');
    await applyFilterRange(rootElement, '세대수', '300', '');

    const filterPanel = rootElement.querySelector<HTMLElement>(
      '[data-ui-layer="filter-controls"]',
    );
    expect(filterPanel?.dataset.filterState).toBe('active');
    expect(filterPanel?.textContent).not.toContain('필터 3개 적용');
    expect(filterPanel?.textContent).toContain('20평 이상');
    expect(filterPanel?.textContent).toContain('15억 이하');
    expect(filterPanel?.textContent).toContain('300세대 이상');

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
    expect(resetFilterPanel?.querySelector('.filter-status')).toBeNull();
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

  it('초기 탐색 패널은 검색 입력과 지역 내비게이션을 기본으로 연다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const regionPanel = rootElement.querySelector<HTMLElement>('#exploration-panel-region');

    expect(rootElement.querySelector('[role="tablist"]')).toBeNull();
    expect(explorationPanel?.getAttribute('aria-label')).toBe('탐색 패널');
    expect(
      Array.from(explorationPanel?.querySelectorAll('p') ?? [])
        .some((element) => element.textContent?.trim() === '탐색'),
    ).toBe(false);
    expect(regionPanel?.getAttribute('aria-label')).toBe('지역 탐색 패널');
    expect(
      Array.from(regionPanel?.querySelectorAll('p') ?? [])
        .some((element) => element.textContent?.trim() === '지역'),
    ).toBe(false);
    expect(rootElement.querySelector('input[aria-label="단지 검색"]')).not.toBeNull();
    expect(rootElement.querySelector('#exploration-panel-region')?.hasAttribute('hidden')).toBe(false);

    unmount(root);
  });

  it('단지 검색 입력은 debounce 후 검색 목록 모드로 전환한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/search/complexes/suggestions?q=Sample')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/search/complexes?q=Sample')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 501,
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Sample';
        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await waitForMillis(350);
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes?q=Sample'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector<HTMLElement>('#exploration-panel')?.dataset.sidebarMode).toBe(
      'search',
    );
    expect(rootElement.querySelector('#exploration-panel-search')?.hasAttribute('hidden')).toBe(
      false,
    );
    expect(rootElement.querySelector('#exploration-panel-region')?.hasAttribute('hidden')).toBe(
      true,
    );
    expect(
      rootElement.querySelector('button[aria-label="검색 결과 선택 Sample Apartment"]'),
    ).not.toBeNull();

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

  it('map surface를 block하지 않고 marker loading state를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));

    const { root, rootElement } = await renderApp();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    await waitForMillis(151);
    expect(rootElement.textContent).toContain('이 지역의 단지를 불러오는 중');

    unmount(root);
  });

  it('marker API가 empty list를 반환하면 empty marker state를 표시한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: expect.stringContaining('"region":"si-do"'),
      }),
    );
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('이 지도 영역에는 표시할 단지가 없습니다');

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

  it('지도 형식 control은 일반·지형·위성을 Kakao runtime에 적용한다', async () => {
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

    const mapTypeToggle = rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 형식 선택"]');
    expect(mapTypeToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(mapTypeToggle?.textContent).toBe('지도');
    expect(rootElement.querySelector('[aria-label="지도 형식 메뉴"]')).toBeNull();
    expect(sdk.map.setMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.ROADMAP);

    await act(async () => mapTypeToggle?.click());
    expect(mapTypeToggle?.getAttribute('aria-expanded')).toBe('true');

    await act(async () => mapTypeToggle?.click());
    expect(mapTypeToggle?.getAttribute('aria-expanded')).toBe('false');

    await act(async () => mapTypeToggle?.click());

    const terrainButton = rootElement.querySelector<HTMLButtonElement>('button[aria-label="지형 지도"]');
    await act(async () => terrainButton?.click());
    expect(sdk.map.removeOverlayMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.TERRAIN);
    expect(sdk.map.setMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.ROADMAP);
    expect(sdk.map.addOverlayMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.TERRAIN);
    expect(mapTypeToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(mapTypeToggle?.textContent).toBe('지형');

    await act(async () => mapTypeToggle?.click());
    const hybridButton = rootElement.querySelector<HTMLButtonElement>('button[aria-label="위성 지도"]');
    await act(async () => hybridButton?.click());
    expect(sdk.map.removeOverlayMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.TERRAIN);
    expect(sdk.map.setMapTypeId).toHaveBeenLastCalledWith(sdk.kakao.maps.MapTypeId.HYBRID);
    expect(mapTypeToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(mapTypeToggle?.textContent).toBe('위성');

    unmount(root);
  });

  it('지도 도구에서 거리뷰를 열면 기존 지도 영역을 전체 거리뷰로 교체하고 복귀한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리뷰 사용"]')?.click());
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')?.getAttribute('data-map-tool')).toBe('roadview');
    expect(rootElement.querySelector('[aria-label="거리뷰 패널"]')).not.toBeNull();
    expect(rootElement.querySelector('[aria-label="카카오 지도 화면"]')?.hasAttribute('hidden')).toBe(true);
    expect(rootElement.querySelector('[data-ui-layer="map-control-rail"]')).toBeNull();
    expect(rootElement.querySelector('button[aria-label="거리뷰 뒤로가기"]')).not.toBeNull();
    expect(sdk.map.addOverlayMapTypeId).toHaveBeenCalledWith(sdk.kakao.maps.MapTypeId.ROADVIEW);
    expect(sdk.roadviewClient.getNearestPanoId).toHaveBeenCalledWith(expect.anything(), 100, expect.any(Function));

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리뷰 닫기"]')?.click());
    expect(rootElement.querySelector('[aria-label="거리뷰 패널"]')).toBeNull();
    expect(rootElement.querySelector('[aria-label="카카오 지도 화면"]')?.hasAttribute('hidden')).toBe(false);
    expect(rootElement.querySelector('[data-ui-layer="map-control-rail"]')).not.toBeNull();
    expect(sdk.map.removeOverlayMapTypeId).toHaveBeenCalledWith(sdk.kakao.maps.MapTypeId.ROADVIEW);

    unmount(root);
  });

  it('가까운 panoId가 없으면 지도는 유지하고 거리뷰 부재 상태를 안내한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    sdk.setRoadviewPanoId(null);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리뷰 사용"]')?.click());
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="카카오 지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('[aria-label="거리뷰 패널"]')?.textContent).toContain('이 위치 주변에는 거리뷰가 없습니다');
    expect(rootElement.querySelector('[aria-label="거리뷰 패널"]')?.getAttribute('data-roadview-state')).toBe('unavailable');

    unmount(root);
  });

  it('거리뷰 위치를 연속 선택하면 가장 최근 panoId 응답만 적용한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    sdk.setRoadviewAutoResolve(false);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리뷰 사용"]')?.click());
    await flushAsyncState();
    await act(async () => sdk.triggerMapClick(37.51, 127.01));

    await act(async () => sdk.resolveRoadviewRequest(1, 202));
    await act(async () => sdk.resolveRoadviewRequest(0, 101));

    expect(sdk.roadview.setPanoId).toHaveBeenCalledTimes(1);
    expect(sdk.roadview.setPanoId).toHaveBeenLastCalledWith(202, expect.anything());

    unmount(root);
  });

  it('지적편집도는 지도 형식과 독립적으로 toggle되고 참고 안내를 표시한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지적편집도 표시"]')?.click());

    expect(rootElement.querySelector('[aria-label="지도 화면"]')?.getAttribute('data-cadastral-visible')).toBe('true');
    expect(rootElement.textContent).toContain('지적편집도는 참고용이며 실제 지적 정보와 다를 수 있습니다.');
    expect(sdk.map.addOverlayMapTypeId).toHaveBeenCalledWith(sdk.kakao.maps.MapTypeId.USE_DISTRICT);

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 형식 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="위성 지도"]')?.click());
    expect(rootElement.querySelector('[aria-label="지도 화면"]')?.getAttribute('data-cadastral-visible')).toBe('true');

    unmount(root);
  });

  it('거리 측정은 지도 click을 다중 지점 경로와 누적 거리로 표시하고 종료 시 정리한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리 측정 사용"]')?.click());
    await act(async () => {
      sdk.triggerMapClick(37.5, 127);
      sdk.triggerMapClick(37.51, 127.01);
    });
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="거리 측정 도구"]')?.textContent).toContain('1.25km');
    expect(sdk.polyline.setPath).toHaveBeenLastCalledWith(expect.arrayContaining([expect.anything(), expect.anything()]));

    await act(async () => rootElement.querySelector<HTMLButtonElement>('[aria-label="거리 측정 도구"] button:nth-of-type(3)')?.click());
    expect(rootElement.querySelector('[aria-label="거리 측정 도구"]')?.getAttribute('data-distance-phase')).toBe('complete');
    const pathUpdateCount = sdk.polyline.setPath.mock.calls.length;
    await act(async () => sdk.triggerMapClick(37.52, 127.02));
    expect(sdk.polyline.setPath).toHaveBeenCalledTimes(pathUpdateCount);

    await act(async () => rootElement.querySelector<HTMLButtonElement>('[aria-label="거리 측정 도구"] button:last-child')?.click());
    expect(rootElement.querySelector('[aria-label="거리 측정 도구"]')).toBeNull();
    expect(sdk.polyline.setMap).toHaveBeenCalledWith(null);

    unmount(root);
  });

  it('주변시설은 단지 선택 없이 0~3종을 선택하고 현재 viewport에서 자동 조회한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
      level: 4,
    });
    const fetchMock = vi.fn((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/api/v1/map/complexes')) return Promise.resolve(jsonResponse([{
        parcelId: 1001, complexId: 501, name: 'Sample Apartment', lat: 37.5123, lng: 127.0456,
        latestDealAmount: 125000, unitCntSum: 740,
      }]));
      if (url.includes('/api/v1/detail/1001/complexes')) return Promise.resolve(jsonResponse([]));
      if (url.includes('/api/v1/detail/1001')) return Promise.resolve(jsonResponse({
        parcelId: 1001, complexId: 501, latitude: 37.5123, longitude: 127.0456,
        address: 'Sample address', tradeName: 'Sample', name: 'Sample Apartment',
      }));
      if (url.includes('/api/v1/trade/1001/trend')) return Promise.resolve(jsonResponse([]));
      if (url.includes('/api/v1/trade/1001')) return Promise.resolve(jsonResponse({
        parcelId: 1001, complexId: 501, content: [], page: 0, size: 25,
        totalElements: 0, totalPages: 0,
      }));
      if (url.includes('/api/v1/map/nearby-places')) {
        const category = JSON.parse(String(_init?.body)).category as 'SUPERMARKET' | 'HOSPITAL' | 'SCHOOL';
        const label = { SUPERMARKET: '대형마트', HOSPITAL: '병원', SCHOOL: '학교' }[category];
        return Promise.resolve(jsonResponse({
          bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
          level: 4,
          source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
          generatedAt: '2026-07-13T03:00:01Z',
          category: {
            category, label,
            retrievedAt: '2026-07-13T03:00:00Z',
            places: Array.from({ length: 6 }, (_, index) => ({
              placeId: `kakao:${category}:${index}`,
              name: category === 'SUPERMARKET' && index === 0 ? '가까운 대형마트' : `${label} ${index + 1}`,
              categoryDetail: label,
              lat: 37.48 + index * 0.001,
              lng: 126.89 + index * 0.001,
              distanceMeters: 72 + index,
              address: 'Sample address',
              roadAddress: null,
              phone: null,
              placeUrl: `https://place.map.kakao.com/${category}-${index}`,
            })),
          },
        }));
      }
      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/map/nearby-places'))).toHaveLength(0);
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="대형마트 선택"]')?.click());
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 450)));
    await flushAsyncState();

    const nearbyCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/map/nearby-places'));
    expect(nearbyCalls).toHaveLength(1);
    expect(nearbyCalls[0]?.[1]).toEqual(expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('"category":"SUPERMARKET"'),
    }));
    expect(rootElement.querySelector('[aria-label="주변시설 종류 선택"]')).not.toBeNull();
    const poi = sdk.overlays.find((overlay) => overlay.content.classList.contains('nearby-place-marker'));
    expect(poi?.content.getAttribute('aria-label')).toBe('가까운 대형마트');
    expect(poi?.content.querySelector('svg')).not.toBeNull();
    expect(poi).toMatchObject({ yAnchor: 1, zIndex: 2 });

    await act(async () => poi?.content.click());
    await flushAsyncState();
    expect(rootElement.querySelector('[aria-label="선택 장소 정보"]')?.textContent).toContain('가까운 대형마트');
    expect(sdk.map.setCenter).toHaveBeenLastCalledWith(expect.objectContaining({ getLat: expect.any(Function) }));
    const selectedPoi = [...sdk.overlays].reverse().find((overlay) =>
      overlay.content.classList.contains('nearby-place-marker')
      && overlay.content.dataset.selected === 'true');
    expect(selectedPoi).toMatchObject({ yAnchor: 1, zIndex: 3 });

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="병원 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="학교 선택"]')?.click());
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 450)));
    await flushAsyncState();

    const multiCategoryCalls = fetchMock.mock.calls
      .filter(([input]) => String(input).includes('/api/v1/map/nearby-places'));
    expect(multiCategoryCalls).toHaveLength(3);
    expect(multiCategoryCalls.map((call) => JSON.parse(String(call[1]?.body)).category))
      .toEqual(['SUPERMARKET', 'SCHOOL', 'HOSPITAL']);
    const activePoiOverlays = sdk.overlays.filter((overlay) => (
      overlay.content.classList.contains('nearby-place-marker')
      && overlay.setMap.mock.calls.at(-1)?.[0] === sdk.map
    ));
    expect(activePoiOverlays).toHaveLength(15);
    expect(activePoiOverlays.filter((overlay) => overlay.content.dataset.category === 'SUPERMARKET')).toHaveLength(5);
    expect(activePoiOverlays.filter((overlay) => overlay.content.dataset.category === 'HOSPITAL')).toHaveLength(5);
    expect(activePoiOverlays.filter((overlay) => overlay.content.dataset.category === 'SCHOOL')).toHaveLength(5);

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]')?.click());
    expect(rootElement.querySelector('[aria-label="주변시설 종류 선택"]')).toBeNull();
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="거리 측정 사용"]')?.click());
    expect(rootElement.querySelector('.map-surface')?.getAttribute('data-map-tool')).toBe('distance');
    expect(rootElement.querySelector('.map-surface')?.getAttribute('data-facilities-active')).toBe('true');

    sdk.setViewport({
      bounds: { swLat: 37.46, swLng: 126.86, neLat: 37.51, neLng: 126.94 },
      level: 4,
    });
    await act(async () => sdk.triggerIdle());
    await act(async () => new Promise((resolve) => window.setTimeout(resolve, 450)));
    await flushAsyncState();
    expect(fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/v1/map/nearby-places'))).toHaveLength(6);

    unmount(root);
  });

  it('전국 overview level에서 zoom-out을 막고 Kakao runtime 최고 level을 제한한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 33,
        swLng: 124,
        neLat: 39,
        neLng: 132,
      },
      level: 12,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 12 });
    await flushAsyncState();

    expect(sdk.map.setMaxLevel).toHaveBeenCalledWith(12);
    expect(rootElement.querySelector<HTMLElement>('[aria-label="지도 화면"]')?.dataset.mapLevel).toBe('12');
    expect(rootElement.querySelector<HTMLButtonElement>('button[aria-label="지도 축소"]')?.disabled).toBe(true);

    unmount(root);
  });

  it('새로고침 기본 지도는 전국 중심과 overview level로 시작한다', async () => {
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 33,
        swLng: 124,
        neLat: 39,
        neLng: 132,
      },
      level: 12,
    });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root } = await renderApp();
    await flushAsyncState();

    expect(sdk.kakao.maps.LatLng).toHaveBeenCalledWith(36.35, 127.8);
    expect(sdk.kakao.maps.Map).toHaveBeenCalledWith(
      expect.any(HTMLDivElement),
      expect.objectContaining({ level: 12 }),
    );

    unmount(root);
  });

  it('map surface를 제거하지 않고 non-blocking marker error를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      '단지 정보를 불러오지 못했어요',
    );
    expect(rootElement.textContent).toContain('지도 이동과 확대·축소는 계속 사용할 수 있습니다');
    expect(rootElement.querySelectorAll('[data-marker-id]')).toHaveLength(0);

    unmount(root);
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
            complexName: 'Sample Apartment',
            parcelId: 1001,
            latitude: 37.5123,
            longitude: 127.0456,
            address: 'Sample address',
          },
        ]),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
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
    expect(searchResult?.classList.contains('complex-list-row')).toBe(true);
    expect(searchResult?.closest('[data-ui-component="complex-list"]')).not.toBeNull();
    expect(searchResult?.querySelector('.complex-list-name')?.textContent).toBe('Sample Apartment');
    expect(searchResult?.querySelector('.complex-list-address')?.textContent).toBe('Sample address');

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
      resolveApiUrl('/api/v1/detail/1001/complexes'),
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

  it('좌표 대기 search result도 complexId scope를 유지하고 detail/trade sidebar를 연다', async () => {
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
            complexId: 801,
            complexName: 'Coordinate Pending Complex',
            parcelId: 3001,
            latitude: null,
            longitude: null,
            address: 'Coordinate pending address',
          },
        ]),
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
    expect(fetchMock).toHaveBeenCalledTimes(6);
    expect(rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Coordinate Pending Complex');
    expect(rootElement.textContent).toContain('거래 내역이 없습니다');

    unmount(root);
  });

  it('children이 있는 시도·시군구 단계에서는 단지 목록을 요청하거나 표시하지 않는다', async () => {
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
      'button[aria-label="지역 처음으로"]',
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
      'button[aria-label="지역 이동 Seoul"]',
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
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );
    expect(rootElement.textContent).toContain('Gangnam-gu');
    expect(rootElement.textContent).not.toContain('Region Complex');

    unmount(root);
  });

  it('탐색 패널은 지역 목록을 바로 불러오고 단계적으로 drill-down한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);

      if (requestUrl === resolveApiUrl('/api/v1/map/complexes')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region')) {
        return Promise.resolve(jsonResponse([{ id: 1, name: 'Seoul' }]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({
          id: 1,
          name: 'Seoul',
          latitude: 37.5663,
          longitude: 126.978,
          children: [{ id: 11, name: 'Gangnam-gu' }],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/11')) {
        return Promise.resolve(jsonResponse({
          id: 11,
          name: 'Gangnam-gu',
          latitude: 37.5172,
          longitude: 127.0473,
          children: [{ id: 111, name: 'Apgujeong-dong' }],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/11/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([]));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/111')) {
        return Promise.resolve(jsonResponse({
          id: 111,
          name: 'Apgujeong-dong',
          latitude: 37.5271,
          longitude: 127.0287,
          children: [],
        }));
      }

      if (requestUrl === resolveApiUrl('/api/v1/region/111/complexes?limit=20&offset=0')) {
        return Promise.resolve(jsonResponse([
          {
            complexId: 901,
            complexName: 'Apgujeong Region Complex',
            parcelId: 3001,
            latitude: 37.5271,
            longitude: 127.0287,
            address: 'Apgujeong address',
            unitCnt: 810,
            dongCnt: 12,
            useDate: '2018-04-20',
          },
        ]));
      }

      return Promise.resolve(errorResponse(404));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialRegionLoad: true });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[role="tablist"]')).toBeNull();
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('시도 선택');
    expect(rootElement.querySelector('button[aria-label="지역 처음으로"]')?.getAttribute('aria-current')).toBe('page');
    expect(rootElement.querySelector('.region-step-summary')).toBeNull();

    const sidoButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Seoul"]',
    );
    await act(async () => {
      sidoButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Seoul');
    expect(rootElement.querySelector('button[aria-label="지역 처음으로"]')?.hasAttribute('aria-current')).toBe(false);
    expect(rootElement.textContent).not.toContain('시군구 선택');
    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Seoul"]')).not.toBeNull();
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/1/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );

    const sigunguButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Gangnam-gu"]',
    );
    await act(async () => {
      sigunguButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Gangnam-gu');
    expect(rootElement.textContent).not.toContain('읍면동 선택');
    expect(fetchMock).not.toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/11/complexes?limit=20&offset=0'),
      expect.anything(),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"eup-myeon-dong"'),
      }),
    );

    const emdButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 이동 Apgujeong-dong"]',
    );
    await act(async () => {
      emdButton?.click();
    });
    await flushAsyncState();

    expect(rootElement.textContent).toContain('Apgujeong Region Complex');
    const regionComplexCard = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="지역 단지 선택 Apgujeong Region Complex"]',
    );
    expect(regionComplexCard?.classList.contains('complex-list-row')).toBe(true);
    expect(regionComplexCard?.closest('[data-ui-component="complex-list"]')).not.toBeNull();
    expect(regionComplexCard?.querySelector('.complex-list-name')?.textContent).toBe('Apgujeong Region Complex');
    expect(regionComplexCard?.querySelector('.complex-list-address')?.textContent).toBe('Apgujeong address');
    expect(regionComplexCard?.querySelector('.complex-list-context')?.textContent).toContain('2018년 승인');
    expect(regionComplexCard?.querySelector('.complex-list-unit')?.textContent).toBe('810세대');
    expect(regionComplexCard?.querySelector('.complex-list-building')?.textContent).toBe('12동');
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/region/111/complexes?limit=20&offset=0'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({ method: 'POST' }),
    );

    await act(async () => {
      rootElement.querySelector<HTMLButtonElement>('button[aria-label="지역 단계 이동 Seoul"]')?.click();
    });
    await flushAsyncState();

    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Seoul"]')).not.toBeNull();
    expect(rootElement.querySelector('button[aria-label="지역 단계 이동 Gangnam-gu"]')).toBeNull();
    expect(rootElement.textContent).not.toContain('Apgujeong Region Complex');
    expect(rootElement.querySelector('button[aria-label="지역 이동 Gangnam-gu"]')).not.toBeNull();

    expect(rootElement.querySelector('input[aria-label="단지 검색"]')).not.toBeNull();

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

  it('단지 검색 입력은 suggestion API를 사용하고 suggestion 선택으로 detail을 연다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse([]))
      .mockResolvedValueOnce(
        jsonResponse([
          {
            complexId: 501,
            complexName: 'Suggested Apartment',
            parcelId: 1001,
            address: 'Suggestion address',
          },
        ]),
      )
      .mockResolvedValueOnce(
        jsonResponse({
          parcelId: 1001,
          complexId: 501,
          latitude: 37.5123,
          longitude: 127.0456,
          address: 'Suggestion address',
          name: 'Suggested Apartment',
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

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="단지 검색"]',
    );
    await act(async () => {
      if (searchInput) {
        searchInput.value = 'Suggested';
        searchInput.dispatchEvent(new Event('input', { bubbles: true }));
      }
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/search/complexes/suggestions?q=Suggested'),
      expect.objectContaining({ method: 'GET' }),
    );
    const suggestion = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="검색 제안 선택 Suggested Apartment"]',
    );
    expect(suggestion).not.toBeNull();

    await act(async () => {
      suggestion?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001?complexId=501'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.textContent).toContain('Suggested Apartment');

    unmount(root);
  });

  it('filter control을 documented complex marker request field에 적용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    await applyFilterRange(rootElement, '평형', '20', '34');
    await applyFilterRange(rootElement, '가격', '8.5', '15');
    await applyFilterRange(rootElement, '입주년차', '5', '25');
    await applyFilterRange(rootElement, '세대수', '300', '1200');

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

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      '단지 정보를 불러오지 못했어요',
    );

    const retryButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="마커 다시 불러오기"]',
    );
    expect(retryButton).not.toBeNull();

    await act(async () => {
      retryButton?.click();
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
    expect(sdk.overlays[0]?.content.getAttribute('aria-label')).toBe('필지 1001 상세 열기');
    expect(rootElement.querySelector('[aria-label="단지 마커"]')).toBeNull();
    expect(rootElement.querySelector('[data-marker-id="1001"]')).toBeNull();

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
      '지도를 불러오지 못했어요',
    );

    unmount(root);
  });

  it('key가 없어도 대체 지도 위에 지역 marker를 표시하고 바로 다음 단계로 이동한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: 1200,
          },
        ]));
      }
      if (String(url) === resolveApiUrl('/api/v1/region/1')) {
        return Promise.resolve(jsonResponse({ id: 1, name: 'Seoul', latitude: 37.5663, longitude: 126.978, children: [{ id: 11, name: 'Gangnam-gu' }] }));
      }

      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    const fallbackMarkerLayer = rootElement.querySelector('[aria-label="대체 지도 마커"]');
    const fallbackMarker = rootElement.querySelector<HTMLButtonElement>(
      '[data-fallback-marker-id="region-1"]',
    );
    expect(fallbackMarkerLayer).not.toBeNull();
    expect(fallbackMarker?.textContent).toContain('Seoul');
    expect(fallbackMarker?.textContent).toContain('1,200세대');

    await act(async () => {
      fallbackMarker?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(resolveApiUrl('/api/v1/region/1'), { method: 'GET' });
    expect(rootElement.querySelector('[aria-label="지역 단계"]')?.textContent).toContain('Seoul');

    unmount(root);
  });

  it('지역 marker 세대수가 없으면 0세대 대신 세대수 없음으로 표시한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      if (String(url) === resolveApiUrl('/api/v1/map/regions')) {
        return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: null,
          },
        ]));
      }

      return Promise.resolve(jsonResponse([]));
    });
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: '' });
    await flushAsyncState();
    await flushAsyncState();

    const fallbackMarker = rootElement.querySelector<HTMLButtonElement>(
      '[data-fallback-marker-id="region-1"]',
    );
    expect(fallbackMarker?.textContent).toContain('세대수 없음');
    expect(fallbackMarker?.textContent).not.toContain('0세대');

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

  it('Kakao host resize는 center를 보존하며 relayout을 한 번 호출한다', async () => {
    let resizeCallback: ResizeObserverCallback | null = null;
    const observe = vi.fn();
    const disconnect = vi.fn();
    class FakeResizeObserver {
      constructor(callback: ResizeObserverCallback) {
        resizeCallback = callback;
      }
      observe = observe;
      disconnect = disconnect;
      unobserve = vi.fn();
    }
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.7, neLng: 127.2 },
      level: 4,
    });
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const host = rootElement.querySelector<HTMLElement>('[aria-label="카카오 지도 화면"]');
    expect(observe).toHaveBeenCalledWith(host);
    const relayoutCallsBeforeResize = sdk.map.relayout.mock.calls.length;
    act(() => {
      resizeCallback?.([], {} as ResizeObserver);
    });

    expect(sdk.map.relayout).toHaveBeenCalledTimes(relayoutCallsBeforeResize + 1);
    expect(sdk.map.setCenter).toHaveBeenLastCalledWith(sdk.center);

    unmount(root);
    expect(disconnect).toHaveBeenCalledTimes(1);
  });

  it('Kakao CustomOverlay marker는 KOSA price-card에 latest deal amount와 단지명을 표시한다', async () => {
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

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();
    await flushAsyncState();

    expect(sdk.overlays[0]?.content.textContent).toContain('12.5억');
    expect(sdk.overlays[0]?.content.textContent).toContain('Sample Apartment');
    expect(sdk.overlays[0]?.content.classList.contains('map-marker-complex')).toBe(true);
    expect(sdk.overlays[0]?.content.dataset.markerShape).toBe('price-card');
    expect(rootElement.querySelector('[aria-label="단지 마커"]')).toBeNull();
    expect(rootElement.querySelector('[data-marker-id="1001"]')).toBeNull();
    expect(rootElement.textContent).not.toContain('세대 정보 없음');
    expect(rootElement.textContent).not.toContain('0 units');

    unmount(root);
  });

  it('Kakao CustomOverlay region marker 클릭은 바로 다음 지도 단계로 이동한다', async () => {
    const fetchMock = vi.fn((url: RequestInfo | URL) => {
      const requestUrl = String(url);
      if (requestUrl === resolveApiUrl('/api/v1/map/regions')) return Promise.resolve(jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
            unitCntSum: 1200,
          },
        ]));
      if (requestUrl === resolveApiUrl('/api/v1/region/1')) return Promise.resolve(jsonResponse({ id: 1, name: 'Seoul', latitude: 37.5663, longitude: 126.978, children: [{ id: 11, name: 'Gangnam-gu' }] }));
      return Promise.resolve(jsonResponse([]));
    });
    const sdk = createFakeKakaoSdk({
      bounds: {
        swLat: 37.45,
        swLng: 126.85,
        neLat: 37.7,
        neLng: 127.2,
      },
      level: 10,
    });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 10 });
    await flushAsyncState();
    await flushAsyncState();

    const regionOverlayButton = sdk.overlays[0]?.content as HTMLButtonElement | undefined;
    expect(regionOverlayButton).not.toBeNull();
    expect(regionOverlayButton?.getAttribute('aria-label')).toBe('지역 이동 Seoul');
    expect(regionOverlayButton?.dataset.markerDensity).toBe('standard');
    expect(regionOverlayButton?.textContent).toContain('1,200세대');
    expect(rootElement.querySelector('[aria-label="지역 마커"]')).toBeNull();

    await act(async () => {
      regionOverlayButton?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(resolveApiUrl('/api/v1/region/1'), { method: 'GET' });
    expect(rootElement.querySelector('[aria-label="지역 단계"]')?.textContent).toContain('Seoul');
    expect(sdk.map.setCenter).toHaveBeenCalled();
    expect(sdk.map.setLevel).toHaveBeenLastCalledWith(9);
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"region":"si-gun-gu"'),
      }),
    );

    unmount(root);
  });

  it('Kakao SDK runtime ready 상태는 운영 문구 없이 map host state로만 노출한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: 'test-app-key' });
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );

    expect(script).not.toBeNull();
    expect(rootElement.textContent).not.toContain('지도 준비 중');
    expect(rootElement.textContent).not.toContain('지도 준비 완료');
    expect(rootElement.querySelector('[aria-label="카카오 지도 화면"]')?.getAttribute('data-kakao-map-state')).toBe('loading');

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

    expect(rootElement.textContent).not.toContain('지도 준비 완료');
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
    root.render(<App authClient={testAnonymousAuthClient} initialRegionLoad={false} {...props} />);
  });

  return { root, rootElement };
}

const testAnonymousAuthClient: AuthClient = {
  authenticatedRequest: async () => { throw new Error('Authentication required'); },
  authorizationUrl: (provider) => `http://localhost:8082/oauth2/authorization/${provider}`,
  logout: async () => undefined,
  restoreSession: async () => ({ kind: 'anonymous' }),
};

async function flushAsyncState(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => {
      setTimeout(resolve, 0);
    });
  });
}

async function flushLazyRoute(): Promise<void> {
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  await flushAsyncState();
}

async function waitForMillis(ms: number): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => {
      setTimeout(resolve, ms);
    });
  });
}

async function applyFilterRange(
  rootElement: HTMLElement,
  label: '세대수' | '평형' | '가격' | '입주년차',
  min: string,
  max: string,
): Promise<void> {
  const inputLabels = label === '입주년차'
    ? ['최소 연식', '최대 연식']
    : label === '가격'
      ? ['최소 가격 억', '최대 가격 억']
      : [`최소 ${label}`, `최대 ${label}`];

  await act(async () => {
    rootElement.querySelector<HTMLButtonElement>(`button[aria-label="${label} 필터 열기"]`)?.click();
  });
  setInputValue(rootElement, `input[aria-label="${inputLabels[0]}"]`, min);
  setInputValue(rootElement, `input[aria-label="${inputLabels[1]}"]`, max);
  await act(async () => {
    submitForm(rootElement.querySelector<HTMLFormElement>('form[aria-label="마커 필터"]'));
  });
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
    input.dispatchEvent(new Event('input', { bubbles: true }));
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
  yAnchor: number;
  zIndex?: number;
  setMap: ReturnType<typeof vi.fn>;
};

function createFakeKakaoSdk(options: { bounds: FakeBounds; level: number }) {
  const overlays: FakeOverlay[] = [];
  let bounds = options.bounds;
  let level = options.level;
  const idleHandlers: Array<() => void> = [];
  const clickHandlers: Array<(event: { latLng: ReturnType<typeof latLng> }) => void> = [];
  const center = latLng(37.5663, 126.978);
  let polylinePath: ReturnType<typeof latLng>[] = [];
  const polyline = {
    getLength: vi.fn(() => polylinePath.length >= 2 ? 1250 : 0),
    setMap: vi.fn(),
    setPath: vi.fn((path: ReturnType<typeof latLng>[]) => {
      polylinePath = path;
    }),
  };
  const roadview = { setPanoId: vi.fn() };
  let nearestPanoId: number | null = 101;
  let roadviewAutoResolve = true;
  const roadviewRequests: Array<(panoId: number | null) => void> = [];
  const roadviewClient = {
    getNearestPanoId: vi.fn((_position: unknown, _radius: number, callback: (panoId: number | null) => void) => {
      roadviewRequests.push(callback);
      if (roadviewAutoResolve) callback(nearestPanoId);
    }),
  };
  const map = {
    addOverlayMapTypeId: vi.fn(),
    getBounds: () => ({
      getSouthWest: () => latLng(bounds.swLat, bounds.swLng),
      getNorthEast: () => latLng(bounds.neLat, bounds.neLng),
    }),
    getLevel: () => level,
    getCenter: vi.fn(() => center),
    relayout: vi.fn(),
    removeOverlayMapTypeId: vi.fn(),
    setCenter: vi.fn(),
    setMaxLevel: vi.fn(),
    setMinLevel: vi.fn(),
    setMapTypeId: vi.fn(),
    setLevel: vi.fn((nextLevel: number) => {
      level = nextLevel;
    }),
  };
  const kakao = {
    maps: {
      MapTypeId: {
        HYBRID: 'HYBRID',
        ROADMAP: 'ROADMAP',
        ROADVIEW: 'ROADVIEW',
        TERRAIN: 'TERRAIN',
        USE_DISTRICT: 'USE_DISTRICT',
      },
      LatLng: vi.fn(function (this: unknown, lat: number, lng: number) {
        void this;
        return latLng(lat, lng);
      }),
      Map: vi.fn(function (this: unknown) {
        void this;
        return map;
      }),
      CustomOverlay: vi.fn(function (this: unknown, options: { content: HTMLElement; yAnchor: number; zIndex?: number }) {
        void this;
        const overlay = {
          content: options.content,
          yAnchor: options.yAnchor,
          zIndex: options.zIndex,
          setMap: vi.fn(),
        };
        overlays.push(overlay);
        return overlay;
      }),
      Marker: vi.fn(function (this: unknown) {
        void this;
        return { setMap: vi.fn(), setPosition: vi.fn() };
      }),
      Polyline: vi.fn(function (this: unknown) {
        void this;
        return polyline;
      }),
      Roadview: vi.fn(function (this: unknown) {
        void this;
        return roadview;
      }),
      RoadviewClient: vi.fn(function (this: unknown) {
        void this;
        return roadviewClient;
      }),
      event: {
        addListener: vi.fn((_target: unknown, eventName: string, handler: (...args: never[]) => void) => {
          if (eventName === 'idle') {
            idleHandlers.push(handler as () => void);
          }
          if (eventName === 'click') {
            clickHandlers.push(handler as unknown as (event: { latLng: ReturnType<typeof latLng> }) => void);
          }
        }),
        removeListener: vi.fn((_target: unknown, eventName: string, handler: (...args: never[]) => void) => {
          if (eventName === 'click') {
            const index = clickHandlers.indexOf(handler as unknown as (event: { latLng: ReturnType<typeof latLng> }) => void);
            if (index >= 0) clickHandlers.splice(index, 1);
          }
        }),
      },
    },
  };

  return {
    kakao,
    map,
    center,
    overlays,
    polyline,
    roadview,
    roadviewClient,
    setViewport(nextViewport: { bounds: FakeBounds; level: number }) {
      bounds = nextViewport.bounds;
      level = nextViewport.level;
    },
    setRoadviewPanoId(panoId: number | null) {
      nearestPanoId = panoId;
    },
    setRoadviewAutoResolve(autoResolve: boolean) {
      roadviewAutoResolve = autoResolve;
    },
    resolveRoadviewRequest(index: number, panoId: number | null) {
      roadviewRequests[index]?.(panoId);
    },
    triggerIdle() {
      idleHandlers.forEach((handler) => handler());
    },
    triggerMapClick(lat: number, lng: number) {
      clickHandlers.forEach((handler) => handler({ latLng: latLng(lat, lng) }));
    },
  };
}

function latLng(lat: number, lng: number) {
  return {
    getLat: () => lat,
    getLng: () => lng,
  };
}
