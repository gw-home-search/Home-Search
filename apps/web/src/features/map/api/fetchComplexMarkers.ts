import { resolveApiUrl } from './resolveApiUrl';

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
    throw new Error(`Failed to fetch complex markers: ${response.status}`);
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    return [];
  }

  return payload.map((item) => normalizeComplexMarker(item as ComplexMarkerResponse));
}

function normalizeComplexMarker(marker: ComplexMarkerResponse): ComplexMarker {
  return {
    parcelId: toRequiredNumber(marker.parcelId ?? marker.id, 'parcelId'),
    lat: toRequiredNumber(marker.lat ?? marker.latitude, 'lat'),
    lng: toRequiredNumber(marker.lng ?? marker.longitude, 'lng'),
    latestDealAmount: toNullableNumber(marker.latestDealAmount),
    unitCntSum: toRequiredNumber(marker.unitCntSum, 'unitCntSum'),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid complex marker ${field}`);
  }

  return parsed;
}

function toNullableNumber(value: unknown): number | null {
  if (value == null) {
    return null;
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error('Invalid complex marker latestDealAmount');
  }

  return parsed;
}
