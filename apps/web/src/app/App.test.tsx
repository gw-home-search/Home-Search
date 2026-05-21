import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../features/map/api/resolveApiUrl';
import { App } from './App';

describe('App', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows marker loading state without blocking the map surface', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => undefined)));

    const { root, rootElement } = await renderApp();

    expect(rootElement.querySelector('[aria-label="Map surface"]')).not.toBeNull();
    expect(rootElement.textContent).toContain('Loading markers');

    unmount(root);
  });

  it('shows an empty marker state when the marker API returns an empty list', async () => {
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

  it('uses region markers before detailed map levels', async () => {
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

  it('refreshes markers on map zoom changes and ignores stale previous responses', async () => {
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

  it('shows a non-blocking marker error without removing the map surface', async () => {
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

  it('opens the detail drawer from a complex marker and loads V1 detail and trade data', async () => {
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

    unmount(root);
  });

  it('opens the detail drawer from a Kakao CustomOverlay complex marker', async () => {
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

    unmount(root);
  });

  it('searches complexes with the documented URL and opens the selected parcel detail', async () => {
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

  it('loads region navigation and refreshes map context from selected region detail', async () => {
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

  it('applies filter controls to the documented complex marker request fields', async () => {
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

  it('recovers from a marker error with a same-viewport retry on the Kakao map surface', async () => {
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

  it('uses Kakao map runtime bounds and level when the SDK is already available', async () => {
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

  it('refreshes marker requests when Kakao idle reports a new viewport', async () => {
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

  it('keeps the map surface visible and reports a non-blocking Kakao runtime error when no key is configured', async () => {
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

  it('renders Kakao CustomOverlay markers and clears them on unmount', async () => {
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

  it('shows a loading map runtime status until the Kakao SDK script resolves', async () => {
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
