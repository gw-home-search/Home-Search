import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { fetchWithTimeout } from '../../../shared/http/fetchWithTimeout';
import { readValidatedJson, requestFailureFromResponse } from '../../../shared/http/requestFailure';

export type TradeAreaOption = {
  exclArea: number;
  tradeCount: number;
  latestDealDate: string;
};

export type TradeAreas = {
  complexId: number;
  defaultExclArea: number | null;
  areas: TradeAreaOption[];
};

const COMPLEX_PATH = '/api/v1/complex';
const ISO_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

export async function fetchTradeAreas(complexId: number, signal?: AbortSignal): Promise<TradeAreas> {
  const response = await fetchWithTimeout(
    resolveApiUrl(`${COMPLEX_PATH}/${complexId}/trade-areas`),
    { method: 'GET', signal },
  );

  if (!response.ok) {
    throw await requestFailureFromResponse(response, {
      service: 'property-data',
      operation: 'trade-areas',
    });
  }

  return readValidatedJson(response, {
    service: 'property-data',
    operation: 'trade-areas',
  }, normalizeTradeAreas);
}

function normalizeTradeAreas(payload: unknown): TradeAreas {
  if (!isRecord(payload) || !Array.isArray(payload.areas)) {
    throw new Error('Invalid public API trade areas response: expected an object with areas');
  }
  const complexId = requiredPositiveNumber(payload.complexId, 'complexId');
  const defaultExclArea = nullablePositiveNumber(payload.defaultExclArea, 'defaultExclArea');
  const areas = payload.areas.map((value) => normalizeTradeArea(value));
  for (let index = 1; index < areas.length; index += 1) {
    if (areas[index - 1].exclArea >= areas[index].exclArea) {
      throw new Error('Invalid public API trade areas response: areas must be strictly ascending');
    }
  }
  if (areas.length === 0 && defaultExclArea != null) {
    throw new Error('Invalid public API trade areas response: empty areas cannot have a default');
  }
  if (defaultExclArea != null && !areas.some((area) => area.exclArea === defaultExclArea)) {
    throw new Error('Invalid public API trade areas response: default must be one of areas');
  }
  return { complexId, defaultExclArea, areas };
}

function normalizeTradeArea(value: unknown): TradeAreaOption {
  if (!isRecord(value)) {
    throw new Error('Invalid public API trade areas response: area must be an object');
  }
  const latestDealDate = value.latestDealDate;
  if (typeof latestDealDate !== 'string' || !ISO_DATE_PATTERN.test(latestDealDate)) {
    throw new Error('Invalid public API trade areas response: latestDealDate must be YYYY-MM-DD');
  }
  const tradeCount = value.tradeCount;
  if (typeof tradeCount !== 'number' || !Number.isSafeInteger(tradeCount) || tradeCount < 1) {
    throw new Error('Invalid public API trade areas response: tradeCount must be a positive integer');
  }
  return {
    exclArea: requiredPositiveNumber(value.exclArea, 'exclArea'),
    tradeCount,
    latestDealDate,
  };
}

function requiredPositiveNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
    throw new Error(`Invalid public API trade areas response: ${field} must be a positive number`);
  }
  return value;
}

function nullablePositiveNumber(value: unknown, field: string): number | null {
  return value == null ? null : requiredPositiveNumber(value, field);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}
