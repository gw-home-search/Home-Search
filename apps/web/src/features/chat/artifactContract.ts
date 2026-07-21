export type FactListArtifactItem = {
  label: string;
  value: string;
  factIds: string[];
};

export type FactListArtifact = {
  type: 'factList';
  version: 1;
  artifactId: string;
  title: string;
  items: FactListArtifactItem[];
};

export type ChatArtifact = FactListArtifact;

const MAX_ARTIFACT_BYTES = 65_536;

export function readChatArtifacts(value: unknown, allowedFactIds: ReadonlySet<string>): ChatArtifact[] {
  if (!Array.isArray(value) || value.length > 8) return [];
  try {
    if (new TextEncoder().encode(JSON.stringify(value)).byteLength > MAX_ARTIFACT_BYTES) return [];
  } catch {
    return [];
  }
  return value.flatMap((candidate) => {
    const artifact = readFactListArtifact(candidate, allowedFactIds);
    return artifact == null ? [] : [artifact];
  });
}

function readFactListArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): FactListArtifact | null {
  if (!isRecord(value)
    || value.type !== 'factList'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || !Array.isArray(value.items)
    || value.items.length === 0
    || value.items.length > 10) return null;
  const items = value.items.flatMap((candidate) => {
    const item = readFactListItem(candidate, allowedFactIds);
    return item == null ? [] : [item];
  });
  if (items.length !== value.items.length) return null;
  return {
    type: 'factList',
    version: 1,
    artifactId: value.artifactId,
    title: value.title.trim(),
    items,
  };
}

function readFactListItem(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): FactListArtifactItem | null {
  if (!isRecord(value)
    || !isDisplayText(value.label, 100)
    || !isDisplayText(value.value, 2_000)
    || !Array.isArray(value.factIds)
    || value.factIds.length === 0
    || value.factIds.length > 100
    || !value.factIds.every((factId) => isIdentifier(factId) && allowedFactIds.has(factId))
    || new Set(value.factIds).size !== value.factIds.length) return null;
  return {
    label: value.label.trim(),
    value: value.value.trim(),
    factIds: value.factIds,
  };
}

function isDisplayText(value: unknown, maximumLength: number): value is string {
  return typeof value === 'string' && value.trim().length > 0 && value.length <= maximumLength;
}

function isIdentifier(value: unknown): value is string {
  return typeof value === 'string' && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/.test(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
