import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

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
): Promise<TradeTrendPoint[]> {
  const query = complexId == null ? '' : `?complexId=${encodeURIComponent(complexId)}`;
  return fetchTrend(`${TRADE_PATH}/${parcelId}/trend${query}`);
}

export async function fetchComplexTradeTrend(complexId: number): Promise<TradeTrendPoint[]> {
  return fetchTrend(`${COMPLEX_PATH}/${complexId}/trade-trend`);
}

async function fetchTrend(path: string): Promise<TradeTrendPoint[]> {
  const response = await fetch(resolveApiUrl(path), { method: 'GET' });

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch trade trend: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
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
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API trade trend response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API trade trend response: ${field} must be a number`);
  }

  return parsed;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API trade trend response: ${field} must be a non-empty string`);
  }

  return value;
}
