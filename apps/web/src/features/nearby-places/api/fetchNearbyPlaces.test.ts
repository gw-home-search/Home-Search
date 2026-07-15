import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchNearbyPlaces } from './fetchNearbyPlaces';

describe('fetchNearbyPlaces 주변 장소 요청', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('단지 기준 제품 기본 8개 category 요청을 만들고 공용 응답을 정규화한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      complexId: 501,
      center: { lat: 37.321, lng: 127.109 },
      radiusMeters: 800,
      source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
      generatedAt: '2026-07-13T03:00:01Z',
      categories: [{
        category: 'CAFE',
        label: '카페',
        matchedCount: 18,
        returnedCount: 1,
        hasMore: true,
        retrievedAt: '2026-07-13T03:00:00Z',
        places: [{
          placeId: 'kakao:123456',
          name: '카페 이름',
          categoryDetail: '음식점 > 카페',
          lat: 37.322,
          lng: 127.108,
          distanceMeters: 72,
          address: '경기도 수원시',
          roadAddress: '경기도 수원시 도로명',
          phone: '031-000-0000',
          placeUrl: 'https://place.map.kakao.com/123456',
        }],
      }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchNearbyPlaces(501);

    const url = new URL(fetchMock.mock.calls[0][0]);
    expect(url.pathname).toBe('/api/v1/complex/501/nearby-places');
    expect(url.searchParams.get('radiusMeters')).toBe('800');
    expect(url.searchParams.get('categories')).toBe(
      'SUPERMARKET,CONVENIENCE_STORE,RESTAURANT,DAYCARE_KINDERGARTEN,SCHOOL,ACADEMY,SUBWAY_STATION,HOSPITAL',
    );
    expect(url.searchParams.get('limitPerCategory')).toBe('5');
    expect(result.categories[0].places[0]).toMatchObject({ placeId: 'kakao:123456', distanceMeters: 72 });
  });

  it('ProblemDetail을 사용자에게 노출할 수 있는 오류로 변환한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      detail: 'Nearby place provider unavailable.',
    }), { status: 503, headers: { 'Content-Type': 'application/problem+json' } })));

    await expect(fetchNearbyPlaces(501)).rejects.toThrow('503 Nearby place provider unavailable.');
  });
});
