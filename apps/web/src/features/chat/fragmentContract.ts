import type { ChatAction } from './actionContract';
import type { ChatArtifact } from './artifactContract';

export type ChatFragment = {
  fragmentId: string;
  capability: string;
  status: 'success' | 'failed';
  answer: string;
  factIds: string[];
  artifactIds: string[];
  actionIds: string[];
  limitations: string[];
};

export function readChatFragments(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
  artifacts: readonly ChatArtifact[],
  actions: readonly ChatAction[],
): ChatFragment[] {
  if (!Array.isArray(value) || value.length > 4) return [];
  const artifactIds = new Set(artifacts.map(({ artifactId }) => artifactId));
  const actionIds = new Set(actions.map(({ actionId }) => actionId));
  const fragments = value.flatMap((item) => {
    if (!isRecord(item)
      || !hasExactKeys(item, [
        'fragmentId', 'capability', 'status', 'answer', 'factIds',
        'artifactIds', 'actionIds', 'limitations',
      ])
      || !isIdentifier(item.fragmentId)
      || !isIdentifier(item.capability)
      || (item.status !== 'success' && item.status !== 'failed')
      || !isText(item.answer, 20_000)
      || !isIdentifiers(item.factIds, allowedFactIds)
      || !isIdentifiers(item.artifactIds, artifactIds)
      || !isIdentifiers(item.actionIds, actionIds)
      || !Array.isArray(item.limitations)
      || item.limitations.length > 50
      || !item.limitations.every((text) => isText(text, 2_000))) return [];
    return [{
      fragmentId: item.fragmentId,
      capability: item.capability,
      status: item.status as 'success' | 'failed',
      answer: item.answer.trim(),
      factIds: item.factIds,
      artifactIds: item.artifactIds,
      actionIds: item.actionIds,
      limitations: item.limitations.map((text) => text.trim()),
    }];
  });
  if (fragments.length !== value.length
    || new Set(fragments.map(({ fragmentId }) => fragmentId)).size !== fragments.length) return [];
  return fragments;
}

function isIdentifiers(
  value: unknown,
  allowed: ReadonlySet<string>,
): value is string[] {
  return Array.isArray(value)
    && value.length <= 100
    && value.every((item) => isIdentifier(item) && allowed.has(item))
    && new Set(value).size === value.length;
}

function isText(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximum;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every((key) => key in value);
}
