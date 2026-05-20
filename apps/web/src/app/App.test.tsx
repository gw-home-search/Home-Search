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
    expect(rootElement.textContent).toContain('Map ready');

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
      CustomOverlay: vi.fn(function (this: unknown) {
        void this;
        const overlay = { setMap: vi.fn() };
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
