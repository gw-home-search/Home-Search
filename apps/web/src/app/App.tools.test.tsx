import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  createFakeKakaoSdk,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  unmount,
} from './appTestHarness';

describe('App 지도 도구', () => {
  afterEach(resetAppTestState);

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
});
