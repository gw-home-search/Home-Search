import type { ChatAction } from './actionContract';
import type { ChatArtifact } from './artifactContract';

export type ChatUiReportText = { text: string; factIds: string[] };

export type ChatUiReport = {
  version: 1;
  kind: 'RECOMMENDATION' | 'COMPARISON' | 'RECENT_TRADE' | 'PRICE_TREND'
    | 'PROPERTY_OVERVIEW' | 'PARTIAL' | 'GENERAL';
  opening: ChatUiReportText;
  basis: ChatUiReportText[];
  primaryArtifactId: string | null;
  highlights: Array<{
    complexId: number;
    title: string;
    body: string;
    factIds: string[];
  }>;
  detailArtifactIds: string[];
  actionIds: string[];
};

export function readChatUiReport(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
  artifacts: readonly ChatArtifact[],
  actions: readonly ChatAction[],
): ChatUiReport | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'version', 'kind', 'opening', 'basis', 'primaryArtifactId',
      'highlights', 'detailArtifactIds', 'actionIds',
    ])
    || value.version !== 1
    || !isReportKind(value.kind)
    || !Array.isArray(value.basis)
    || value.basis.length > 8
    || !Array.isArray(value.highlights)
    || value.highlights.length > 2
    || !Array.isArray(value.detailArtifactIds)
    || value.detailArtifactIds.length > 5
    || !Array.isArray(value.actionIds)
    || value.actionIds.length > 10) return null;
  const opening = readText(value.opening, allowedFactIds);
  const basis = value.basis.flatMap((item) => {
    const parsed = readText(item, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  const highlights = value.highlights.flatMap((item) => {
    if (!isRecord(item)
      || !hasExactKeys(item, ['complexId', 'title', 'body', 'factIds'])
      || !isSafeInteger(item.complexId)
      || !isText(item.title, 100)
      || !isText(item.body, 1_000)
      || !isFactIds(item.factIds, allowedFactIds)) return [];
    return [{
      complexId: item.complexId,
      title: item.title.trim(),
      body: item.body.trim(),
      factIds: item.factIds,
    }];
  });
  const artifactIds = new Set(artifacts.map(({ artifactId }) => artifactId));
  const actionIds = new Set(actions.map(({ actionId }) => actionId));
  const primaryArtifactId = value.primaryArtifactId;
  if (opening == null
    || basis.length !== value.basis.length
    || highlights.length !== value.highlights.length
    || (primaryArtifactId !== null
      && (!isIdentifier(primaryArtifactId) || !artifactIds.has(primaryArtifactId)))
    || !value.detailArtifactIds.every(
      (id) => isIdentifier(id) && artifactIds.has(id) && id !== primaryArtifactId,
    )
    || new Set(value.detailArtifactIds).size !== value.detailArtifactIds.length
    || !value.actionIds.every((id) => isIdentifier(id) && actionIds.has(id))
    || new Set(value.actionIds).size !== value.actionIds.length) return null;
  return {
    version: 1,
    kind: value.kind,
    opening,
    basis,
    primaryArtifactId,
    highlights,
    detailArtifactIds: value.detailArtifactIds,
    actionIds: value.actionIds,
  };
}

function readText(value: unknown, factIds: ReadonlySet<string>): ChatUiReportText | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['text', 'factIds'])
    || !isText(value.text, 2_000)
    || !isFactIds(value.factIds, factIds)) return null;
  return { text: value.text.trim(), factIds: value.factIds };
}

function isReportKind(value: unknown): value is ChatUiReport['kind'] {
  return value === 'RECOMMENDATION' || value === 'COMPARISON'
    || value === 'RECENT_TRADE' || value === 'PRICE_TREND'
    || value === 'PROPERTY_OVERVIEW' || value === 'PARTIAL' || value === 'GENERAL';
}

function isFactIds(value: unknown, allowed: ReadonlySet<string>): value is string[] {
  return Array.isArray(value)
    && value.length > 0
    && value.length <= 100
    && new Set(value).size === value.length
    && value.every((id) => isIdentifier(id) && allowed.has(id));
}

function isText(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= max;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isSafeInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...keys].sort();
  return actual.length === expected.length && actual.every((key, index) => key === expected[index]);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
