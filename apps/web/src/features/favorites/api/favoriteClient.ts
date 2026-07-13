import type { FavoriteListPage, FavoriteStatus } from '../favoriteTypes';

type AuthenticatedRequest = (path: string, init?: RequestInit) => Promise<Response>;
export type FavoriteErrorKind = 'request' | 'limit' | 'session-expired' | 'invalid-response';

export class FavoriteClientError extends Error {
  constructor(readonly kind: FavoriteErrorKind) {
    super('Favorite request failed');
    this.name = 'FavoriteClientError';
  }
}

export type FavoriteClient = {
  get(complexId: number, signal?: AbortSignal): Promise<FavoriteStatus>;
  list(page?: number, size?: number, signal?: AbortSignal): Promise<FavoriteListPage>;
  save(complexId: number, signal?: AbortSignal): Promise<void>;
  remove(complexId: number, signal?: AbortSignal): Promise<void>;
};

export function createFavoriteClient(request: AuthenticatedRequest): FavoriteClient {
  async function mutation(complexId: number, method: 'PUT' | 'DELETE', signal?: AbortSignal) {
    validateComplexId(complexId);
    const response = await request(`/api/v1/favorites/${complexId}`, { method, signal });
    if (response.status === 204) return;
    if (response.status === 401) throw new FavoriteClientError('session-expired');
    if (response.status === 409 && await hasErrorCode(response, 'FAVORITE_LIMIT_REACHED')) {
      throw new FavoriteClientError('limit');
    }
    throw new FavoriteClientError('request');
  }

  return {
    async get(complexId, signal) {
      validateComplexId(complexId);
      const response = await request(`/api/v1/favorites/${complexId}`, { method: 'GET', signal });
      if (response.status === 401) throw new FavoriteClientError('session-expired');
      if (!response.ok) throw new FavoriteClientError('request');
      const value = await safeJson(response);
      if (!isFavoriteStatus(value) || value.complexId !== complexId) throw new FavoriteClientError('invalid-response');
      return value;
    },
    async list(page = 0, size = 20, signal) {
      if (!Number.isSafeInteger(page) || page < 0 || !Number.isSafeInteger(size) || size < 1 || size > 100) {
        throw new FavoriteClientError('request');
      }
      const response = await request(`/api/v1/favorites?page=${page}&size=${size}`, { method: 'GET', signal });
      if (response.status === 401) throw new FavoriteClientError('session-expired');
      if (!response.ok) throw new FavoriteClientError('request');
      const value = await safeJson(response);
      if (!isFavoriteList(value)) throw new FavoriteClientError('invalid-response');
      return value;
    },
    save(complexId, signal) { return mutation(complexId, 'PUT', signal); },
    remove(complexId, signal) { return mutation(complexId, 'DELETE', signal); },
  };
}

function validateComplexId(complexId: number) {
  if (!Number.isSafeInteger(complexId) || complexId <= 0) throw new FavoriteClientError('request');
}

async function safeJson(response: Response): Promise<unknown> {
  try { return await response.json(); } catch { throw new FavoriteClientError('invalid-response'); }
}

async function hasErrorCode(response: Response, code: string): Promise<boolean> {
  try {
    const value: unknown = await response.json();
    return isRecord(value) && value.code === code;
  } catch { return false; }
}

function isFavoriteStatus(value: unknown): value is FavoriteStatus {
  return isRecord(value)
    && isPositiveInteger(value.complexId)
    && typeof value.favorite === 'boolean'
    && (value.savedAt === null || isInstant(value.savedAt));
}

function isFavoriteList(value: unknown): value is FavoriteListPage {
  return isRecord(value) && Array.isArray(value.content)
    && value.content.every((item) => isRecord(item) && isPositiveInteger(item.complexId) && isInstant(item.savedAt))
    && isNonNegativeInteger(value.page) && isPositiveInteger(value.size)
    && isNonNegativeInteger(value.totalElements) && isNonNegativeInteger(value.totalPages);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
function isPositiveInteger(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value > 0; }
function isNonNegativeInteger(value: unknown): value is number { return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0; }
function isInstant(value: unknown): value is string { return typeof value === 'string' && value.length > 0 && !Number.isNaN(Date.parse(value)); }
