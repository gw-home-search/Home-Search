import { readProblemDetail } from '../../map/api/readProblemDetail';
import { resolveApiUrl } from '../../map/api/resolveApiUrl';

export type TradeItem = {
  tradeId: number;
  dealDate: string;
  exclArea: number;
  dealAmount: number;
  aptDong: string | null;
  floor: number | null;
};

export type ParcelTrades = {
  parcelId: number;
  complexId: number | null;
  trades: TradeItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type TradePageOptions = {
  page?: number;
  size?: number;
};

type ParcelTradesResponse = {
  parcelId?: number | string;
  complexId?: number | string | null;
  content?: unknown;
  page?: number | string;
  size?: number | string;
  totalElements?: number | string;
  totalPages?: number | string;
};

type TradeItemResponse = {
  tradeId?: number | string;
  dealDate?: string;
  exclArea?: number | string;
  dealAmount?: number | string;
  aptDong?: string | null;
  floor?: number | string | null;
};

const TRADE_PATH = '/api/v1/trade';
const COMPLEX_PATH = '/api/v1/complex';

export async function fetchParcelTrades(
  parcelId: number,
  complexId?: number | null,
  options: TradePageOptions = {},
): Promise<ParcelTrades> {
  const response = await fetch(
    resolveApiUrl(`${TRADE_PATH}/${parcelId}${tradeQuery(complexId, options)}`),
    { method: 'GET' },
  );

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch parcel trades: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!isRecord(payload)) {
    throw new Error('Invalid public API parcel trade response: expected an object');
  }

  return normalizeParcelTrades(payload);
}

export async function fetchComplexTrades(
  complexId: number,
  options: TradePageOptions = {},
): Promise<ParcelTrades> {
  const response = await fetch(
    resolveApiUrl(`${COMPLEX_PATH}/${complexId}/trades${tradeQuery(null, options)}`),
    { method: 'GET' },
  );

  if (!response.ok) {
    const detail = await readProblemDetail(response);
    throw new Error(
      `Failed to fetch parcel trades: ${response.status}${detail ? ` ${detail}` : ''}`,
    );
  }

  const payload: unknown = await response.json();
  if (!isRecord(payload)) {
    throw new Error('Invalid public API parcel trade response: expected an object');
  }

  return normalizeParcelTrades(payload);
}

function normalizeParcelTrades(payload: ParcelTradesResponse): ParcelTrades {
  if (!Array.isArray(payload.content)) {
    throw new Error('Invalid public API parcel trade response: content must be an array');
  }

  return {
    parcelId: toRequiredNumber(payload.parcelId, 'parcelId'),
    complexId: toNullableNumber(payload.complexId, 'complexId'),
    trades: payload.content.map((trade) => {
      if (!isTradeItemResponse(trade)) {
        throw new Error('Invalid public API parcel trade response: trade item must be an object');
      }

      return normalizeTradeItem(trade);
    }),
    page: toRequiredNumber(payload.page, 'page'),
    size: toRequiredNumber(payload.size, 'size'),
    totalElements: toRequiredNumber(payload.totalElements, 'totalElements'),
    totalPages: toRequiredNumber(payload.totalPages, 'totalPages'),
  };
}

function tradeQuery(complexId: number | null | undefined, options: TradePageOptions): string {
  const params = new URLSearchParams();
  if (complexId != null) {
    params.set('complexId', String(complexId));
  }
  if (options.page != null) {
    params.set('page', String(options.page));
  }
  if (options.size != null) {
    params.set('size', String(options.size));
  }
  const query = params.toString();
  return query.length > 0 ? `?${query}` : '';
}

function normalizeTradeItem(trade: TradeItemResponse): TradeItem {
  return {
    tradeId: toRequiredNumber(trade.tradeId, 'tradeId'),
    dealDate: toRequiredString(trade.dealDate, 'dealDate'),
    exclArea: toRequiredNumber(trade.exclArea, 'exclArea'),
    dealAmount: toRequiredNumber(trade.dealAmount, 'dealAmount'),
    aptDong: toNullableString(trade.aptDong),
    floor: toNullableNumber(trade.floor, 'floor'),
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function isTradeItemResponse(value: unknown): value is TradeItemResponse {
  return isRecord(value);
}

function toRequiredNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API parcel trade response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API parcel trade response: ${field} must be a number`);
  }

  return parsed;
}

function toNullableNumber(value: unknown, field: string): number | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'number' && (typeof value !== 'string' || value.trim().length === 0)) {
    throw new Error(`Invalid public API parcel trade response: ${field} must be a number`);
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`Invalid public API parcel trade response: ${field} must be a number`);
  }

  return parsed;
}

function toRequiredString(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Invalid public API parcel trade response: ${field} must be a non-empty string`);
  }

  return value;
}

function toNullableString(value: unknown): string | null {
  if (value == null) {
    return null;
  }

  if (typeof value !== 'string') {
    return null;
  }

  return value.length > 0 ? value : null;
}
