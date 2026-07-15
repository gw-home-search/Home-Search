import { describe, expect, it } from 'vitest';

import type { ViewportNearbyPlaces } from './api/fetchViewportNearbyPlaces';
import type { NearbyPlaceCategory } from './api/fetchNearbyPlaces';
import { createMapNearbyPlaces } from './mapNearbyPlaces';

describe('지도 주변시설 조합', () => {
  it('category당 5개와 전체 15개를 넘지 않는다', () => {
    const categories: NearbyPlaceCategory[] = ['CAFE', 'HOSPITAL', 'SCHOOL'];
    const places = createMapNearbyPlaces(categories.map((category) => result(category, 6)), categories);

    expect(places).toHaveLength(15);
    expect(places.filter((item) => item.category === 'CAFE')).toHaveLength(5);
    expect(places.filter((item) => item.category === 'HOSPITAL')).toHaveLength(5);
    expect(places.filter((item) => item.category === 'SCHOOL')).toHaveLength(5);
  });

  it('여러 category에 동일한 Kakao placeId가 포함되면 하나만 표시한다', () => {
    const cafe = result('CAFE', 2);
    const restaurant = result('RESTAURANT', 6);
    restaurant.category.places[0] = cafe.category.places[0];

    const places = createMapNearbyPlaces([cafe, restaurant], ['CAFE', 'RESTAURANT']);

    expect(places).toHaveLength(7);
    expect(places.filter((item) => item.category === 'RESTAURANT')).toHaveLength(5);
    expect(new Set(places.map((item) => item.place.placeId))).toHaveProperty('size', 7);
  });
});

function result(category: NearbyPlaceCategory, count: number): ViewportNearbyPlaces {
  return {
    bounds: { swLat: 37.45, swLng: 126.85, neLat: 37.50, neLng: 126.93 },
    level: 4,
    source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
    generatedAt: '2026-07-15T04:00:00Z',
    category: {
      category,
      label: category,
      retrievedAt: '2026-07-15T03:59:50Z',
      places: Array.from({ length: count }, (_, index) => ({
        placeId: `kakao:${category}:${index}`,
        name: `${category} ${index}`,
        categoryDetail: null,
        lat: 37.49,
        lng: 126.91,
        distanceMeters: index,
        address: null,
        roadAddress: null,
        phone: null,
        placeUrl: null,
      })),
    },
  };
}
