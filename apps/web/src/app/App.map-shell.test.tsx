import { act } from 'react';
import { IDBFactory } from 'fake-indexeddb';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AuthClient } from './appTestHarness';
import { IndexedDbChatConversationStore } from '../features/chat/storage/chatConversationStore';
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

describe('App 지도 shell', () => {
  afterEach(resetAppTestState);

  it('desktop exploration rail은 항상 표시하고 운영 상태·toggle 문구를 제거한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    const mapWorkspace = rootElement.querySelector('[data-layout-region="map-workspace"]');
    const mapSurface = rootElement.querySelector<HTMLElement>('[aria-label="지도 화면"]');
    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const filterPanel = rootElement.querySelector<HTMLElement>('form[aria-label="마커 필터"]');
    const markerAlert = Array.from(rootElement.querySelectorAll('[role="status"]')).find((alert) =>
      alert.textContent?.includes('이 지역의 단지 정보를 새로 불러오지 못했어요'),
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
    expect(rootElement.querySelector('.app-bar .chatbot-launcher')?.getAttribute('aria-label'))
      .toBe('홈서치 AI 열기');
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/regions'),
      expect.objectContaining({ method: 'POST' }),
    );

    unmount(root);
  });

  it('챗봇을 열면 중간 폭 화면에서 탐색 rail을 닫고 지도와 대화의 split 상태를 노출한다', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1180 });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    const authClient: AuthClient = {
      authenticatedRequest: async () => new Response('{}', { status: 503 }),
      authorizationUrl: (provider) => `http://localhost:8082/oauth2/authorization/${provider}`,
      logout: async () => undefined,
      restoreSession: async () => ({
        kind: 'authenticated',
        currentUser: { userId: 1, provider: 'google', displayName: '지도 사용자', profileImage: null },
      }),
    };
    const chatConversationStore = new IndexedDbChatConversationStore(
      new IDBFactory(),
      'map-shell-chat-layout',
    );

    const { root, rootElement } = await renderApp({ authClient, chatConversationStore });
    await flushAsyncState();
    const shell = rootElement.querySelector<HTMLElement>('.app-shell');
    expect(shell?.getAttribute('data-chat-open')).toBe('false');

    await act(async () => rootElement.querySelector<HTMLButtonElement>('.chatbot-launcher')?.click());
    await flushAsyncState();
    expect(shell?.getAttribute('data-chat-open')).toBe('true');
    expect(rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rootElement.querySelector('#exploration-panel')?.hasAttribute('hidden')).toBe(true);

    await act(async () => rootElement.querySelector<HTMLButtonElement>('button[aria-label="챗봇 닫기"]')?.click());
    expect(shell?.getAttribute('data-chat-open')).toBe('false');
    expect(rootElement.querySelector('#exploration-panel')?.hasAttribute('hidden')).toBe(false);

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
    expect(Array.from(rootElement.querySelectorAll('[role="status"]')).some((notice) =>
      notice.textContent?.includes('이 지역의 단지 정보를 새로 불러오지 못했어요'))).toBe(true);
    expect(rootElement.textContent).toContain('지도 이동과 확대·축소는 계속 사용할 수 있어요.');
    expect(rootElement.querySelectorAll('[data-marker-id]')).toHaveLength(0);

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

    expect(Array.from(rootElement.querySelectorAll('[role="status"]')).some((notice) =>
      notice.textContent?.includes('이 지역의 단지 정보를 새로 불러오지 못했어요'))).toBe(true);

    const retryButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="단지 다시 불러오기"]',
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
    expect(Array.from(rootElement.querySelectorAll('[role="status"]')).some((notice) =>
      notice.textContent?.includes('지도를 준비하지 못했어요'))).toBe(true);

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
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('kakao', sdk.kakao);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    const host = rootElement.querySelector<HTMLElement>('[aria-label="카카오 지도 화면"]');
    expect(observe).toHaveBeenCalledWith(host);
    const relayoutCallsBeforeResize = sdk.map.relayout.mock.calls.length;
    sdk.setViewport({
      bounds: { swLat: 37.5, swLng: 126.9, neLat: 37.65, neLng: 127.1 },
      level: 4,
    });
    act(() => {
      resizeCallback?.([], {} as ResizeObserver);
    });
    expect(sdk.map.relayout).toHaveBeenCalledTimes(relayoutCallsBeforeResize + 1);
    expect(sdk.map.setCenter).toHaveBeenLastCalledWith(sdk.center);

    await flushAsyncState();
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.5,
          swLng: 126.9,
          neLat: 37.65,
          neLng: 127.1,
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
