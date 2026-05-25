import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../features/map/api/resolveApiUrl';
import { App } from './App';

describe('App map-first shell 화면', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('collapsible exploration control과 in-map marker error가 있는 map-first shell을 rendering한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    const mapWorkspace = rootElement.querySelector('[data-layout-region="map-workspace"]');
    const mapSurface = rootElement.querySelector<HTMLElement>('[aria-label="Map surface"]');
    const explorationPanel = rootElement.querySelector<HTMLElement>('#exploration-panel');
    const filterPanel = rootElement.querySelector<HTMLElement>('form[aria-label="Marker filters"]');
    const explorationToggle = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="Toggle exploration panel"]',
    );
    const markerAlert = Array.from(rootElement.querySelectorAll('[role="alert"]')).find((alert) =>
      alert.textContent?.includes('Marker data unavailable'),
    );

    expect(mapWorkspace).not.toBeNull();
    expect(mapSurface).not.toBeNull();
    expect(mapWorkspace?.firstElementChild).toBe(mapSurface);
    expect(explorationPanel).not.toBeNull();
    expect(filterPanel?.getAttribute('data-map-overlay')).toBe('filters');
    expect(markerAlert?.closest('[aria-label="Map surface"]')).toBe(mapSurface);
    expect(explorationToggle?.getAttribute('aria-controls')).toBe('exploration-panel');
    expect(explorationToggle?.getAttribute('aria-expanded')).toBe('true');

    await act(async () => {
      explorationToggle?.click();
    });

    expect(explorationToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(explorationPanel?.getAttribute('data-collapsed')).toBe('true');
    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();

    unmount(root);
  });

  it('map surface를 block하지 않고 marker loading state를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));

    const { root, rootElement } = await renderApp();

    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Loading markers');

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
    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('No markers in this area');

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
      'button[aria-label="Zoom out"]',
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

  it('map surface를 제거하지 않고 non-blocking marker error를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Marker data unavailable',
    );
    expect(rootElement.textContent).toContain('Map remains usable');
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
      'button[aria-label="Open detail for parcel 1001"]',
    );
    expect(markerButton).not.toBeNull();

    await act(async () => {
      markerButton?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="Complex detail drawer"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    expect(rootElement.textContent).toContain('Sample address');
    expect(rootElement.textContent).toContain('2025-12-01');
    expect(rootElement.textContent).toContain('125,000 10k KRW');
    const chartSection = rootElement.querySelector<HTMLElement>(
      '[aria-label="Trade amount chart"]',
    );
    const chartPoints = Array.from(
      rootElement.querySelectorAll<HTMLElement>('[data-chart-point]'),
    );

    expect(chartSection).not.toBeNull();
    expect(chartSection?.textContent).toContain('125,000 10k KRW');
    expect(chartPoints.map((point) => point.dataset.chartDate)).toEqual([
      '2025-10-15',
      '2025-12-01',
    ]);

    unmount(root);
  });

  it('Kakao CustomOverlay complex marker에서 detail drawer를 연다', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse([
          {
            parcelId: 1001,
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
      'Open detail for parcel 1001',
    );

    await act(async () => {
      sdk.overlays[0]?.content.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(rootElement.querySelector('[aria-label="Complex detail drawer"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');
    const emptyChartSection = rootElement.querySelector<HTMLElement>(
      '[aria-label="Trade amount chart"]',
    );

    expect(emptyChartSection).not.toBeNull();
    expect(emptyChartSection?.textContent).toContain('No trade amounts to chart');

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
          trades: [],
        }),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const searchInput = rootElement.querySelector<HTMLInputElement>(
      'input[aria-label="Search complexes"]',
    );
    const searchButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="Run complex search"]',
    );
    const searchForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="Complex search"]',
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
      'button[aria-label="Select search result Sample Apartment"]',
    );
    expect(searchResult).not.toBeNull();

    await act(async () => {
      searchResult?.click();
    });
    await flushAsyncState();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/detail/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/trade/1001'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('"swLat":37.5023'),
      }),
    );
    expect(rootElement.querySelector('[aria-label="Complex detail drawer"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Sample complex name');

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
      'button[aria-label="Load root regions"]',
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
      'button[aria-label="Open region Seoul"]',
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

    setInputValue(rootElement, 'input[aria-label="Minimum pyeong"]', '20');
    setInputValue(rootElement, 'input[aria-label="Maximum pyeong"]', '34');
    setInputValue(rootElement, 'input[aria-label="Minimum price eok"]', '8.5');
    setInputValue(rootElement, 'input[aria-label="Maximum price eok"]', '15');
    setInputValue(rootElement, 'input[aria-label="Minimum building age"]', '5');
    setInputValue(rootElement, 'input[aria-label="Maximum building age"]', '25');
    setInputValue(rootElement, 'input[aria-label="Minimum unit count"]', '300');
    setInputValue(rootElement, 'input[aria-label="Maximum unit count"]', '1200');

    const applyButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="Apply marker filters"]',
    );
    const filterForm = rootElement.querySelector<HTMLFormElement>(
      'form[aria-label="Marker filters"]',
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
      'Marker data unavailable',
    );

    const retryButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="Retry marker load"]',
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

    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Kakao map unavailable',
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

  it('Kakao SDK script가 resolve될 때까지 loading map runtime status를 표시한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const { root, rootElement } = await renderApp({ kakaoMapAppKey: 'test-app-key' });
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );

    expect(script).not.toBeNull();
    expect(rootElement.textContent).toContain('Loading map runtime');
    expect(rootElement.textContent).not.toContain('Map ready');

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

    expect(rootElement.textContent).toContain('Map ready');
    expect(
      rootElement
        .querySelector('[aria-label="Kakao map viewport"]')
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
