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

export type TradeTableArtifact = {
  type: 'tradeTable';
  version: 1;
  artifactId: string;
  title: string;
  amountUnit: '10_000_KRW';
  rows: Array<{
    tradeId: number;
    dealDate: string;
    exclusiveAreaSquareMeters: number;
    amountTenThousandKrw: number;
    floor: number | null;
    factIds: string[];
  }>;
};

export type TrendTableArtifact = {
  type: 'trendTable';
  version: 1;
  artifactId: string;
  title: string;
  amountUnit: '10_000_KRW';
  rows: Array<{
    month: string;
    averageAmountTenThousandKrw: number | null;
    minimumAmountTenThousandKrw: number | null;
    maximumAmountTenThousandKrw: number | null;
    tradeCount: number | null;
    availability: 'available' | 'unavailable';
    reason: string | null;
    factIds: string[];
  }>;
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
  version: 1 | 2;
  artifactId: string;
  title: string;
  columns: Array<{ key: string; label: string; factIds: string[] }>;
  rows: Array<{
    key: string;
    label: string;
    group?: 'PRICE' | 'SCALE' | 'TRANSPORT' | 'EDUCATION' | 'LIFESTYLE';
    cells: ComparisonTableCell[];
  }>;
  basis: {
    cutoffDate: string | null;
    startDate: string | null;
    exclusiveAreaSquareMeters: number | null;
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

export type RecommendationMetricKey = 'TRANSIT' | 'ACADEMY' | 'SCHOOL' | 'SHOPPING';

export type RecommendationTableMetric = {
  availability: 'available' | 'unavailable';
  value: number | null;
  unit: 'COUNT' | 'METERS';
  nearestDistanceMeters: number | null;
  reason: string | null;
  factIds: string[];
};

export type RecommendationTableArtifactV1 = {
  type: 'recommendationTable';
  version: 1;
  artifactId: string;
  title: string;
  policyVersion: 'criteria-recommendation-policy-v1';
  basis: {
    scopeType: 'ADMIN_REGION' | 'STATION_RADIUS';
    scopeLabel: string;
    criteriaOrder: RecommendationMetricKey[];
    minimumUnitCount: number | null;
    radiusMeters: number | null;
  };
  rows: Array<{
    order: number;
    complexId: number;
    complexName: string;
    unitCount: number | null;
    metrics: Partial<Record<RecommendationMetricKey, RecommendationTableMetric>>;
    factIds: string[];
  }>;
};

export type AgentRecommendationRole = 'BALANCED' | 'TRADE_ACTIVITY' | 'SCALE'
  | 'NEWER' | 'TRANSIT' | 'EDUCATION' | 'LIFESTYLE';

export type RecommendationTableArtifactV2 = {
  type: 'recommendationTable';
  version: 2;
  artifactId: string;
  title: string;
  policyVersion: 'agentic-recommendation-v1';
  basis: {
    selectionMode: 'AGENTIC';
    scopeType: 'ADMIN_REGION' | 'STATION_RADIUS';
    scopeLabel: string;
    requestedCount: number;
    criteriaOrder: Array<'TRADE_ACTIVITY' | 'SCALE' | 'NEWER' | 'TRANSIT' | 'EDUCATION' | 'LIFESTYLE'>;
    defaultPolicy: 'BALANCED_V1';
  };
  rows: Array<{
    order: number;
    complexId: number;
    complexName: string;
    role: AgentRecommendationRole;
    summary: string;
    strengths: Array<{ text: string; factIds: string[] }>;
    tradeoffs: Array<{ text: string; factIds: string[] }>;
    metrics: Record<string, never>;
    factIds: string[];
  }>;
};

export type RecommendationTableArtifact = RecommendationTableArtifactV1
  | RecommendationTableArtifactV2;

export type CandidateProfileArtifact = {
  type: 'candidateProfile';
  version: 1;
  artifactId: string;
  title: string;
  rank: number;
  complexId: number;
  address: string | null;
  unitCount: number | null;
  useDate: string | null;
  reasons: Array<{ text: string; factIds: string[] }>;
  sections: Array<{
    key: 'TRADE' | 'TRANSPORT' | 'EDUCATION' | 'LIFESTYLE';
    label: string;
    items: Array<{ label: string; value: string; factIds: string[] }>;
  }>;
  factIds: string[];
};

export type ChatArtifact = FactListArtifact | TradeTableArtifact | TrendTableArtifact
  | ComparisonTableArtifact | RecommendationCardsArtifact | RecommendationTableArtifact
  | CandidateProfileArtifact;

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
      ?? readTradeTableArtifact(candidate, allowedFactIds)
      ?? readTrendTableArtifact(candidate, allowedFactIds)
      ?? readComparisonTableArtifact(candidate, allowedFactIds)
      ?? readRecommendationTableArtifact(candidate, allowedFactIds)
      ?? readCandidateProfileArtifact(candidate, allowedFactIds)
      ?? readRecommendationCardsArtifact(candidate, allowedFactIds);
    return artifact == null ? [] : [artifact];
  });
}

function readCandidateProfileArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): CandidateProfileArtifact | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'artifactId', 'title', 'rank', 'complexId', 'address',
      'unitCount', 'useDate', 'reasons', 'sections', 'factIds',
    ])
    || value.type !== 'candidateProfile'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || !isIntegerInRange(value.rank, 1, 5)
    || !isIntegerInRange(value.complexId, 1, Number.MAX_SAFE_INTEGER)
    || (value.address !== null && !isDisplayText(value.address, 300))
    || (value.unitCount !== null && !isIntegerInRange(value.unitCount, 0, 100_000))
    || (value.useDate !== null && !isIsoDate(value.useDate))
    || !Array.isArray(value.reasons)
    || value.reasons.length === 0
    || value.reasons.length > 3
    || !Array.isArray(value.sections)
    || value.sections.length > 4
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  const profileFactIds = new Set(value.factIds);
  const reasons = value.reasons.flatMap((reason) => {
    if (!isRecord(reason)
      || !hasExactKeys(reason, ['text', 'factIds'])
      || !isDisplayText(reason.text, 500)
      || !isFactIds(reason.factIds, allowedFactIds, false)
      || !reason.factIds.every((factId) => profileFactIds.has(factId))) return [];
    return [{ text: reason.text.trim(), factIds: reason.factIds }];
  });
  const sections = value.sections.flatMap((section) => {
    if (!isRecord(section)
      || !hasExactKeys(section, ['key', 'label', 'items'])
      || !isCandidateSectionKey(section.key)
      || !isDisplayText(section.label, 100)
      || !Array.isArray(section.items)
      || section.items.length === 0
      || section.items.length > 8) return [];
    const items = section.items.flatMap((item) => {
      if (!isRecord(item)
        || !hasExactKeys(item, ['label', 'value', 'factIds'])
        || !isDisplayText(item.label, 100)
        || !isDisplayText(item.value, 500)
        || !isFactIds(item.factIds, allowedFactIds, false)
        || !item.factIds.every((factId) => profileFactIds.has(factId))) return [];
      return [{ label: item.label.trim(), value: item.value.trim(), factIds: item.factIds }];
    });
    return items.length === section.items.length
      ? [{ key: section.key, label: section.label.trim(), items }]
      : [];
  });
  if (reasons.length !== value.reasons.length
    || sections.length !== value.sections.length
    || new Set(sections.map(({ key }) => key)).size !== sections.length) return null;
  return {
    type: 'candidateProfile', version: 1,
    artifactId: value.artifactId, title: value.title.trim(), rank: value.rank,
    complexId: value.complexId, address: value.address?.trim() ?? null,
    unitCount: value.unitCount, useDate: value.useDate,
    reasons, sections, factIds: value.factIds,
  };
}

function isCandidateSectionKey(
  value: unknown,
): value is CandidateProfileArtifact['sections'][number]['key'] {
  return value === 'TRADE' || value === 'TRANSPORT'
    || value === 'EDUCATION' || value === 'LIFESTYLE';
}

function readRecommendationTableArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationTableArtifact | null {
  if (!isRecord(value) || value.type !== 'recommendationTable') return null;
  return value.version === 1
    ? readRecommendationTableArtifactV1(value, allowedFactIds)
    : value.version === 2
      ? readRecommendationTableArtifactV2(value, allowedFactIds)
      : null;
}

function readRecommendationTableArtifactV1(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationTableArtifactV1 | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'artifactId', 'title', 'policyVersion', 'basis', 'rows',
    ])
    || value.type !== 'recommendationTable'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || value.policyVersion !== 'criteria-recommendation-policy-v1'
    || !isRecord(value.basis)
    || !hasExactKeys(value.basis, [
      'scopeType', 'scopeLabel', 'criteriaOrder', 'minimumUnitCount', 'radiusMeters',
    ])
    || (value.basis.scopeType !== 'ADMIN_REGION'
      && value.basis.scopeType !== 'STATION_RADIUS')
    || !isDisplayText(value.basis.scopeLabel, 100)
    || !Array.isArray(value.basis.criteriaOrder)
    || value.basis.criteriaOrder.length > 4
    || !value.basis.criteriaOrder.every(isRecommendationMetricKey)
    || new Set(value.basis.criteriaOrder).size !== value.basis.criteriaOrder.length
    || (value.basis.minimumUnitCount !== null
      && !isIntegerInRange(value.basis.minimumUnitCount, 1, 100_000))
    || (value.basis.radiusMeters !== null
      && !isIntegerInRange(value.basis.radiusMeters, 100, 3_000))
    || !Array.isArray(value.rows)
    || value.rows.length === 0
    || value.rows.length > 5) return null;
  const criteriaOrder = value.basis.criteriaOrder as RecommendationMetricKey[];
  const rows = value.rows.flatMap((row) => {
    if (!isRecord(row)
      || !hasExactKeys(row, [
        'order', 'complexId', 'complexName', 'unitCount', 'metrics', 'factIds',
      ])
      || !isIntegerInRange(row.order, 1, 5)
      || !isIntegerInRange(row.complexId, 1, Number.MAX_SAFE_INTEGER)
      || !isDisplayText(row.complexName, 100)
      || (row.unitCount !== null && !isIntegerInRange(row.unitCount, 0, 100_000))
      || !isRecord(row.metrics)
      || !isFactIds(row.factIds, allowedFactIds, false)) return [];
    const rowFactIds = row.factIds;
    const metricKeys = Object.keys(row.metrics);
    if (metricKeys.length !== criteriaOrder.length
      || metricKeys.some((key) => !criteriaOrder.includes(key as RecommendationMetricKey))) {
      return [];
    }
    const metrics: Partial<Record<RecommendationMetricKey, RecommendationTableMetric>> = {};
    for (const key of criteriaOrder) {
      const metric = readRecommendationTableMetric(row.metrics[key], key, allowedFactIds);
      if (metric == null || !metric.factIds.every((factId) => rowFactIds.includes(factId))) {
        return [];
      }
      metrics[key] = metric;
    }
    return [{
      order: row.order,
      complexId: row.complexId,
      complexName: row.complexName.trim(),
      unitCount: row.unitCount,
      metrics,
      factIds: rowFactIds,
    }];
  });
  if (rows.length !== value.rows.length
    || rows.some((row, index) => row.order !== index + 1)
    || new Set(rows.map(({ complexId }) => complexId)).size !== rows.length) return null;
  return {
    type: 'recommendationTable', version: 1,
    artifactId: value.artifactId, title: value.title.trim(),
    policyVersion: 'criteria-recommendation-policy-v1',
    basis: {
      scopeType: value.basis.scopeType,
      scopeLabel: value.basis.scopeLabel.trim(),
      criteriaOrder,
      minimumUnitCount: value.basis.minimumUnitCount,
      radiusMeters: value.basis.radiusMeters,
    },
    rows,
  };
}

function readRecommendationTableArtifactV2(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): RecommendationTableArtifactV2 | null {
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'type', 'version', 'artifactId', 'title', 'policyVersion', 'basis', 'rows',
    ])
    || value.type !== 'recommendationTable'
    || value.version !== 2
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || value.policyVersion !== 'agentic-recommendation-v1'
    || !isRecord(value.basis)
    || !hasExactKeys(value.basis, [
      'selectionMode', 'scopeType', 'scopeLabel', 'requestedCount',
      'criteriaOrder', 'defaultPolicy',
    ])
    || value.basis.selectionMode !== 'AGENTIC'
    || (value.basis.scopeType !== 'ADMIN_REGION'
      && value.basis.scopeType !== 'STATION_RADIUS')
    || !isDisplayText(value.basis.scopeLabel, 100)
    || !isIntegerInRange(value.basis.requestedCount, 1, 5)
    || !Array.isArray(value.basis.criteriaOrder)
    || value.basis.criteriaOrder.length > 6
    || !value.basis.criteriaOrder.every(isAgentCriterion)
    || new Set(value.basis.criteriaOrder).size !== value.basis.criteriaOrder.length
    || value.basis.defaultPolicy !== 'BALANCED_V1'
    || !Array.isArray(value.rows)
    || value.rows.length === 0
    || value.rows.length > value.basis.requestedCount) return null;
  const rows = value.rows.flatMap((row) => {
    if (!isRecord(row)
      || !hasExactKeys(row, [
        'order', 'complexId', 'complexName', 'role', 'summary', 'strengths',
        'tradeoffs', 'metrics', 'factIds',
      ])
      || !isIntegerInRange(row.order, 1, 5)
      || !isIntegerInRange(row.complexId, 1, Number.MAX_SAFE_INTEGER)
      || !isDisplayText(row.complexName, 100)
      || !isAgentRole(row.role)
      || !isDisplayText(row.summary, 2_000)
      || !Array.isArray(row.strengths)
      || row.strengths.length === 0
      || row.strengths.length > 5
      || !Array.isArray(row.tradeoffs)
      || row.tradeoffs.length === 0
      || row.tradeoffs.length > 5
      || !isRecord(row.metrics)
      || Object.keys(row.metrics).length !== 0
      || !isFactIds(row.factIds, allowedFactIds, false)) return [];
    const rowFactIds = new Set(row.factIds);
    const strengths = readAgentFactTexts(row.strengths, allowedFactIds, rowFactIds);
    const tradeoffs = readAgentFactTexts(row.tradeoffs, allowedFactIds, rowFactIds);
    if (strengths == null || tradeoffs == null) return [];
    return [{
      order: row.order, complexId: row.complexId,
      complexName: row.complexName.trim(), role: row.role,
      summary: row.summary.trim(), strengths, tradeoffs,
      metrics: {} as Record<string, never>, factIds: row.factIds,
    }];
  });
  if (rows.length !== value.rows.length
    || rows.some((row, index) => row.order !== index + 1)
    || new Set(rows.map(({ complexId }) => complexId)).size !== rows.length) return null;
  return {
    type: 'recommendationTable', version: 2, artifactId: value.artifactId,
    title: value.title.trim(), policyVersion: 'agentic-recommendation-v1',
    basis: {
      selectionMode: 'AGENTIC', scopeType: value.basis.scopeType,
      scopeLabel: value.basis.scopeLabel.trim(), requestedCount: value.basis.requestedCount,
      criteriaOrder: value.basis.criteriaOrder as RecommendationTableArtifactV2['basis']['criteriaOrder'],
      defaultPolicy: 'BALANCED_V1',
    },
    rows,
  };
}

function readAgentFactTexts(
  values: unknown[],
  allowedFactIds: ReadonlySet<string>,
  rowFactIds: ReadonlySet<string>,
): Array<{ text: string; factIds: string[] }> | null {
  const parsed = values.flatMap((item) => {
    if (!isRecord(item)
      || !hasExactKeys(item, ['text', 'factIds'])
      || !isDisplayText(item.text, 2_000)
      || !isFactIds(item.factIds, allowedFactIds, false)
      || !item.factIds.every((factId) => rowFactIds.has(factId))) return [];
    return [{ text: item.text.trim(), factIds: item.factIds }];
  });
  return parsed.length === values.length ? parsed : null;
}

function isAgentRole(value: unknown): value is AgentRecommendationRole {
  return value === 'BALANCED' || value === 'TRADE_ACTIVITY' || value === 'SCALE'
    || value === 'NEWER' || value === 'TRANSIT' || value === 'EDUCATION'
    || value === 'LIFESTYLE';
}

function isAgentCriterion(
  value: unknown,
): value is RecommendationTableArtifactV2['basis']['criteriaOrder'][number] {
  return value === 'TRADE_ACTIVITY' || value === 'SCALE' || value === 'NEWER'
    || value === 'TRANSIT' || value === 'EDUCATION' || value === 'LIFESTYLE';
}

function readRecommendationTableMetric(
  value: unknown,
  key: RecommendationMetricKey,
  allowedFactIds: ReadonlySet<string>,
): RecommendationTableMetric | null {
  const expectedUnit = key === 'ACADEMY' ? 'COUNT' : 'METERS';
  if (!isRecord(value)
    || !hasExactKeys(value, [
      'availability', 'value', 'unit', 'nearestDistanceMeters', 'reason', 'factIds',
    ])
    || (value.availability !== 'available' && value.availability !== 'unavailable')
    || value.unit !== expectedUnit
    || !isFactIds(value.factIds, allowedFactIds, false)) return null;
  if (value.availability === 'available') {
    if (!isIntegerInRange(value.value, 0, Number.MAX_SAFE_INTEGER)
      || (value.nearestDistanceMeters !== null
        && !isIntegerInRange(value.nearestDistanceMeters, 0, 10_000_000))
      || value.reason !== null) return null;
  } else if (value.value !== null
    || value.nearestDistanceMeters !== null
    || !isDisplayText(value.reason, 2_000)) return null;
  return {
    availability: value.availability,
    value: value.value as number | null,
    unit: expectedUnit,
    nearestDistanceMeters: value.nearestDistanceMeters as number | null,
    reason: value.reason as string | null,
    factIds: value.factIds,
  };
}

function isRecommendationMetricKey(value: unknown): value is RecommendationMetricKey {
  return value === 'TRANSIT' || value === 'ACADEMY'
    || value === 'SCHOOL' || value === 'SHOPPING';
}

function readTradeTableArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): TradeTableArtifact | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['type', 'version', 'artifactId', 'title', 'amountUnit', 'rows'])
    || value.type !== 'tradeTable'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || value.amountUnit !== '10_000_KRW'
    || !Array.isArray(value.rows)
    || value.rows.length === 0
    || value.rows.length > 10) return null;
  const rows = value.rows.flatMap((row) => {
    if (!isRecord(row)
      || !hasExactKeys(row, [
        'tradeId', 'dealDate', 'exclusiveAreaSquareMeters',
        'amountTenThousandKrw', 'floor', 'factIds',
      ])
      || !isIntegerInRange(row.tradeId, 1, Number.MAX_SAFE_INTEGER)
      || !isIsoDate(row.dealDate)
      || !isNumberInRange(row.exclusiveAreaSquareMeters, Number.MIN_VALUE, 1_000)
      || !isIntegerInRange(row.amountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER)
      || (row.floor !== null && !isIntegerInRange(row.floor, -100, 1_000))
      || !isFactIds(row.factIds, allowedFactIds, false)) return [];
    return [{
      tradeId: row.tradeId,
      dealDate: row.dealDate,
      exclusiveAreaSquareMeters: row.exclusiveAreaSquareMeters,
      amountTenThousandKrw: row.amountTenThousandKrw,
      floor: row.floor,
      factIds: row.factIds,
    }];
  });
  if (rows.length !== value.rows.length
    || new Set(rows.map(({ tradeId }) => tradeId)).size !== rows.length) return null;
  return {
    type: 'tradeTable', version: 1, artifactId: value.artifactId,
    title: value.title.trim(), amountUnit: '10_000_KRW', rows,
  };
}

function readTrendTableArtifact(
  value: unknown,
  allowedFactIds: ReadonlySet<string>,
): TrendTableArtifact | null {
  if (!isRecord(value)
    || !hasExactKeys(value, ['type', 'version', 'artifactId', 'title', 'amountUnit', 'rows'])
    || value.type !== 'trendTable'
    || value.version !== 1
    || !isIdentifier(value.artifactId)
    || !isDisplayText(value.title, 100)
    || value.amountUnit !== '10_000_KRW'
    || !Array.isArray(value.rows)
    || value.rows.length === 0
    || value.rows.length > 24) return null;
  const rows = value.rows.flatMap((row) => {
    if (!isRecord(row)
      || !hasExactKeys(row, [
        'month', 'averageAmountTenThousandKrw', 'minimumAmountTenThousandKrw',
        'maximumAmountTenThousandKrw', 'tradeCount', 'availability', 'reason', 'factIds',
      ])
      || typeof row.month !== 'string'
      || !/^\d{4}-\d{2}$/.test(row.month)
      || (row.availability !== 'available' && row.availability !== 'unavailable')
      || !isFactIds(row.factIds, allowedFactIds, row.availability === 'unavailable')) return [];
    if (row.availability === 'available') {
      if (!isIntegerInRange(row.averageAmountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER)
        || !isIntegerInRange(row.minimumAmountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER)
        || !isIntegerInRange(row.maximumAmountTenThousandKrw, 1, Number.MAX_SAFE_INTEGER)
        || !isIntegerInRange(row.tradeCount, 1, Number.MAX_SAFE_INTEGER)
        || row.reason !== null
        || row.factIds.length === 0) return [];
    } else if (row.averageAmountTenThousandKrw !== null
      || row.minimumAmountTenThousandKrw !== null
      || row.maximumAmountTenThousandKrw !== null
      || row.tradeCount !== null
      || !isDisplayText(row.reason, 2_000)) return [];
    return [{
      month: row.month,
      averageAmountTenThousandKrw: row.averageAmountTenThousandKrw as number | null,
      minimumAmountTenThousandKrw: row.minimumAmountTenThousandKrw as number | null,
      maximumAmountTenThousandKrw: row.maximumAmountTenThousandKrw as number | null,
      tradeCount: row.tradeCount as number | null,
      availability: row.availability as 'available' | 'unavailable',
      reason: row.reason as string | null,
      factIds: row.factIds,
    }];
  });
  if (rows.length !== value.rows.length
    || new Set(rows.map(({ month }) => month)).size !== rows.length) return null;
  return {
    type: 'trendTable', version: 1, artifactId: value.artifactId,
    title: value.title.trim(), amountUnit: '10_000_KRW', rows,
  };
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
  const version = value.version as 1 | 2;
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
  const rows: ComparisonTableArtifact['rows'] = value.rows.flatMap((row) => {
    const expectedRowKeys = version === 2
      ? ['key', 'label', 'group', 'cells']
      : ['key', 'label', 'cells'];
    if (!isRecord(row)
      || !hasExactKeys(row, expectedRowKeys)
      || !isIdentifier(row.key)
      || !isDisplayText(row.label, 100)
      || (version === 2 && !isComparisonGroup(row.group))
      || !Array.isArray(row.cells)
      || row.cells.length !== columns.length) return [];
    const cells = row.cells.flatMap((cell) => {
      const parsed = readComparisonCell(cell, allowedFactIds);
      return parsed == null ? [] : [parsed];
    });
    if (cells.length !== columns.length) return [];
    const parsedRow: ComparisonTableArtifact['rows'][number] = {
      key: row.key,
      label: row.label.trim(),
      cells,
    };
    if (version === 2 && isComparisonGroup(row.group)) parsedRow.group = row.group;
    return [parsedRow];
  });
  const basis = value.basis;
  if (rows.length !== value.rows.length
    || new Set(rows.map(({ key }) => key)).size !== rows.length
    || !hasExactKeys(basis, ['cutoffDate', 'startDate', 'exclusiveAreaSquareMeters'])) return null;
  let parsedBasis: ComparisonTableArtifact['basis'];
  if (version === 1) {
    if (!isIsoDate(basis.cutoffDate)
      || !isIsoDate(basis.startDate)
      || typeof basis.exclusiveAreaSquareMeters !== 'number'
      || !Number.isFinite(basis.exclusiveAreaSquareMeters)
      || basis.exclusiveAreaSquareMeters <= 0
      || basis.exclusiveAreaSquareMeters > 1000) return null;
    parsedBasis = {
      cutoffDate: basis.cutoffDate,
      startDate: basis.startDate,
      exclusiveAreaSquareMeters: basis.exclusiveAreaSquareMeters,
    };
  } else {
    if (basis.cutoffDate !== null
      || basis.startDate !== null
      || basis.exclusiveAreaSquareMeters !== null) return null;
    parsedBasis = {
      cutoffDate: null,
      startDate: null,
      exclusiveAreaSquareMeters: null,
    };
  }
  return {
    type: 'comparisonTable',
    version,
    artifactId: value.artifactId,
    title: value.title.trim(),
    columns,
    rows,
    basis: parsedBasis,
  };
}

function isComparisonGroup(
  value: unknown,
): value is NonNullable<ComparisonTableArtifact['rows'][number]['group']> {
  return value === 'PRICE' || value === 'SCALE' || value === 'TRANSPORT'
    || value === 'EDUCATION' || value === 'LIFESTYLE';
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
