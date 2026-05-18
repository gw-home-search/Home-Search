import { resolveApiUrl } from './resolveApiUrl';

export type RegionLevel = 'si-do' | 'si-gun-gu' | 'eup-myeon-dong';

export type RegionMarkersRequest = {
  swLat: number;
  swLng: number;
  neLat: number;
  neLng: number;
  region: RegionLevel;
};

export type RegionMarker = {
  id: number;
  name: string;
  lat: number;
  lng: number;
};

type RegionMarkerResponse = {
  id?: number | string;
  name?: string;
  regionName?: string;
  lat?: number | string;
  lng?: number | string;
  latitude?: number | string;
  longitude?: number | string;
  trend?: unknown;
};

const REGION_MARKERS_PATH = '/api/v1/map/regions';

export async function fetchRegionMarkers(request: RegionMarkersRequest): Promise<RegionMarker[]> {
  const response = await fetch(resolveApiUrl(REGION_MARKERS_PATH), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
  });

  if (!response.ok) {
    throw new Error(`Failed to fetch region markers: ${response.status}`);
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    return [];
  }

  return payload.map((item) => normalizeRegionMarker(item as RegionMarkerResponse));
}

function normalizeRegionMarker(marker: RegionMarkerResponse): RegionMarker {
  return {
    id: toRequiredNumber(marker.id, 'id'),
    name: toRequiredString(marker.name ?? marker.regionName, 'name'),
    lat: toRequiredNumber(marker.lat ?? marker.latitude, 'lat'),
    lng: toRequiredNumber(marker.lng ?? marker.longitude, 'lng'),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid region marker ${field}`);
  }

  return parsed;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid region marker ${field}`);
  }

  return value;
}
