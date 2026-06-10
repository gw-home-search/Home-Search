import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type ComplexDetail = {
  parcelId: number;
  complexId: number | null;
  latitude: number | null;
  longitude: number | null;
  address: string;
  tradeName: string | null;
  name: string;
  dongCnt: number | null;
  unitCnt: number | null;
  platArea: number | null;
  archArea: number | null;
  totArea: number | null;
  bcRat: number | null;
  vlRat: number | null;
  useDate: string | null;
};

type ComplexDetailResponse = {
  parcelId?: number | string;
  complexId?: number | string | null;
  latitude?: number | string | null;
  longitude?: number | string | null;
  address?: string | null;
  tradeName?: string | null;
  name?: string | null;
  dongCnt?: number | string | null;
  unitCnt?: number | string | null;
  platArea?: number | string | null;
  archArea?: number | string | null;
  totArea?: number | string | null;
  bcRat?: number | string | null;
  vlRat?: number | string | null;
  useDate?: string | null;
};

const DETAIL_PATH = '/api/v1/detail';
const COMPLEX_PATH = '/api/v1/complex';

export async function fetchComplexDetail(
  parcelId: number,
  complexId?: number | null,
): Promise<ComplexDetail> {
  const response = await fetch(resolveApiUrl(scopedPath(`${DETAIL_PATH}/${parcelId}`, complexId)), {
    method: 'GET',
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch complex detail: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!isRecord(payload)) {
    throw new Error('Invalid public API complex detail response: expected an object');
  }

  return normalizeComplexDetail(payload);
}

export async function fetchComplexDetailByComplexId(complexId: number): Promise<ComplexDetail> {
  const response = await fetch(resolveApiUrl(`${COMPLEX_PATH}/${complexId}`), {
    method: 'GET',
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch complex detail: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!isRecord(payload)) {
    throw new Error('Invalid public API complex detail response: expected an object');
  }

  return normalizeComplexDetail(payload);
}

function normalizeComplexDetail(detail: ComplexDetailResponse): ComplexDetail {
  return {
    parcelId: toRequiredNumber(detail.parcelId, 'parcelId'),
    complexId: toNullableNumber(detail.complexId, 'complexId'),
    latitude: toNullableNumber(detail.latitude, 'latitude'),
    longitude: toNullableNumber(detail.longitude, 'longitude'),
    address: toRequiredString(detail.address, 'address'),
    tradeName: toNullableString(detail.tradeName),
    name: toRequiredString(detail.name, 'name'),
    dongCnt: toNullableNumber(detail.dongCnt, 'dongCnt'),
    unitCnt: toNullableNumber(detail.unitCnt, 'unitCnt'),
    platArea: toNullableNumber(detail.platArea, 'platArea'),
    archArea: toNullableNumber(detail.archArea, 'archArea'),
    totArea: toNullableNumber(detail.totArea, 'totArea'),
    bcRat: toNullableNumber(detail.bcRat, 'bcRat'),
    vlRat: toNullableNumber(detail.vlRat, 'vlRat'),
    useDate: toNullableString(detail.useDate),
  };
}

function scopedPath(path: string, complexId?: number | null): string {
  return complexId == null ? path : `${path}?complexId=${encodeURIComponent(complexId)}`;
}

function isRecord(value: unknown): value is ComplexDetailResponse {
  return typeof value === 'object' && value !== null;
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  return parsed;
}

function toNullableNumber(value: unknown, field: string): number | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a number`);
  }

  return parsed;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API complex detail response: ${field} must be a non-empty string`);
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
