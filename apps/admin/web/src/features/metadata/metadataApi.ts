import { adminRequest } from '../../shared/http/adminHttp';

export type MetadataPending = {
  complexId: number;
  aptName: string;
  aptSeq: string | null;
  canonicalPnu: string;
  address: string | null;
  status: string;
  failureKind: string | null;
  failureReason: string | null;
  attempts: number;
  nextAttemptAt: string | null;
  holdAt: string | null;
  holdReason: string | null;
};

export type MetadataSummary = { totalCount: number; statusCounts: Record<string, number> };
export type MetadataAlias = {
  id: number;
  canonicalPrefix: string;
  sourcePrefix: string;
  status: string;
  reason: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  disabledBy: string | null;
  disabledAt: string | null;
};

export function fetchMetadataPending() { return adminRequest<MetadataPending[]>('/api/v1/admin/metadata/pending?limit=50&offset=0'); }
export function fetchMetadataSummary() { return adminRequest<MetadataSummary>('/api/v1/admin/metadata/pending/summary'); }
export function fetchMetadataAliases() { return adminRequest<MetadataAlias[]>('/api/v1/admin/metadata/pnu-aliases'); }
export function retryMetadata(complexId: number, reason: string) {
  return adminRequest<void>(`/api/v1/admin/metadata/${complexId}/retry`, { method: 'POST', body: JSON.stringify({ reason }) });
}
export function holdMetadata(complexId: number, reason: string) {
  return adminRequest<void>(`/api/v1/admin/metadata/${complexId}/hold`, { method: 'POST', body: JSON.stringify({ reason }) });
}
export function proposeMetadataAlias(request: { canonicalPrefix: string; sourcePrefix: string; reason: string }) {
  return adminRequest<void>('/api/v1/admin/metadata/pnu-aliases', { method: 'POST', body: JSON.stringify(request) });
}
export function changeMetadataAlias(aliasId: number, action: 'approve' | 'disable', reason: string) {
  return adminRequest<void>(`/api/v1/admin/metadata/pnu-aliases/${aliasId}/${action}`, { method: 'POST', body: JSON.stringify({ reason }) });
}
