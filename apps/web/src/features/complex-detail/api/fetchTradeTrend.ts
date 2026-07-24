import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { readValidatedJson, requestFailureFromResponse } from '../../../shared/http/requestFailure';
import { fetchWithTimeout } from '../../../shared/http/fetchWithTimeout';

export type TradeTrendPoint = {
  month: string;
  avgAmount: number;
  count: number;
  minAmount: number;
  maxAmount: number;
};

type TradeTrendPointResponse = {
  month?: string;
  avgAmount?: number | string;
  count?: number | string;
  minAmount?: number | string;
  maxAmount?: number | string;
};

const TRADE_PATH = '/api/v1/trade';
const COMPLEX_PATH = '/api/v1/complex';

export async function fetchParcelTradeTrend(
  parcelId: number,
  complexId?: number | null,
  signal?: AbortSignal,
): Promise<TradeTrendPoint[]> {
  const query = complexId == null ? '' : `?complexId=${encodeURIComponent(complexId)}`;
  return fetchTrend(`${TRADE_PATH}/${parcelId}/trend${query}`, signal);
}

export async function fetchComplexTradeTrend(
  complexId: number,
  signal?: AbortSignal,
): Promise<TradeTrendPoint[]> {
  return fetchTrend(`${COMPLEX_PATH}/${complexId}/trade-trend`, signal);
}

async function fetchTrend(path: string, signal?: AbortSignal): Promise<TradeTrendPoint[]> {
  const response = await fetchWithTimeout(resolveApiUrl(path), { method: 'GET', signal });

  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'trade-trend',
    });
  }

  return readValidatedJson(response, {
    service: 'property-data',
    operation: 'trade-trend',
  }, normalizeTradeTrend);
}

function normalizeTradeTrend(payload: unknown): TradeTrendPoint[] {
  if (!Array.isArray(payload)) {
    throw new Error('Invalid public API trade trend response: expected an array');
  }
  return payload.map((point) => {
    if (!isRecord(point)) {
      throw new Error('Invalid public API trade trend response: point must be an object');
    }

    return normalizeTrendPoint(point);
  });
}

function normalizeTrendPoint(point: TradeTrendPointResponse): TradeTrendPoint {
  return {
    month: toRequiredString(point.month, 'month'),
    avgAmount: toRequiredNumber(point.avgAmount, 'avgAmount'),
    count: toRequiredNumber(point.count, 'count'),
    minAmount: toRequiredNumber(point.minAmount, 'minAmount'),
    maxAmount: toRequiredNumber(point.maxAmount, 'maxAmount'),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error(`Invalid public API trade trend response: ${field} must be a number`);
  }

  return value;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API trade trend response: ${field} must be a non-empty string`);
  }

  return value;
}
