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

export type ComparisonTableCell = {
  availability: 'available' | 'unavailable';
  value: string | number | null;
  unit: string;
  reason: string | null;
  factIds: string[];
};

export type ComparisonTableArtifact = {
  type: 'comparisonTable';
  version: 1;
  artifactId: string;
  title: string;
  columns: Array<{ key: string; label: string; factIds: string[] }>;
  rows: Array<{ key: string; label: string; cells: ComparisonTableCell[] }>;
  basis: {
    cutoffDate: string;
    startDate: string;
    exclusiveAreaSquareMeters: number;
  };
};

export type ChatArtifact = FactListArtifact | ComparisonTableArtifact;

const MAX_ARTIFACT_BYTES = 65_536;

export function readChatArtifacts(value: unknown, allowedFactIds: ReadonlySet<string>): ChatArtifact[] {
  if (!Array.isArray(value) || value.length > 8) return [];
  try {
    if (new TextEncoder().encode(JSON.stringify(value)).byteLength > MAX_ARTIFACT_BYTES) return [];
  } catch {
    return [];
  }
  return value.flatMap((candidate) => {
    const artifact = readFactListArtifact(candidate, allowedFactIds)
      ?? readComparisonTableArtifact(candidate, allowedFactIds);
    return artifact == null ? [] : [artifact];
  });
}

function readComparisonTableArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): ComparisonTableArtifact | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'artifactId', 'title', 'columns', 'rows', 'basis',
    ])
    || value.type !== 'comparisonTable'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || !Array.isArray(value.columns)
    || value.columns.length < 2
    || value.columns.length > 4
    || !Array.isArray(value.rows)
    || value.rows.length === 0
    || value.rows.length > 12
    || !isRecord(value.basis)) return null;
  const columns = value.columns.flatMap((column) => {
    if (!isRecord(column)
      || !hasExactKeys(column, ['key', 'label', 'factIds'])
      || !isIdentifier(column.key)
      || !isDisplayText(column.label, 100)
      || !isFactIds(column.factIds, allowedFactIds, false)) return [];
    return [{ key: column.key, label: column.label.trim(), factIds: column.factIds }];
  });
  if (columns.length !== value.columns.length
    || new Set(columns.map(({ key }) => key)).size !== columns.length) return null;
  const rows = value.rows.flatMap((row) => {
    if (!isRecord(row)
      || !hasExactKeys(row, ['key', 'label', 'cells'])
      || !isIdentifier(row.key)
      || !isDisplayText(row.label, 100)
      || !Array.isArray(row.cells)
      || row.cells.length !== columns.length) return [];
    const cells = row.cells.flatMap((cell) => {
      const parsed = readComparisonCell(cell, allowedFactIds);
      return parsed == null ? [] : [parsed];
    });
    return cells.length === columns.length
      ? [{ key: row.key, label: row.label.trim(), cells }]
      : [];
  });
  const basis = value.basis;
  if (rows.length !== value.rows.length
    || new Set(rows.map(({ key }) => key)).size !== rows.length
    || !hasExactKeys(basis, ['cutoffDate', 'startDate', 'exclusiveAreaSquareMeters'])
    || !isIsoDate(basis.cutoffDate)
    || !isIsoDate(basis.startDate)
    || typeof basis.exclusiveAreaSquareMeters !== 'number'
    || !Number.isFinite(basis.exclusiveAreaSquareMeters)
    || basis.exclusiveAreaSquareMeters <= 0
    || basis.exclusiveAreaSquareMeters > 1000) return null;
  return {
    type: 'comparisonTable',
    version: 1,
    artifactId: value.artifactId,
    title: value.title.trim(),
    columns,
    rows,
    basis: {
      cutoffDate: basis.cutoffDate,
      startDate: basis.startDate,
      exclusiveAreaSquareMeters: basis.exclusiveAreaSquareMeters,
    },
  };
}

function readComparisonCell(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): ComparisonTableCell | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['availability', 'value', 'unit', 'reason', 'factIds'])
    || (value.availability !== 'available' && value.availability !== 'unavailable')
    || !isDisplayText(value.unit, 100)
    || !isFactIds(value.factIds, allowedFactIds, true)) return null;
  if (value.availability === 'available') {
    if ((typeof value.value !== 'string' && typeof value.value !== 'number')
      || (typeof value.value === 'string' && !isDisplayText(value.value, 2_000))
      || (typeof value.value === 'number' && !Number.isFinite(value.value))
      || value.reason !== null
      || value.factIds.length === 0) return null;
  } else if (value.value !== null || !isDisplayText(value.reason, 2_000)) {
    return null;
  }
  return {
    availability: value.availability,
    value: value.value,
    unit: value.unit.trim(),
    reason: value.reason,
    factIds: value.factIds,
  };
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

function isFactIds(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
  allowEmpty: boolean,
): value is string[] {
  return Array.isArray(value)
    && (allowEmpty || value.length > 0)
    && value.length <= 100
    && value.every((factId) => isIdentifier(factId) && allowedFactIds.has(factId))
    && new Set(value).size === value.length;
}

function isIsoDate(value: unknown): value is string {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value);
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
