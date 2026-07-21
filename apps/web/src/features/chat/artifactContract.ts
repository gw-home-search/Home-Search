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

export type RecommendationScoreItem = {
  key: 'PRICE' | 'TRANSIT' | 'SHOPPING' | 'STUDENT' | 'YOUNG_CHILD';
  label: string;
  weight: number;
  points: number;
  distanceMeters: number | null;
  factIds: string[];
  details?: string[];
};

export type RecommendationCard = {
  rank: number;
  complexId: number;
  complexName: string;
  totalScore: number;
  latestTrade: { date: string; amountTenThousandKrw: number; factIds: string[] };
  recentThreeMedian: { amountTenThousandKrw: number; factIds: string[] };
  scoreBreakdown: RecommendationScoreItem[];
  limitations: string[];
  factIds: string[];
  activeThemes: Array<'TRANSIT' | 'STUDENT' | 'YOUNG_CHILD' | 'SHOPPING'>;
};

export type RecommendationCardsArtifact = {
  type: 'recommendationCards';
  version: 1;
  artifactId: string;
  title: string;
  policyVersion: 'recommendation-policy-v1';
  cards: RecommendationCard[];
};

export type ChatArtifact = FactListArtifact | ComparisonTableArtifact | RecommendationCardsArtifact;

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
      ?? readComparisonTableArtifact(candidate, allowedFactIds)
      ?? readRecommendationCardsArtifact(candidate, allowedFactIds);
    return artifact == null ? [] : [artifact];
  });
}

function readRecommendationCardsArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationCardsArtifact | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'artifactId', 'title', 'policyVersion', 'cards',
    ])
    || value.type !== 'recommendationCards'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || value.policyVersion !== 'recommendation-policy-v1'
    || !Array.isArray(value.cards)
    || value.cards.length === 0
    || value.cards.length > 5) return null;
  const cards = value.cards.flatMap((card) => {
    const parsed = readRecommendationCard(card, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  if (cards.length !== value.cards.length
    || cards.some((card, index) => card.rank !== index + 1)
    || new Set(cards.map(({ complexId }) => complexId)).size !== cards.length) return null;
  return {
    type: 'recommendationCards',
    version: 1,
    artifactId: value.artifactId,
    title: value.title.trim(),
    policyVersion: 'recommendation-policy-v1',
    cards,
  };
}

function readRecommendationCard(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationCard | null {
  const hasCurrentKeys = isRecord(value) && hasExactKeys(value, [
    'rank', 'complexId', 'complexName', 'totalScore', 'latestTrade',
    'recentThreeMedian', 'scoreBreakdown', 'limitations', 'factIds', 'activeThemes',
  ]);
  const hasLegacyKeys = isRecord(value) && hasExactKeys(value, [
    'rank', 'complexId', 'complexName', 'totalScore', 'latestTrade',
    'recentThreeMedian', 'scoreBreakdown', 'limitations', 'factIds',
  ]);
  if (!isRecord(value)
    || (!hasCurrentKeys && !hasLegacyKeys)
    || !isIntegerInRange(value.rank, 1, 5)
    || !isIntegerInRange(value.complexId, 1, Number.MAX_SAFE_INTEGER)
    || !isDisplayText(value.complexName, 100)
    || !isNumberInRange(value.totalScore, 0, 100)
    || !isRecord(value.recentThreeMedian)
    || !hasExactKeys(value.recentThreeMedian, ['amountTenThousandKrw', 'factIds'])
    || !isIntegerInRange(
      value.recentThreeMedian.amountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER,
    )
    || !isFactIds(value.recentThreeMedian.factIds, allowedFactIds, false)
    || !isRecord(value.latestTrade)
    || !hasExactKeys(value.latestTrade, ['date', 'amountTenThousandKrw', 'factIds'])
    || !isIsoDate(value.latestTrade.date)
    || !isIntegerInRange(value.latestTrade.amountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER)
    || !isFactIds(value.latestTrade.factIds, allowedFactIds, false)
    || !Array.isArray(value.scoreBreakdown)
    || value.scoreBreakdown.length < 3
    || value.scoreBreakdown.length > 5
    || !Array.isArray(value.limitations)
    || value.limitations.length > 5
    || !value.limitations.every((item) => isDisplayText(item, 2_000))
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  const rawActiveThemes = hasCurrentKeys ? value.activeThemes : [];
  if (!Array.isArray(rawActiveThemes)
    || rawActiveThemes.length > 3
    || !rawActiveThemes.every((theme) => (
      theme === 'TRANSIT' || theme === 'STUDENT'
      || theme === 'YOUNG_CHILD' || theme === 'SHOPPING'
    ))
    || new Set(rawActiveThemes).size !== rawActiveThemes.length) return null;
  const scoreBreakdown = value.scoreBreakdown.flatMap((item) => {
    const parsed = readRecommendationScoreItem(item, allowedFactIds);
    return parsed == null ? [] : [parsed];
  });
  const activeThemes = rawActiveThemes as RecommendationCard['activeThemes'];
  const canonicalThemes = ['TRANSIT', 'STUDENT', 'YOUNG_CHILD', 'SHOPPING']
    .filter((theme) => activeThemes.includes(theme as RecommendationCard['activeThemes'][number]));
  if (activeThemes.some((theme, index) => theme !== canonicalThemes[index])) return null;
  const optionalKeys = ['STUDENT', 'YOUNG_CHILD'].filter((key) => activeThemes.includes(key as 'STUDENT' | 'YOUNG_CHILD'));
  const expectedKeys = ['PRICE', 'TRANSIT', 'SHOPPING', ...optionalKeys];
  const dynamicShare = activeThemes.length === 0 ? 0 : 25 / activeThemes.length;
  const expectedWeights = [
    60,
    activeThemes.length === 0 ? 25 : 10 + (activeThemes.includes('TRANSIT') ? dynamicShare : 0),
    activeThemes.length === 0 ? 15 : 5 + (activeThemes.includes('SHOPPING') ? dynamicShare : 0),
    ...optionalKeys.map(() => dynamicShare),
  ];
  const scoreTotal = Math.round(
    scoreBreakdown.reduce((total, item) => total + item.points, 0) * 10,
  ) / 10;
  if (scoreBreakdown.length !== expectedKeys.length
    || scoreBreakdown.some((item, index) => (
      item.key !== expectedKeys[index]
      || Math.abs(item.weight - (expectedWeights[index] ?? -1)) > 1e-9
    ))
    || scoreTotal !== value.totalScore) return null;
  const cardFactIds = new Set(value.factIds);
  const nestedFactIds = [
    ...value.latestTrade.factIds,
    ...value.recentThreeMedian.factIds,
    ...scoreBreakdown.flatMap(({ factIds }) => factIds),
  ];
  if (!nestedFactIds.every((factId) => cardFactIds.has(factId))) return null;
  return {
    rank: value.rank,
    complexId: value.complexId,
    complexName: value.complexName.trim(),
    totalScore: value.totalScore,
    latestTrade: {
      date: value.latestTrade.date,
      amountTenThousandKrw: value.latestTrade.amountTenThousandKrw,
      factIds: value.latestTrade.factIds,
    },
    recentThreeMedian: {
      amountTenThousandKrw: value.recentThreeMedian.amountTenThousandKrw,
      factIds: value.recentThreeMedian.factIds,
    },
    scoreBreakdown,
    limitations: value.limitations.map((item) => item.trim()),
    factIds: value.factIds,
    activeThemes,
  };
}

function readRecommendationScoreItem(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationScoreItem | null {
  if (!isRecord(value)
    || (!hasExactKeys(value, [
      'key', 'label', 'weight', 'points', 'distanceMeters', 'factIds',
    ]) && !hasExactKeys(value, [
      'key', 'label', 'weight', 'points', 'distanceMeters', 'factIds', 'details',
    ]))
    || (value.key !== 'PRICE' && value.key !== 'TRANSIT' && value.key !== 'SHOPPING'
      && value.key !== 'STUDENT' && value.key !== 'YOUNG_CHILD')
    || !isDisplayText(value.label, 100)
    || !isNumberInRange(value.weight, 0, 100)
    || !isNumberInRange(value.points, 0, value.weight)
    || (value.distanceMeters !== null
      && !isIntegerInRange(value.distanceMeters, 0, 10_000_000))
    || !isFactIds(value.factIds, allowedFactIds, false)
    || (value.details !== undefined && (
      !Array.isArray(value.details)
      || value.details.length > 5
      || !value.details.every((item) => isDisplayText(item, 200))
    ))) return null;
  return {
    key: value.key,
    label: value.label.trim(),
    weight: value.weight,
    points: value.points,
    distanceMeters: value.distanceMeters,
    factIds: value.factIds,
    ...(value.details === undefined
      ? {}
      : { details: value.details.map((item) => item.trim()) }),
  };
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

function isNumberInRange(value: unknown, minimum: number, maximum: number): value is number {
  return typeof value === 'number'
    && Number.isFinite(value)
    && value >= minimum
    && value <= maximum;
}

function isIntegerInRange(value: unknown, minimum: number, maximum: number): value is number {
  return isNumberInRange(value, minimum, maximum) && Number.isInteger(value);
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
