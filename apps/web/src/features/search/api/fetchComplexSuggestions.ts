import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type ComplexSuggestion = {
  complexId: number;
  complexName: string;
  parcelId: number;
  address: string;
};

type ComplexSuggestionResponse = {
  complexId?: number | string;
  complexName?: string;
  parcelId?: number | string;
  address?: string;
};

const SEARCH_SUGGESTIONS_PATH = '/api/v1/search/complexes/suggestions';

export async function fetchComplexSuggestions(query: string): Promise<ComplexSuggestion[]> {
  const trimmedQuery = query.trim();
  if (trimmedQuery.length === 0) {
    return [];
  }

  const response = await fetch(
    resolveApiUrl(`${SEARCH_SUGGESTIONS_PATH}?${new URLSearchParams({ q: trimmedQuery })}`),
    {
      method: 'GET',
    },
  );

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch complex suggestions: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
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
    address: toRequiredString(item.address, 'address'),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex suggestion response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex suggestion response: ${field} must be a number`);
  }

  return parsed;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API complex suggestion response: ${field} must be a non-empty string`);
  }

  return value;
}
