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

describe('App 주변 시설', () => {
  afterEach(resetAppTestState);

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
});
