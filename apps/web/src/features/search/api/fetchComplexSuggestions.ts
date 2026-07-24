import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { readValidatedJson, requestFailureFromResponse } from '../../../shared/http/requestFailure';
import { fetchWithTimeout } from '../../../shared/http/fetchWithTimeout';

export type ComplexSuggestion = {
  complexId: number;
  complexName: string;
  parcelId: number;
  address: string | null;
};

type ComplexSuggestionResponse = {
  complexId?: number | string;
  complexName?: string;
  parcelId?: number | string;
  address?: string | null;
};

const SEARCH_SUGGESTIONS_PATH = '/api/v1/search/complexes/suggestions';

export async function fetchComplexSuggestions(
  query: string,
  signal?: AbortSignal,
): Promise<ComplexSuggestion[]> {
  const trimmedQuery = query.trim();
  if (trimmedQuery.length === 0) {
    return [];
  }

  const response = await fetchWithTimeout(
    resolveApiUrl(`${SEARCH_SUGGESTIONS_PATH}?${new URLSearchParams({ q: trimmedQuery })}`),
    {
      method: 'GET',
      signal,
    },
  );

  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'complex-suggestions',
    });
  }

  return readValidatedJson(response, {
    service: 'property-data',
    operation: 'complex-suggestions',
  }, normalizeComplexSuggestions);
}

function normalizeComplexSuggestions(payload: unknown): ComplexSuggestion[] {
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API complex suggestion response: expected an array');
  }

  return payload.map((item) => normalizeComplexSuggestion(item as ComplexSuggestionResponse));
}

function normalizeComplexSuggestion(item: ComplexSuggestionResponse): ComplexSuggestion {
  return {
    complexId: toRequiredNumber(item.complexId, 'complexId'),
    complexName: toRequiredString(item.complexName, 'complexName'),
    parcelId: toRequiredNumber(item.parcelId, 'parcelId'),
    address: toNullableString(item.address),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid public API complex suggestion response: ${field} must be a number`);
  }

  return value;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API complex suggestion response: ${field} must be a non-empty string`);
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
