import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type InsightDataStatus = 'FRESH' | 'STALE' | 'UNAVAILABLE';
export type InsightScopeType = 'NATIONWIDE' | 'SIDO';
export type InsightTradeStatus = 'ACTIVE' | 'CANCELED';

export type InsightTradeItem = {
  rank: number;
  complexId: number;
  parcelId: number;
  complexName: string;
  sidoName: string | null;
  sigunguName: string | null;
  exclArea: number;
  dealAmount: number;
  dealDate: string;
  disclosedAt: string;
  previousAmount: number | null;
  previousDealDate: string | null;
  deltaAmount: number | null;
  deltaRate: number | null;
  tradeStatus: InsightTradeStatus;
};

export type MarketInsights = {
  snapshotId: string | null;
  periodStart: string;
  periodEnd: string;
  generatedAt: string | null;
  dataCutoff: string | null;
  dataStatus: InsightDataStatus;
  scope: { type: InsightScopeType; regionCode: string | null };
  newTrades: InsightTradeItem[];
  highestDeals: InsightTradeItem[];
  recordHighs: InsightTradeItem[];
  previousRises: InsightTradeItem[];
  previousFalls: InsightTradeItem[];
  cancellations: InsightTradeItem[];
};

type InsightQuery = {
  scope?: InsightScopeType;
  regionCode?: string | null;
  date?: string;
  limit?: number;
};

const SECTIONS = [
  'newTrades', 'highestDeals', 'recordHighs', 'previousRises', 'previousFalls', 'cancellations',
] as const;

export async function fetchMarketInsights(
  query: InsightQuery = {},
  signal?: AbortSignal,
): Promise<MarketInsights> {
  const params = new URLSearchParams();
  params.set('scope', query.scope ?? 'NATIONWIDE');
  if (query.regionCode) params.set('regionCode', query.regionCode);
  if (query.date) params.set('date', query.date);
  params.set('limit', String(query.limit ?? 10));
  const response = await fetch(
    resolveApiUrl(`/api/v1/insights/trades/latest?${params.toString()}`),
    { method: 'GET', signal },
  );
  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(`Failed to fetch market insights: ${response.status}${detail ? ` ${detail}` : ''}`);
  }
  return normalizeInsights(await response.json());
}

function normalizeInsights(value: unknown): MarketInsights {
  const source = record(value, 'response');
  const scope = record(source.scope, 'scope');
  const dataStatus = enumValue(source.dataStatus, ['FRESH', 'STALE', 'UNAVAILABLE'], 'dataStatus');
  const scopeType = enumValue(scope.type, ['NATIONWIDE', 'SIDO'], 'scope.type');
  const result = {
    snapshotId: optionalString(source.snapshotId, 'snapshotId'),
    periodStart: requiredString(source.periodStart, 'periodStart'),
    periodEnd: requiredString(source.periodEnd, 'periodEnd'),
    generatedAt: optionalString(source.generatedAt, 'generatedAt'),
    dataCutoff: optionalString(source.dataCutoff, 'dataCutoff'),
    dataStatus,
    scope: { type: scopeType, regionCode: optionalString(scope.regionCode, 'scope.regionCode') },
  } as Omit<MarketInsights, typeof SECTIONS[number]> & Partial<MarketInsights>;
  for (const section of SECTIONS) {
    const items = source[section];
    if (!Array.isArray(items)) throw new Error(`Invalid market insight response: ${section} must be an array`);
    result[section] = items.map(normalizeItem);
  }
  return result as MarketInsights;
}

function normalizeItem(value: unknown): InsightTradeItem {
  const item = record(value, 'item');
  return {
    rank: requiredNumber(item.rank, 'rank'),
    complexId: requiredNumber(item.complexId, 'complexId'),
    parcelId: requiredNumber(item.parcelId, 'parcelId'),
    complexName: requiredString(item.complexName, 'complexName'),
    sidoName: optionalString(item.sidoName, 'sidoName'),
    sigunguName: optionalString(item.sigunguName, 'sigunguName'),
    exclArea: requiredNumber(item.exclArea, 'exclArea'),
    dealAmount: requiredNumber(item.dealAmount, 'dealAmount'),
    dealDate: requiredString(item.dealDate, 'dealDate'),
    disclosedAt: requiredString(item.disclosedAt, 'disclosedAt'),
    previousAmount: optionalNumber(item.previousAmount, 'previousAmount'),
    previousDealDate: optionalString(item.previousDealDate, 'previousDealDate'),
    deltaAmount: optionalNumber(item.deltaAmount, 'deltaAmount'),
    deltaRate: optionalNumber(item.deltaRate, 'deltaRate'),
    tradeStatus: enumValue(item.tradeStatus, ['ACTIVE', 'CANCELED'], 'tradeStatus'),
  };
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Invalid market insight response: ${field} must be an object`);
  }
  return value as Record<string, unknown>;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value) throw new Error(`Invalid market insight response: ${field}`);
  return value;
}

function optionalString(value: unknown, field: string): string | null {
  if (value == null) return null;
  return requiredString(value, field);
}

function requiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid market insight response: ${field}`);
  }
  return value;
}

function optionalNumber(value: unknown, field: string): number | null {
  if (value == null) return null;
  return requiredNumber(value, field);
}

function enumValue<const T extends string>(value: unknown, values: readonly T[], field: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) {
    throw new Error(`Invalid market insight response: ${field}`);
  }
  return value as T;
}
