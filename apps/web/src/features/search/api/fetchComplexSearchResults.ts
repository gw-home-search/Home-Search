import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type ComplexSearchResult = {
  complexId: number;
  complexName: string;
  parcelId: number;
  latitude: number | null;
  longitude: number | null;
  address: string | null;
};

type ComplexSearchResultResponse = {
  complexId?: number | string;
  complexName?: string;
  parcelId?: number | string;
  latitude?: number | string | null;
  longitude?: number | string | null;
  address?: string | null;
};

const SEARCH_COMPLEXES_PATH = '/api/v1/search/complexes';

export async function fetchComplexSearchResults(query: string): Promise<ComplexSearchResult[]> {
  const trimmedQuery = query.trim();
  if (trimmedQuery.length === 0) {
    return [];
  }

  const response = await fetch(
    resolveApiUrl(`${SEARCH_COMPLEXES_PATH}?${new URLSearchParams({ q: trimmedQuery })}`),
    {
      method: 'GET',
    },
  );

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch complex search results: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API complex search response: expected an array');
  }

  return payload.map((item) => normalizeComplexSearchResult(item as ComplexSearchResultResponse));
}

function normalizeComplexSearchResult(result: ComplexSearchResultResponse): ComplexSearchResult {
  return {
    complexId: toRequiredNumber(result.complexId, 'complexId'),
    complexName: toRequiredString(result.complexName, 'complexName'),
    parcelId: toRequiredNumber(result.parcelId, 'parcelId'),
    latitude: toNullableNumber(result.latitude, 'latitude'),
    longitude: toNullableNumber(result.longitude, 'longitude'),
    address: toNullableString(result.address),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex search response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex search response: ${field} must be a number`);
  }

  return parsed;
}

function toNullableNumber(value: unknown, field: string): number | null {
  if (value == null) {
    return null;
  }

  return toRequiredNumber(value, field);
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API complex search response: ${field} must be a non-empty string`);
  }

  return value;
}

function toNullableString(value: unknown): string | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'string') {
    return null;
  }

  return value.length > 0 ? value : null;
}
