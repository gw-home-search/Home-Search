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
  trades: TradeItem[];
};

type ParcelTradesResponse = {
  parcelId?: number | string;
  trades?: unknown;
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

export async function fetchParcelTrades(parcelId: number): Promise<ParcelTrades> {
  const response = await fetch(resolveApiUrl(`${TRADE_PATH}/${parcelId}`), {
    method: 'GET',
  });

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
  if (!Array.isArray(payload.trades)) {
    throw new Error('Invalid public API parcel trade response: trades must be an array');
  }

  return {
    parcelId: toRequiredNumber(payload.parcelId, 'parcelId'),
    trades: payload.trades.map((trade) => {
      if (!isTradeItemResponse(trade)) {
        throw new Error('Invalid public API parcel trade response: trade item must be an object');
      }

      return normalizeTradeItem(trade);
    }),
  };
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
