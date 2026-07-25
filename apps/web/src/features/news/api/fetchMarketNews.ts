import { fetchWithTimeout } from '../../../shared/http/fetchWithTimeout';
import {
  readValidatedJson,
  requestFailureFromResponse,
} from '../../../shared/http/requestFailure';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type MarketNewsCategory =
  | 'ALL'
  | 'POLICY'
  | 'FINANCE_LOAN'
  | 'SUPPLY_SALE'
  | 'REDEVELOPMENT'
  | 'TRANSACTION_PRICE'
  | 'TRANSPORT_DEVELOPMENT';
export type MarketNewsDataStatus = 'FRESH' | 'STALE' | 'UNAVAILABLE';
export type MarketNewsScopeType = 'NATIONWIDE' | 'SIDO';
export type MarketNewsRelationType = 'DIRECT_COMPLEX' | 'SAME_DONG' | 'SAME_SIGUNGU';

export type MarketNewsItem = {
  articleId: number;
  category: Exclude<MarketNewsCategory, 'ALL'>;
  title: string;
  providedAt: string;
  url: string;
  region: { code: string | null; name: string | null } | null;
  relationType: MarketNewsRelationType | null;
};

export type MarketNews = {
  snapshotId: string | null;
  generatedAt: string | null;
  dataCutoff: string | null;
  dataStatus: MarketNewsDataStatus;
  scope: { type: MarketNewsScopeType; regionCode: string | null };
  category: MarketNewsCategory;
  items: MarketNewsItem[];
  nextCursor: string | null;
};

export type MarketNewsQuery = {
  scope?: MarketNewsScopeType;
  regionCode?: string | null;
  category?: MarketNewsCategory;
  cursor?: string | null;
  limit?: number;
};

const CATEGORIES: readonly MarketNewsCategory[] = [
  'ALL',
  'POLICY',
  'FINANCE_LOAN',
  'SUPPLY_SALE',
  'REDEVELOPMENT',
  'TRANSACTION_PRICE',
  'TRANSPORT_DEVELOPMENT',
];
const ITEM_CATEGORIES = CATEGORIES.filter((value): value is Exclude<MarketNewsCategory, 'ALL'> => value !== 'ALL');
const RELATION_TYPES: readonly MarketNewsRelationType[] = ['DIRECT_COMPLEX', 'SAME_DONG', 'SAME_SIGUNGU'];

export async function fetchMarketNews(
  query: MarketNewsQuery = {},
  signal?: AbortSignal,
): Promise<MarketNews> {
  const params = new URLSearchParams();
  params.set('scope', query.scope ?? 'NATIONWIDE');
  if (query.regionCode) params.set('regionCode', query.regionCode);
  params.set('category', query.category ?? 'ALL');
  if (query.cursor) params.set('cursor', query.cursor);
  params.set('limit', String(query.limit ?? 20));
  const response = await fetchWithTimeout(
    resolveApiUrl(`/api/v1/insights/news?${params.toString()}`),
    { method: 'GET', signal },
  );
  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'market-news',
    });
  }
  return readValidatedJson(
    response,
    { service: 'property-data', operation: 'market-news' },
    normalizeMarketNews,
  );
}

export async function fetchComplexNews(
  complexId: number,
  signal?: AbortSignal,
): Promise<MarketNewsItem[]> {
  const response = await fetchWithTimeout(
    resolveApiUrl(`/api/v1/complex/${encodeURIComponent(String(complexId))}/news`),
    { method: 'GET', signal },
  );
  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'complex-news',
    });
  }
  return readValidatedJson(
    response,
    { service: 'property-data', operation: 'complex-news' },
    (value) => {
      if (!Array.isArray(value)) throw new Error('Invalid complex news response: array required');
      if (value.length > 5) throw new Error('Invalid complex news response: at most five items');
      return value.map(normalizeNewsItem);
    },
  );
}

function normalizeMarketNews(value: unknown): MarketNews {
  const source = record(value, 'response');
  const scope = record(source.scope, 'scope');
  if (!Array.isArray(source.items)) throw new Error('Invalid market news response: items');
  return {
    snapshotId: optionalString(source.snapshotId, 'snapshotId'),
    generatedAt: optionalString(source.generatedAt, 'generatedAt'),
    dataCutoff: optionalString(source.dataCutoff, 'dataCutoff'),
    dataStatus: enumValue(source.dataStatus, ['FRESH', 'STALE', 'UNAVAILABLE'], 'dataStatus'),
    scope: {
      type: enumValue(scope.type, ['NATIONWIDE', 'SIDO'], 'scope.type'),
      regionCode: optionalString(scope.regionCode, 'scope.regionCode'),
    },
    category: enumValue(source.category, CATEGORIES, 'category'),
    items: source.items.map(normalizeNewsItem),
    nextCursor: optionalString(source.nextCursor, 'nextCursor'),
  };
}

function normalizeNewsItem(value: unknown): MarketNewsItem {
  const item = record(value, 'item');
  const region = item.region == null ? null : record(item.region, 'region');
  return {
    articleId: requiredNumber(item.articleId, 'articleId'),
    category: enumValue(item.category, ITEM_CATEGORIES, 'item.category'),
    title: requiredString(item.title, 'title'),
    providedAt: requiredString(item.providedAt, 'providedAt'),
    url: safePublicUrl(item.url),
    region: region == null ? null : {
      code: optionalString(region.code, 'region.code'),
      name: optionalString(region.name, 'region.name'),
    },
    relationType: item.relationType == null
      ? null
      : enumValue(item.relationType, RELATION_TYPES, 'relationType'),
  };
}

function safePublicUrl(value: unknown): string {
  const url = requiredString(value, 'url');
  const parsed = new URL(url);
  if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password) {
    throw new Error('Invalid market news response: unsafe url');
  }
  return url;
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`Invalid market news response: ${field}`);
  }
  return value as Record<string, unknown>;
}

function requiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || !value) throw new Error(`Invalid market news response: ${field}`);
  return value;
}

function optionalString(value: unknown, field: string): string | null {
  if (value == null) return null;
  return requiredString(value, field);
}

function requiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid market news response: ${field}`);
  }
  return value;
}

function enumValue<const T extends string>(value: unknown, values: readonly T[], field: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) {
    throw new Error(`Invalid market news response: ${field}`);
  }
  return value as T;
}
