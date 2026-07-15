import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export const NEARBY_PLACE_CATEGORIES = [
  'CAFE',
  'RESTAURANT',
  'CONVENIENCE_STORE',
  'HOSPITAL',
  'PHARMACY',
  'SCHOOL',
  'SUPERMARKET',
  'DAYCARE_KINDERGARTEN',
  'ACADEMY',
  'SUBWAY_STATION',
] as const;

export type NearbyPlaceCategory = typeof NEARBY_PLACE_CATEGORIES[number];

export const DEFAULT_NEARBY_PLACE_CATEGORIES = [
  'SUPERMARKET',
  'CONVENIENCE_STORE',
  'RESTAURANT',
  'DAYCARE_KINDERGARTEN',
  'SCHOOL',
  'ACADEMY',
  'SUBWAY_STATION',
  'HOSPITAL',
] as const satisfies readonly NearbyPlaceCategory[];

export const MAP_NEARBY_PLACE_CATEGORIES = DEFAULT_NEARBY_PLACE_CATEGORIES;

export const NEARBY_PLACE_CATEGORY_LABELS: Readonly<Record<NearbyPlaceCategory, string>> = {
  CAFE: '카페',
  RESTAURANT: '음식점',
  CONVENIENCE_STORE: '편의점',
  HOSPITAL: '병원',
  PHARMACY: '약국',
  SCHOOL: '학교',
  SUPERMARKET: '대형마트',
  DAYCARE_KINDERGARTEN: '어린이집·유치원',
  ACADEMY: '학원',
  SUBWAY_STATION: '지하철역',
};

export type NearbyPlace = {
  placeId: string;
  name: string;
  categoryDetail: string | null;
  lat: number;
  lng: number;
  distanceMeters: number;
  address: string | null;
  roadAddress: string | null;
  phone: string | null;
  placeUrl: string | null;
};

export type NearbyPlaceCategoryResult = {
  category: NearbyPlaceCategory;
  label: string;
  matchedCount: number;
  returnedCount: number;
  hasMore: boolean;
  retrievedAt: string;
  places: NearbyPlace[];
};

export type NearbyPlaces = {
  complexId: number;
  center: { lat: number; lng: number };
  radiusMeters: number;
  source: { provider: 'KAKAO_LOCAL'; countBasis: 'PROVIDER_SEARCH' };
  generatedAt: string;
  categories: NearbyPlaceCategoryResult[];
};

type FetchNearbyPlacesOptions = {
  radiusMeters?: number;
  categories?: readonly NearbyPlaceCategory[];
  limitPerCategory?: number;
  signal?: AbortSignal;
};

export async function fetchNearbyPlaces(
  complexId: number,
  options: FetchNearbyPlacesOptions = {},
): Promise<NearbyPlaces> {
  const radiusMeters = options.radiusMeters ?? 800;
  const categories = options.categories ?? DEFAULT_NEARBY_PLACE_CATEGORIES;
  const limitPerCategory = options.limitPerCategory ?? 5;
  const query = new URLSearchParams({
    radiusMeters: String(radiusMeters),
    categories: categories.join(','),
    limitPerCategory: String(limitPerCategory),
  });
  const response = await fetch(resolveApiUrl(
    `/api/v1/complex/${encodeURIComponent(complexId)}/nearby-places?${query}`,
  ), { method: 'GET', signal: options.signal });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`주변 상권 정보를 불러오지 못했습니다: ${response.status}${detail ? ` ${detail}` : ''}`);
  }

  const payload: unknown = await response.json();
  return normalizeNearbyPlaces(payload);
}

function normalizeNearbyPlaces(value: unknown): NearbyPlaces {
  const root = record(value, 'response');
  const center = record(root.center, 'center');
  const source = record(root.source, 'source');
  if (source.provider !== 'KAKAO_LOCAL' || source.countBasis !== 'PROVIDER_SEARCH') {
    throw invalid('source');
  }
  if (!Array.isArray(root.categories)) throw invalid('categories');

  return {
    complexId: finiteNumber(root.complexId, 'complexId'),
    center: {
      lat: finiteNumber(center.lat, 'center.lat'),
      lng: finiteNumber(center.lng, 'center.lng'),
    },
    radiusMeters: finiteNumber(root.radiusMeters, 'radiusMeters'),
    source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
    generatedAt: requiredString(root.generatedAt, 'generatedAt'),
    categories: root.categories.map(normalizeCategory),
  };
}

function normalizeCategory(value: unknown): NearbyPlaceCategoryResult {
  const item = record(value, 'category');
  if (!isCategory(item.category)) throw invalid('category.category');
  if (!Array.isArray(item.places)) throw invalid('category.places');
  return {
    category: item.category,
    label: requiredString(item.label, 'category.label'),
    matchedCount: nonNegativeNumber(item.matchedCount, 'category.matchedCount'),
    returnedCount: nonNegativeNumber(item.returnedCount, 'category.returnedCount'),
    hasMore: booleanValue(item.hasMore, 'category.hasMore'),
    retrievedAt: requiredString(item.retrievedAt, 'category.retrievedAt'),
    places: item.places.map(normalizePlace).sort((left, right) => left.distanceMeters - right.distanceMeters),
  };
}

function normalizePlace(value: unknown): NearbyPlace {
  const item = record(value, 'place');
  return {
    placeId: requiredString(item.placeId, 'place.placeId'),
    name: requiredString(item.name, 'place.name'),
    categoryDetail: nullableString(item.categoryDetail),
    lat: finiteNumber(item.lat, 'place.lat'),
    lng: finiteNumber(item.lng, 'place.lng'),
    distanceMeters: nonNegativeNumber(item.distanceMeters, 'place.distanceMeters'),
    address: nullableString(item.address),
    roadAddress: nullableString(item.roadAddress),
    phone: nullableString(item.phone),
    placeUrl: nullableString(item.placeUrl),
  };
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid(field);
  return value as Record<string, unknown>;
}

function finiteNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw invalid(field);
  return value;
}

function nonNegativeNumber(value: unknown, field: string): number {
  const parsed = finiteNumber(value, field);
  if (parsed < 0) throw invalid(field);
  return parsed;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) throw invalid(field);
  return value;
}

function nullableString(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function booleanValue(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') throw invalid(field);
  return value;
}

function isCategory(value: unknown): value is NearbyPlaceCategory {
  return typeof value === 'string'
    && (NEARBY_PLACE_CATEGORIES as readonly string[]).includes(value);
}

function invalid(field: string): Error {
  return new Error(`Invalid nearby-place API response: ${field}`);
}
