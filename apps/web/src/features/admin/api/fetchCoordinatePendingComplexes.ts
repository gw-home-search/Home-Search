import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type CoordinatePendingComplex = {
  parcelId: number;
  complexId: number;
  pnu: string;
  aptSeq: string | null;
  aptName: string;
  address: string | null;
  tradeCount: number;
  createdAt: string;
};

export type CoordinateOverrideApprovalRequest = {
  latitude: number;
  longitude: number;
  reason: string;
  approvedBy: string;
};

export type CoordinateOverrideApprovalResult = {
  pnu: string;
  latitude: number;
  longitude: number;
  parcelUpdated: boolean;
};

export async function fetchCoordinatePendingComplexes(
  limit = 50,
): Promise<CoordinatePendingComplex[]> {
  const response = await fetch(
    resolveApiUrl(`/api/v1/admin/coordinates/pending?limit=${encodeURIComponent(limit)}`),
    { method: 'GET' },
  );
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to fetch coordinate pending complexes: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  const payload: unknown = await response.json();
  if (!Array.isArray(payload)) {
    throw new Error('Invalid admin coordinate pending response: expected an array');
  }
  return payload.map(normalizePendingComplex);
}

export async function approveCoordinateOverride(
  pnu: string,
  request: CoordinateOverrideApprovalRequest,
): Promise<CoordinateOverrideApprovalResult> {
  const response = await fetch(
    resolveApiUrl(`/api/v1/admin/coordinates/${encodeURIComponent(pnu)}/override`),
    {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to approve coordinate override: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return normalizeApprovalResult(await response.json());
}

function normalizePendingComplex(value: unknown): CoordinatePendingComplex {
  if (!isRecord(value)) {
    throw new Error('Invalid admin coordinate pending response: item must be an object');
  }

  return {
    parcelId: requiredNumber(value.parcelId, 'parcelId'),
    complexId: requiredNumber(value.complexId, 'complexId'),
    pnu: requiredString(value.pnu, 'pnu'),
    aptSeq: nullableString(value.aptSeq, 'aptSeq'),
    aptName: requiredString(value.aptName, 'aptName'),
    address: nullableString(value.address, 'address'),
    tradeCount: requiredNumber(value.tradeCount, 'tradeCount'),
    createdAt: requiredString(value.createdAt, 'createdAt'),
  };
}

function normalizeApprovalResult(value: unknown): CoordinateOverrideApprovalResult {
  if (!isRecord(value)) {
    throw new Error('Invalid admin coordinate override response: expected an object');
  }

  return {
    pnu: requiredString(value.pnu, 'pnu'),
    latitude: requiredNumber(value.latitude, 'latitude'),
    longitude: requiredNumber(value.longitude, 'longitude'),
    parcelUpdated: requiredBoolean(value.parcelUpdated, 'parcelUpdated'),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function requiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid admin coordinate response: ${field} must be a number`);
  }
  return value;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim().length === 0) {
    throw new Error(`Invalid admin coordinate response: ${field} must be a string`);
  }
  return value;
}

function nullableString(value: unknown, field: string): string | null {
  if (value == null) {
    return null;
  }
  if (typeof value !== 'string') {
    throw new Error(`Invalid admin coordinate response: ${field} must be a string`);
  }
  return value;
}

function requiredBoolean(value: unknown, field: string): boolean {
  if (typeof value !== 'boolean') {
    throw new Error(`Invalid admin coordinate response: ${field} must be a boolean`);
  }
  return value;
}
