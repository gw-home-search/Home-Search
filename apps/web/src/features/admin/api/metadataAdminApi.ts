import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type MetadataPending = {
  complexId: number; aptName: string; aptSeq: string | null; canonicalPnu: string; address: string | null;
  status: string; failureKind: string | null; failureReason: string | null; attempts: number;
  nextAttemptAt: string | null; holdAt: string | null; holdReason: string | null;
};
export type MetadataSummary = { totalCount: number; statusCounts: Record<string, number> };
export type MetadataAlias = {
  id: number; canonicalPrefix: string; sourcePrefix: string; status: string; reason: string | null;
  approvedBy: string | null; approvedAt: string | null; disabledBy: string | null; disabledAt: string | null;
};
export type AdminDecision = { actor: string; reason: string };
export type VersionedAdminDecision = AdminDecision & { expectedStateVersion: number };
export type BuildingMetadataPending = {
  complexId: number; aptSeq: string | null; canonicalName: string; tradeName: string | null; pnu: string;
  status: string; failureKind: string | null; failureReason: string | null; attempts: number;
  currentEvaluationId: number | null; pendingEvaluationId: number | null; stateVersion: number;
  nextAttemptAt: string | null;
};
export type BuildingMetadataDetail = {
  complex: BuildingMetadataPending;
  internalCandidates: Array<{
    complexId: number; aptSeq: string | null; canonicalName: string; tradeName: string | null;
    aliases: Array<{ id: number; aliasType: string; aliasName: string; source: string | null; sourceKey: string | null }>;
  }>;
  identities: Array<{ id: number; complexId: number; source: string; sourceKey: string; pnu: string; matchPath: string; active: boolean }>;
  evaluations: Array<{
    id: number; snapshotId: number; sourceKind: string; policyVersion: string; status: string;
    failureKind: string | null; failureReason: string | null; sourceKeys: string[]; sourceNames: string[];
    selectedComplexId: number | null; matchPath: string | null; values: Record<string, unknown>;
    rawBody: string | null; bodyByteSize: number; payloadOversized: boolean;
  }>;
  comparisons: Array<{ field: string; currentValue: string | null; candidateValue: string | null; conflict: boolean }>;
  recommendations: Array<{ complexId: number; name: string; score: number }>;
  decisions: Array<{ decisionType: string; actor: string; reason: string; stateVersionBefore: number; createdAt: string }>;
};

const headers = (code?: string, json = false): Record<string, string> => ({
  ...(json ? { 'Content-Type': 'application/json' } : {}),
  ...(code ? { 'X-Admin-Access-Code': code } : {}),
});

async function request(path: string, code?: string, init?: RequestInit): Promise<unknown> {
  const response = await fetch(resolveApiUrl(path), { ...init, headers: headers(code, init?.body != null) });
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Metadata admin request failed: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return response.json();
}

export async function fetchMetadataPending(code?: string): Promise<MetadataPending[]> {
  const value = await request('/api/v1/admin/metadata/pending?limit=50&offset=0', code);
  if (!Array.isArray(value)) throw new Error('Invalid metadata pending response');
  return value as MetadataPending[];
}
export async function fetchMetadataSummary(code?: string): Promise<MetadataSummary> {
  return await request('/api/v1/admin/metadata/pending/summary', code) as MetadataSummary;
}
export async function fetchMetadataAliases(code?: string): Promise<MetadataAlias[]> {
  const value = await request('/api/v1/admin/metadata/pnu-aliases', code);
  if (!Array.isArray(value)) throw new Error('Invalid metadata alias response');
  return value as MetadataAlias[];
}
export async function retryMetadata(complexId: number, decision: AdminDecision, code?: string): Promise<void> {
  await request(`/api/v1/admin/metadata/${complexId}/retry`, code, { method: 'POST', body: JSON.stringify(decision) });
}
export async function holdMetadata(complexId: number, decision: AdminDecision, code?: string): Promise<void> {
  await request(`/api/v1/admin/metadata/${complexId}/hold`, code, { method: 'POST', body: JSON.stringify(decision) });
}
export async function proposeMetadataAlias(
  proposal: AdminDecision & { canonicalPrefix: string; sourcePrefix: string }, code?: string,
): Promise<void> {
  await request('/api/v1/admin/metadata/pnu-aliases', code, { method: 'POST', body: JSON.stringify(proposal) });
}
export async function changeMetadataAlias(
  aliasId: number, action: 'approve' | 'disable', decision: AdminDecision, code?: string,
): Promise<void> {
  await request(`/api/v1/admin/metadata/pnu-aliases/${aliasId}/${action}`, code, {
    method: 'POST', body: JSON.stringify(decision),
  });
}

export async function fetchBuildingMetadataPending(code?: string): Promise<BuildingMetadataPending[]> {
  const value = await request('/api/v1/admin/metadata/building/pending?limit=50&offset=0', code);
  if (!Array.isArray(value)) throw new Error('Invalid building metadata pending response');
  return value as BuildingMetadataPending[];
}
export async function fetchBuildingMetadataDetail(complexId: number, code?: string): Promise<BuildingMetadataDetail> {
  return await request(`/api/v1/admin/metadata/building/${complexId}`, code) as BuildingMetadataDetail;
}
export async function decideBuildingMetadata(
  complexId: number, action: 'retry' | 'hold', decision: VersionedAdminDecision, code?: string,
): Promise<void> {
  await request(`/api/v1/admin/metadata/building/${complexId}/${action}`, code, {
    method: 'POST', body: JSON.stringify(decision),
  });
}
export async function linkBuildingMetadataIdentity(
  complexId: number, source: 'ODC_COMPLEX_PK' | 'BLD_MGM_BLD_RGST_PK', sourceKey: string,
  decision: VersionedAdminDecision, code?: string,
): Promise<void> {
  await request(`/api/v1/admin/metadata/building/${complexId}/identities`, code, {
    method: 'POST', body: JSON.stringify({ source, sourceKey, ...decision }),
  });
}
export async function approveBuildingMetadataAlias(
  complexId: number, aliasType: 'ADMIN_ALIAS' | 'BUILDING_REGISTER_NAME', aliasName: string,
  decision: VersionedAdminDecision, code?: string,
): Promise<void> {
  await request(`/api/v1/admin/metadata/building/${complexId}/aliases`, code, {
    method: 'POST', body: JSON.stringify({ aliasType, aliasName, ...decision }),
  });
}
export async function decideBuildingMetadataChange(
  complexId: number, evaluationId: number, action: 'approve' | 'reject',
  decision: VersionedAdminDecision, code?: string,
): Promise<void> {
  await request(`/api/v1/admin/metadata/building/${complexId}/changes/${evaluationId}/${action}`, code, {
    method: 'POST', body: JSON.stringify(decision),
  });
}
