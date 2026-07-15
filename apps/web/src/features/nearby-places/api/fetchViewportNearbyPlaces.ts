import type { MapViewport } from '../../../app/mapAppTypes';
import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import {
  NEARBY_PLACE_CATEGORIES,
  type NearbyPlace,
  type NearbyPlaceCategory,
} from './fetchNearbyPlaces';

export type ViewportNearbyPlaces = {
  bounds: MapViewport['bounds'];
  level: number;
  source: { provider: 'KAKAO_LOCAL'; countBasis: 'PROVIDER_SEARCH' };
  generatedAt: string;
  category: {
    category: NearbyPlaceCategory;
    label: string;
    retrievedAt: string;
    places: NearbyPlace[];
  };
};

export type ViewportNearbyPlaceRequest = MapViewport & {
  category: NearbyPlaceCategory;
};

export async function fetchViewportNearbyPlaces(
  request: ViewportNearbyPlaceRequest,
  signal?: AbortSignal,
): Promise<ViewportNearbyPlaces> {
  const response = await fetch(resolveApiUrl('/api/v1/map/nearby-places'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...request.bounds, level: request.level, category: request.category }),
    signal,
  });
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`주변 상권 정보를 불러오지 못했습니다: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return normalize(await response.json(), request.category);
}

function normalize(value: unknown, requestedCategory: NearbyPlaceCategory): ViewportNearbyPlaces {
  const root = record(value, 'response');
  const bounds = record(root.bounds, 'bounds');
  const source = record(root.source, 'source');
  const category = record(root.category, 'category');
  if (source.provider !== 'KAKAO_LOCAL' || source.countBasis !== 'PROVIDER_SEARCH') throw invalid('source');
  if (
    !isCategory(category.category)
    || category.category !== requestedCategory
    || !Array.isArray(category.places)
    || category.places.length > 10
  ) throw invalid('category');
  const swLat = coordinate(bounds.swLat, 'bounds.swLat', -90, 90);
  const swLng = coordinate(bounds.swLng, 'bounds.swLng', -180, 180);
  const neLat = coordinate(bounds.neLat, 'bounds.neLat', -90, 90);
  const neLng = coordinate(bounds.neLng, 'bounds.neLng', -180, 180);
  if (swLat >= neLat || swLng >= neLng) throw invalid('bounds');
  return {
    bounds: {
      swLat,
      swLng,
      neLat,
      neLng,
    },
    level: finite(root.level, 'level'),
    source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
    generatedAt: text(root.generatedAt, 'generatedAt'),
    category: {
      category: category.category,
      label: text(category.label, 'category.label'),
      retrievedAt: text(category.retrievedAt, 'category.retrievedAt'),
      places: category.places.map(normalizePlace).sort((left, right) => left.distanceMeters - right.distanceMeters),
    },
  };
}

function normalizePlace(value: unknown): NearbyPlace {
  const place = record(value, 'place');
  return {
    placeId: text(place.placeId, 'place.placeId'),
    name: text(place.name, 'place.name'),
    categoryDetail: optionalText(place.categoryDetail),
    lat: finite(place.lat, 'place.lat'),
    lng: finite(place.lng, 'place.lng'),
    distanceMeters: nonNegative(place.distanceMeters, 'place.distanceMeters'),
    address: optionalText(place.address),
    roadAddress: optionalText(place.roadAddress),
    phone: optionalText(place.phone),
    placeUrl: validatedPlaceUrl(place.placeUrl),
  };
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) throw invalid(field);
  return value as Record<string, unknown>;
}

function finite(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw invalid(field);
  return value;
}

function nonNegative(value: unknown, field: string): number {
  const result = finite(value, field);
  if (result < 0) throw invalid(field);
  return result;
}

function coordinate(value: unknown, field: string, min: number, max: number): number {
  const result = finite(value, field);
  if (result < min || result > max) throw invalid(field);
  return result;
}

function text(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) throw invalid(field);
  return value;
}

function optionalText(value: unknown): string | null {
  return typeof value === 'string' && value.length > 0 ? value : null;
}

function validatedPlaceUrl(value: unknown): string | null {
  const candidate = optionalText(value);
  if (candidate === null) return null;
  try {
    const url = new URL(candidate);
    if (
      url.protocol !== 'https:'
      || url.hostname !== 'place.map.kakao.com'
      || url.port !== ''
      || url.username !== ''
      || url.password !== ''
    ) throw invalid('place.placeUrl');
    return url.toString();
  } catch (error) {
    if (error instanceof Error && error.message.startsWith('Invalid viewport')) throw error;
    throw invalid('place.placeUrl');
  }
}

function isCategory(value: unknown): value is NearbyPlaceCategory {
  return typeof value === 'string' && (NEARBY_PLACE_CATEGORIES as readonly string[]).includes(value);
}

function invalid(field: string): Error {
  return new Error(`Invalid viewport nearby-place API response: ${field}`);
}
