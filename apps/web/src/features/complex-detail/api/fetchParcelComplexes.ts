import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type ParcelComplexSummary = {
  complexId: number;
  complexName: string;
  parcelId: number;
  latitude: number | null;
  longitude: number | null;
  address: string;
  dongCnt: number | null;
  unitCnt: number | null;
  useDate: string | null;
};

type ParcelComplexSummaryResponse = {
  complexId?: number | string;
  complexName?: string;
  parcelId?: number | string;
  latitude?: number | string | null;
  longitude?: number | string | null;
  address?: string | null;
  dongCnt?: number | string | null;
  unitCnt?: number | string | null;
  useDate?: string | null;
};

const DETAIL_PATH = '/api/v1/detail';

export async function fetchParcelComplexes(parcelId: number): Promise<ParcelComplexSummary[]> {
  const response = await fetch(resolveApiUrl(`${DETAIL_PATH}/${parcelId}/complexes`), {
    method: 'GET',
  });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch parcel complexes: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API parcel complexes response: expected an array');
  }

  return payload.map((item) => normalizeParcelComplex(item as ParcelComplexSummaryResponse));
}

function normalizeParcelComplex(item: ParcelComplexSummaryResponse): ParcelComplexSummary {
  return {
    complexId: toRequiredNumber(item.complexId, 'complexId'),
    complexName: toRequiredString(item.complexName, 'complexName'),
    parcelId: toRequiredNumber(item.parcelId, 'parcelId'),
    latitude: toNullableNumber(item.latitude, 'latitude'),
    longitude: toNullableNumber(item.longitude, 'longitude'),
    address: toRequiredString(item.address, 'address'),
    dongCnt: toNullableNumber(item.dongCnt, 'dongCnt'),
    unitCnt: toNullableNumber(item.unitCnt, 'unitCnt'),
    useDate: toNullableString(item.useDate),
  };
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API parcel complexes response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API parcel complexes response: ${field} must be a number`);
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
    throw new Error(`Invalid public API parcel complexes response: ${field} must be a non-empty string`);
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
