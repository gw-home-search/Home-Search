import { afterEach, describe, expect, it, vi } from 'vitest';

import type { KakaoMapsApi } from './loadKakaoMapSdk';

describe('loadKakaoMapSdk helper 동작', () => {
  afterEach(() => {
    vi.resetModules();
    vi.unstubAllGlobals();
    document.head
      .querySelectorAll('script[src*="dapi.kakao.com/v2/maps/sdk.js"]')
      .forEach((script) => script.remove());
  });

  it('autoload disabled로 Kakao SDK를 load하고 maps.load 후 resolve한다', async () => {
    const { loadKakaoMapSdk } = await import('./loadKakaoMapSdk');

    const sdkPromise = loadKakaoMapSdk('test-app-key');
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );

    expect(script).not.toBeNull();
    expect(script?.src).toContain('appkey=test-app-key');
    expect(script?.src).toContain('autoload=false');

    const maps = fakeKakaoMapsApi();
    maps.load = vi.fn((callback: () => void) => callback());
    vi.stubGlobal('kakao', { maps });

    script?.onload?.call(script, new Event('load'));

    await expect(sdkPromise).resolves.toBe(maps);
    expect(maps.load).toHaveBeenCalledTimes(1);
  });

  it('이미 사용 가능한 Kakao map runtime은 app key 없이 사용한다', async () => {
    const maps = fakeKakaoMapsApi();
    vi.stubGlobal('kakao', { maps });
    const { loadKakaoMapSdk } = await import('./loadKakaoMapSdk');

    await expect(loadKakaoMapSdk('')).resolves.toBe(maps);
    expect(document.head.querySelector('script[src*="dapi.kakao.com/v2/maps/sdk.js"]')).toBeNull();
  });

  it('Kakao runtime이 map constructor를 노출하지 않으면 maps.load 후 reject한다', async () => {
    const { loadKakaoMapSdk } = await import('./loadKakaoMapSdk');

    const sdkPromise = loadKakaoMapSdk('test-app-key');
    const script = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );
    const maps = {
      load: vi.fn((callback: () => void) => callback()),
    };
    vi.stubGlobal('kakao', { maps });

    script?.onload?.call(script, new Event('load'));

    await expect(sdkPromise).rejects.toThrow('Kakao map SDK did not expose map constructors');
  });

  it('SDK load 실패 후 실패 script를 제거하고 새 요청으로 다시 초기화한다', async () => {
    const { loadKakaoMapSdk } = await import('./loadKakaoMapSdk');

    const firstPromise = loadKakaoMapSdk('test-app-key');
    const firstScript = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );
    firstScript?.onerror?.call(firstScript, new Event('error'));

    await expect(firstPromise).rejects.toThrow('Kakao map SDK failed to load');
    expect(document.head.querySelectorAll('script[src*="dapi.kakao.com/v2/maps/sdk.js"]')).toHaveLength(0);

    const retryPromise = loadKakaoMapSdk('test-app-key');
    const retryScript = document.head.querySelector<HTMLScriptElement>(
      'script[src*="dapi.kakao.com/v2/maps/sdk.js"]',
    );
    expect(retryScript).not.toBeNull();
    expect(retryScript).not.toBe(firstScript);

    retryScript?.onerror?.call(retryScript, new Event('error'));
    await expect(retryPromise).rejects.toThrow('Kakao map SDK failed to load');
  });
});

function fakeKakaoMapsApi(): KakaoMapsApi {
  return {
    LatLng: function FakeLatLng(this: unknown) {
      void this;
      return { getLat: () => 37.5663, getLng: () => 126.978 };
    } as unknown as KakaoMapsApi['LatLng'],
    Map: function FakeMap(this: unknown) {
      void this;
      return {
        getBounds: () => ({
          getSouthWest: () => ({ getLat: () => 37.45, getLng: () => 126.85 }),
          getNorthEast: () => ({ getLat: () => 37.7, getLng: () => 127.2 }),
        }),
        getLevel: () => 4,
      };
    } as unknown as KakaoMapsApi['Map'],
    CustomOverlay: function FakeCustomOverlay(this: unknown) {
      void this;
      return { setMap: vi.fn() };
    } as unknown as KakaoMapsApi['CustomOverlay'],
    Marker: function FakeMarker(this: unknown) {
      void this;
      return { setMap: vi.fn(), setPosition: vi.fn() };
    } as unknown as KakaoMapsApi['Marker'],
    Polyline: function FakePolyline(this: unknown) {
      void this;
      return { getLength: vi.fn(() => 0), setMap: vi.fn(), setPath: vi.fn() };
    } as unknown as KakaoMapsApi['Polyline'],
    Roadview: function FakeRoadview(this: unknown) {
      void this;
      return { setPanoId: vi.fn() };
    } as unknown as KakaoMapsApi['Roadview'],
    RoadviewClient: function FakeRoadviewClient(this: unknown) {
      void this;
      return { getNearestPanoId: vi.fn() };
    } as unknown as KakaoMapsApi['RoadviewClient'],
    MapTypeId: {
      HYBRID: 'HYBRID',
      ROADMAP: 'ROADMAP',
      ROADVIEW: 'ROADVIEW',
      TERRAIN: 'TERRAIN',
      USE_DISTRICT: 'USE_DISTRICT',
    },
    event: {
      addListener: vi.fn(),
      removeListener: vi.fn(),
    },
  };
}
