import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type RegionSummary = {
  id: number;
  name: string;
};

export type RegionDetail = RegionSummary & {
  latitude: number;
  longitude: number;
  children: RegionSummary[];
};

type RegionSummaryResponse = {
  id?: number | string;
  name?: string;
};

type RegionDetailResponse = RegionSummaryResponse & {
  latitude?: number | string;
  longitude?: number | string;
  children?: unknown;
};

const REGION_PATH = '/api/v1/region';

export async function fetchRootRegions(): Promise<RegionSummary[]> {
  const response = await fetch(resolveApiUrl(REGION_PATH), {
    method: 'GET',
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to fetch root regions: ${response.status}${detail ? ` ${detail}` : ''}`);
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API root region response: expected an array');
  }

  return payload.map((item) => normalizeRegionSummary(item as RegionSummaryResponse, 'root region'));
}

export async function fetchRegionDetail(regionId: number): Promise<RegionDetail> {
  const response = await fetch(resolveApiUrl(`${REGION_PATH}/${regionId}`), {
    method: 'GET',
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch region detail: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!isRecord(payload)) {
    throw new Error('Invalid public API region detail response: expected an object');
  }

  return normalizeRegionDetail(payload);
}

function normalizeRegionDetail(detail: RegionDetailResponse): RegionDetail {
  if (!Array.isArray(detail.children)) {
    throw new Error('Invalid public API region detail response: children must be an array');
  }

  return {
    ...normalizeRegionSummary(detail, 'region detail'),
    latitude: toRequiredNumber(detail.latitude, 'latitude', 'region detail'),
    longitude: toRequiredNumber(detail.longitude, 'longitude', 'region detail'),
    children: detail.children.map((child) =>
      normalizeRegionSummary(child as RegionSummaryResponse, 'region child'),
    ),
  };
}

function normalizeRegionSummary(
  region: RegionSummaryResponse,
  responseName: 'root region' | 'region detail' | 'region child',
): RegionSummary {
  return {
    id: toRequiredNumber(region.id, 'id', responseName),
    name: toRequiredString(region.name, 'name', responseName),
  };
}

function isRecord(value: unknown): value is RegionDetailResponse {
  return typeof value === 'object' && value !== null;
}

function toRequiredNumber(
  value: unknown,
  field: string,
  responseName: 'root region' | 'region detail' | 'region child',
): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API ${responseName} response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API ${responseName} response: ${field} must be a number`);
  }

  return parsed;
}

function toRequiredString(
  value: unknown,
  field: string,
  responseName: 'root region' | 'region detail' | 'region child',
): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API ${responseName} response: ${field} must be a non-empty string`);
  }

  return value;
}
