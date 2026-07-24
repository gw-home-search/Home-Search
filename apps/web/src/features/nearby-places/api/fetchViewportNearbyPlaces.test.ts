import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchViewportNearbyPlaces } from './fetchViewportNearbyPlaces';

describe('viewport 주변시설 API 어댑터', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('선택 category 하나와 viewport를 POST하고 응답을 정규화한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
      level: 4,
      source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
      generatedAt: '2026-07-15T04:00:00Z',
      category: {
        category: 'CAFE',
        label: '카페',
        retrievedAt: '2026-07-15T03:59:50Z',
        places: [{
          placeId: 'kakao:1', name: '카페 이름', categoryDetail: '음식점 > 카페',
          lat: 37.49, lng: 126.91, distanceMeters: 170, address: '서울특별시',
          roadAddress: null, phone: null, placeUrl: 'https://place.map.kakao.com/1',
        }],
      },
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchViewportNearbyPlaces({
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
      level: 4,
      category: 'CAFE',
    });

    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/map/nearby-places'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93, level: 4, category: 'CAFE',
        }),
      }),
    );
    expect(result.category.places).toHaveLength(1);
    expect(result.category.places[0]?.name).toBe('카페 이름');
  });

  it('요청 category와 다르거나 10개를 초과한 응답을 거부한다', async () => {
    const response = {
      bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
      level: 4,
      source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
      generatedAt: '2026-07-15T04:00:00Z',
      category: {
        category: 'SCHOOL', label: '학교', retrievedAt: '2026-07-15T03:59:50Z', places: [],
      },
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(response), { status: 200 })));

    await expect(fetchViewportNearbyPlaces({
      bounds: response.bounds, level: 4, category: 'CAFE',
    })).rejects.toMatchObject({
      failure: { kind: 'invalid-response', operation: 'viewport-nearby-places' },
    });

    const overLimitResponse = {
      ...response,
      category: {
        ...response.category,
        category: 'CAFE',
        places: Array.from({ length: 11 }, (_, index) => ({
          placeId: `kakao:${index}`,
          name: `카페 ${index}`,
          categoryDetail: '음식점 > 카페',
          lat: 37.49,
          lng: 126.91,
          distanceMeters: index,
          address: '서울특별시',
          roadAddress: null,
          phone: null,
          placeUrl: `https://place.map.kakao.com/${index}`,
        })),
      },
    };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(overLimitResponse), { status: 200 })));

    await expect(fetchViewportNearbyPlaces({
      bounds: response.bounds, level: 4, category: 'CAFE',
    })).rejects.toMatchObject({
      failure: { kind: 'invalid-response', operation: 'viewport-nearby-places' },
    });
  });
});
