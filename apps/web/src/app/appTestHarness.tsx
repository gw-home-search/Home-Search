import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { expect, vi } from 'vitest';

import { resolveApiUrl } from '../features/map/api/resolveApiUrl';
import type { AuthClient } from '../features/auth/api/authClient';
import { App } from './App';

export { resolveApiUrl };
export type { AuthClient };

export function resetAppTestState(): void {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  window.sessionStorage.clear();
  window.history.pushState({}, '', '/');
  Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1024 });
}
export type TestAppProps = Parameters<typeof App>[0];

export async function renderApp(props?: TestAppProps): Promise<{ root: Root; rootElement: HTMLDivElement }> {
  const rootElement = document.createElement('div');
  const root = createRoot(rootElement);

  await act(async () => {
    root.render(<App authClient={testAnonymousAuthClient} initialRegionLoad={false} {...props} />);
  });

  return { root, rootElement };
}

export const testAnonymousAuthClient: AuthClient = {
  authenticatedRequest: async () => { throw new Error('Authentication required'); },
  authorizationUrl: (provider) => `http://localhost:8082/oauth2/authorization/${provider}`,
  logout: async () => undefined,
  restoreSession: async () => ({ kind: 'anonymous' }),
};

export async function flushAsyncState(): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => {
      setTimeout(resolve, 0);
    });
  });
}

export async function flushLazyRoute(): Promise<void> {
  await act(async () => {
    await vi.dynamicImportSettled();
  });
  await flushAsyncState();
}

export async function waitForMillis(ms: number): Promise<void> {
  await act(async () => {
    await new Promise((resolve) => {
      setTimeout(resolve, ms);
    });
  });
}

export async function applyFilterRange(
  rootElement: HTMLElement,
  label: '세대수' | '평형' | '가격' | '입주년차' | '건폐율' | '용적률',
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

export function unmount(root: Root): void {
  act(() => {
    root.unmount();
  });
}

export function setInputValue(rootElement: HTMLElement, selector: string, value: string): void {
  const input = rootElement.querySelector<HTMLInputElement>(selector);
  expect(input).not.toBeNull();

  if (input) {
    input.value = value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }
}

export function submitForm(form: HTMLFormElement | null): void {
  form?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
}

export function jsonResponse(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    json: () => Promise.resolve(body),
  } as Response;
}

export function errorResponse(status: number): Response {
  return {
    ok: false,
    status,
  } as Response;
}

export function deferred<T>(): {
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

export type FakeBounds = {
  swLat: number;
  swLng: number;
  neLat: number;
  neLng: number;
};

export type FakeOverlay = {
  content: HTMLElement;
  yAnchor: number;
  zIndex?: number;
  setMap: ReturnType<typeof vi.fn>;
};

export function createFakeKakaoSdk(options: { bounds: FakeBounds; level: number }) {
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

export function latLng(lat: number, lng: number) {
  return {
    getLat: () => lat,
    getLng: () => lng,
  };
}
