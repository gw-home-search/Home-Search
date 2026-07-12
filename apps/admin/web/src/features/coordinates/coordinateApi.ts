import { adminRequest } from '../../shared/http/adminHttp';

export type CoordinatePendingReason =
  | 'PNU_COORDINATE_MISSING'
  | 'SAME_PNU_MULTI_COMPLEX'
  | 'COMPLEX_DISPLAY_COORDINATE_MISSING';

export type CoordinatePendingComplex = {
  parcelId: number;
  complexId: number;
  pnu: string;
  aptSeq: string | null;
  aptName: string;
  address: string | null;
  reason: CoordinatePendingReason;
  tradeCount: number;
  createdAt: string;
};

export type CoordinatePendingSummary = {
  totalCount: number;
  reasonCounts: Record<CoordinatePendingReason, number>;
};

export function fetchCoordinatePending(limit = 50, offset = 0) {
  return adminRequest<CoordinatePendingComplex[]>(`/api/v1/admin/coordinates/pending?limit=${limit}&offset=${offset}`);
}

export function fetchCoordinateSummary() {
  return adminRequest<CoordinatePendingSummary>('/api/v1/admin/coordinates/pending/summary');
}

export function overrideCoordinate(pnu: string, request: { latitude: number; longitude: number; reason: string }) {
  return adminRequest<{ pnu: string; latitude: number; longitude: number; parcelUpdated: boolean }>(
    `/api/v1/admin/coordinates/${encodeURIComponent(pnu)}/override`,
    { method: 'PUT', body: JSON.stringify(request) },
  );
}
