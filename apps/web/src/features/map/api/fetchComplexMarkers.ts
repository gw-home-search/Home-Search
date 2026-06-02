import { resolveApiUrl } from './resolveApiUrl';
import { readProblemDetail } from './readProblemDetail';

export type ComplexMarkersRequest = {
  swLat: number;
  swLng: number;
  neLat: number;
  neLng: number;
  pyeongMin?: number | null;
  pyeongMax?: number | null;
  priceEokMin?: number | null;
  priceEokMax?: number | null;
  ageMin?: number | null;
  ageMax?: number | null;
  unitMin?: number | null;
  unitMax?: number | null;
};

export type ComplexMarker = {
  parcelId: number;
  complexId: number | null;
  lat: number;
  lng: number;
  latestDealAmount: number | null;
  unitCntSum: number;
};

type ComplexMarkerResponse = Partial<ComplexMarker> & {
  id?: number | string;
  latitude?: number | string;
  longitude?: number | string;
};

const COMPLEX_MARKERS_PATH = '/api/v1/map/complexes';

export async function fetchComplexMarkers(request: ComplexMarkersRequest): Promise<ComplexMarker[]> {
  const response = await fetch(resolveApiUrl(COMPLEX_MARKERS_PATH), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch complex markers: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API complex marker response: expected an array');
  }

  return payload.map((item) => normalizeComplexMarker(item as ComplexMarkerResponse));
}

function normalizeComplexMarker(marker: ComplexMarkerResponse): ComplexMarker {
  return {
    parcelId: toRequiredNumber(marker.parcelId ?? marker.id, 'parcelId'),
    complexId: toNullableNumber(marker.complexId, 'complexId'),
    lat: toRequiredNumber(marker.lat ?? marker.latitude, 'lat'),
    lng: toRequiredNumber(marker.lng ?? marker.longitude, 'lng'),
    latestDealAmount: toNullableNumber(marker.latestDealAmount),
    unitCntSum: toRequiredNumber(marker.unitCntSum, 'unitCntSum'),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex marker response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex marker response: ${field} must be a number`);
  }

  return parsed;
}

function toNullableNumber(value: unknown, field = 'latestDealAmount'): number | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex marker response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex marker response: ${field} must be a number`);
  }

  return parsed;
}
