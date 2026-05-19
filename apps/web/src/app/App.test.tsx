import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { KakaoMapsApi } from '../features/map/kakao/kakaoMapsTypes';
import { loadKakaoMapsSdk } from '../features/map/kakao/loadKakaoMapsSdk';
import { v1FetchUrl } from '../features/map/api/testUrl';
import { App } from './App';

vi.mock('../features/map/kakao/loadKakaoMapsSdk', () => ({
  loadKakaoMapsSdk: vi.fn(),
}));

const loadKakaoMapsSdkMock = vi.mocked(loadKakaoMapsSdk);

describe('App', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('creates a ready Kakao map surface and fetches markers from SDK bounds and level', async () => {
    const kakao = createFakeKakaoMaps({
      bounds: {
        swLat: 37.1,
        swLng: 126.8,
        neLat: 37.8,
        neLng: 127.3,
      },
      level: 4,
    });
    loadKakaoMapsSdkMock.mockResolvedValue(kakao.api);
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const mapSurface = rootElement.querySelector('[data-testid="kakao-map-surface"]');
    expect(mapSurface).not.toBeNull();
    expect(mapSurface?.getAttribute('data-map-state')).toBe('ready');
    expect(mapSurface?.getAttribute('data-map-level')).toBe('4');
    expect(mapSurface?.getAttribute('data-marker-kind')).toBe('complex');

    expect(kakao.maps).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledWith(
      v1FetchUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.1,
          swLng: 126.8,
          neLat: 37.8,
          neLng: 127.3,
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

  it('keeps the map surface visible when the Kakao SDK cannot load', async () => {
    loadKakaoMapsSdkMock.mockRejectedValue(new Error('VITE_KAKAO_MAP_APP_KEY is required'));
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    const mapSurface = rootElement.querySelector('[data-testid="kakao-map-surface"]');
    expect(mapSurface).not.toBeNull();
    expect(mapSurface?.getAttribute('data-map-state')).toBe('error');
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Map unavailable',
    );
    expect(fetchMock).not.toHaveBeenCalled();

    unmount(root);
  });

  it('renders complex marker overlays and cleans them up when the marker kind changes', async () => {
    const firstBounds = {
      swLat: 37.1,
      swLng: 126.8,
      neLat: 37.8,
      neLng: 127.3,
    };
    const secondBounds = {
      swLat: 37.2,
      swLng: 126.9,
      neLat: 37.9,
      neLng: 127.4,
    };
    const kakao = createFakeKakaoMaps({ bounds: firstBounds, level: 4 });
    loadKakaoMapsSdkMock.mockResolvedValue(kakao.api);
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
        jsonResponse([
          {
            id: 1,
            name: 'Seoul',
            lat: 37.5663,
            lng: 126.978,
          },
        ]),
      );
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp();
    await flushAsyncState();
    await flushAsyncState();

    expect(kakao.overlays).toHaveLength(1);
    expect(kakao.overlays[0].kind).toBe('complex');
    expect(kakao.overlays[0].setMap).toHaveBeenLastCalledWith(kakao.maps[0]);

    kakao.maps[0].setBounds(secondBounds);
    const zoomOutButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="Zoom out"]',
    );
    expect(zoomOutButton).not.toBeNull();

    await act(async () => {
      zoomOutButton?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(fetchMock).toHaveBeenLastCalledWith(
      v1FetchUrl('/api/v1/map/regions'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          ...secondBounds,
          region: 'eup-myeon-dong',
        }),
      }),
    );
    expect(kakao.overlays).toHaveLength(2);
    expect(kakao.overlays[0].setMap).toHaveBeenCalledWith(null);
    expect(kakao.overlays[1].kind).toBe('region');
    expect(
      rootElement
        .querySelector('[data-testid="kakao-map-surface"]')
        ?.getAttribute('data-marker-kind'),
    ).toBe('region');

    unmount(root);
  });

  it('shows a non-blocking marker error without removing the map surface', async () => {
    const kakao = createFakeKakaoMaps({
      bounds: {
        swLat: 37.1,
        swLng: 126.8,
        neLat: 37.8,
        neLng: 127.3,
      },
      level: 4,
    });
    loadKakaoMapsSdkMock.mockResolvedValue(kakao.api);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500)));

    const { root, rootElement } = await renderApp();
    await flushAsyncState();

    expect(
      rootElement.querySelector('[data-testid="kakao-map-surface"]')?.getAttribute('data-map-state'),
    ).toBe('ready');
    expect(rootElement.querySelector('[role="alert"]')?.textContent).toContain(
      'Marker data unavailable',
    );
    expect(kakao.overlays).toHaveLength(0);

    unmount(root);
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

type FakeBoundsRequest = {
  swLat: number;
  swLng: number;
  neLat: number;
  neLng: number;
};

type FakeKakaoOptions = {
  bounds: FakeBoundsRequest;
  level: number;
};

function createFakeKakaoMaps(options: FakeKakaoOptions) {
  const maps: FakeMap[] = [];
  const overlays: FakeOverlay[] = [];

  class LatLng {
    constructor(
      private readonly lat: number,
      private readonly lng: number,
    ) {}

    getLat() {
      return this.lat;
    }

    getLng() {
      return this.lng;
    }
  }

  class LatLngBounds {
    constructor(private readonly bounds: FakeBoundsRequest) {}

    getSouthWest() {
      return new LatLng(this.bounds.swLat, this.bounds.swLng);
    }

    getNorthEast() {
      return new LatLng(this.bounds.neLat, this.bounds.neLng);
    }
  }

  class FakeMap {
    private bounds = options.bounds;
    private level = options.level;
    private readonly listeners = new Map<string, Set<() => void>>();

    constructor(
      readonly container: HTMLElement,
      readonly mapOptions: { center: LatLng; level: number },
    ) {
      this.level = mapOptions.level;
      maps.push(this);
    }

    getBounds() {
      return new LatLngBounds(this.bounds);
    }

    setBounds(bounds: FakeBoundsRequest) {
      this.bounds = bounds;
    }

    getLevel() {
      return this.level;
    }

    setLevel(level: number) {
      this.level = level;
      this.emit('idle');
    }

    addListener(name: string, listener: () => void) {
      const listeners = this.listeners.get(name) ?? new Set<() => void>();
      listeners.add(listener);
      this.listeners.set(name, listeners);
    }

    removeListener(name: string, listener: () => void) {
      this.listeners.get(name)?.delete(listener);
    }

    private emit(name: string) {
      this.listeners.get(name)?.forEach((listener) => listener());
    }
  }

  class FakeOverlay {
    readonly kind: 'complex' | 'region';
    readonly setMap = vi.fn();

    constructor(readonly overlayOptions: { position: LatLng; content: HTMLElement | string }) {
      const content =
        typeof overlayOptions.content === 'string'
          ? overlayOptions.content
          : overlayOptions.content.dataset.markerKind;
      this.kind = content === 'region' ? 'region' : 'complex';
      overlays.push(this);
    }
  }

  return {
    api: {
      Map: FakeMap,
      LatLng,
      LatLngBounds,
      CustomOverlay: FakeOverlay,
      event: {
        addListener: vi.fn((target: FakeMap, name: string, listener: () => void) => {
          target.addListener(name, listener);
          return { target, name, listener };
        }),
        removeListener: vi.fn(
          (handle: { target: FakeMap; name: string; listener: () => void }) => {
            handle.target.removeListener(handle.name, handle.listener);
          },
        ),
      },
    } as unknown as KakaoMapsApi,
    maps,
    overlays,
  };
}
