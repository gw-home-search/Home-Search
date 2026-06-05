import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type CoordinatePendingComplex = {
  parcelId: number;
  complexId: number;
  pnu: string;
  aptSeq: string | null;
  aptName: string;
  address: string | null;
  reason: string;
  tradeCount: number;
  createdAt: string;
};

export type CoordinatePendingReasonCode =
  | 'PNU_COORDINATE_MISSING'
  | 'SAME_PNU_MULTI_COMPLEX'
  | 'COMPLEX_DISPLAY_COORDINATE_MISSING';

export type CoordinatePendingSummary = {
  totalCount: number;
  reasonCounts: Record<CoordinatePendingReasonCode, number>;
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

export type CoordinatePendingRequest = {
  limit?: number;
  offset?: number;
  adminAccessCode?: string;
};

export async function fetchCoordinatePendingComplexes({
  limit = 50,
  offset = 0,
  adminAccessCode,
}: CoordinatePendingRequest = {}): Promise<CoordinatePendingComplex[]> {
  const searchParams = new URLSearchParams({
    limit: String(limit),
    offset: String(offset),
  });
  const response = await fetch(
    resolveApiUrl(`/api/v1/admin/coordinates/pending?${searchParams.toString()}`),
    {
      method: 'GET',
      headers: adminHeaders(adminAccessCode),
    },
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

export async function fetchCoordinatePendingSummary(
  adminAccessCode?: string,
): Promise<CoordinatePendingSummary> {
  const response = await fetch(
    resolveApiUrl('/api/v1/admin/coordinates/pending/summary'),
    {
      method: 'GET',
      headers: adminHeaders(adminAccessCode),
    },
  );
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to fetch coordinate pending summary: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return normalizePendingSummary(await response.json());
}

export async function approveCoordinateOverride(
  pnu: string,
  request: CoordinateOverrideApprovalRequest,
  adminAccessCode?: string,
): Promise<CoordinateOverrideApprovalResult> {
  const response = await fetch(
    resolveApiUrl(`/api/v1/admin/coordinates/${encodeURIComponent(pnu)}/override`),
    {
      method: 'PUT',
      headers: adminHeaders(adminAccessCode, { 'Content-Type': 'application/json' }),
      body: JSON.stringify(request),
    },
  );
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to approve coordinate override: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return normalizeApprovalResult(await response.json());
}

function adminHeaders(adminAccessCode: string | undefined, baseHeaders: Record<string, string> = {}): Record<string, string> {
  if (!adminAccessCode) {
    return baseHeaders;
  }
  return {
    ...baseHeaders,
    'X-Admin-Access-Code': adminAccessCode,
  };
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
    reason: requiredString(value.reason, 'reason'),
    tradeCount: requiredNumber(value.tradeCount, 'tradeCount'),
    createdAt: requiredString(value.createdAt, 'createdAt'),
  };
}

function normalizePendingSummary(value: unknown): CoordinatePendingSummary {
  if (!isRecord(value)) {
    throw new Error('Invalid admin coordinate pending summary response: expected an object');
  }
  if (!isRecord(value.reasonCounts)) {
    throw new Error('Invalid admin coordinate pending summary response: reasonCounts must be an object');
  }

  return {
    totalCount: requiredNumber(value.totalCount, 'totalCount'),
    reasonCounts: {
      PNU_COORDINATE_MISSING: requiredNumber(value.reasonCounts.PNU_COORDINATE_MISSING, 'reasonCounts.PNU_COORDINATE_MISSING'),
      SAME_PNU_MULTI_COMPLEX: requiredNumber(value.reasonCounts.SAME_PNU_MULTI_COMPLEX, 'reasonCounts.SAME_PNU_MULTI_COMPLEX'),
      COMPLEX_DISPLAY_COORDINATE_MISSING: requiredNumber(value.reasonCounts.COMPLEX_DISPLAY_COORDINATE_MISSING, 'reasonCounts.COMPLEX_DISPLAY_COORDINATE_MISSING'),
    },
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
