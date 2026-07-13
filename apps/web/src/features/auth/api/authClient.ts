import type { CurrentUser, OAuthProvider } from '../authTypes';
import { resolveUserApiUrl } from './resolveUserApiUrl';

const AUTH_REQUEST_TIMEOUT_MS = 5_000;
const OAUTH_PROVIDERS: readonly OAuthProvider[] = ['google', 'kakao', 'naver'];

export type AuthRestoreResult =
  | { kind: 'authenticated'; currentUser: CurrentUser }
  | { kind: 'anonymous' }
  | { kind: 'expired' }
  | { kind: 'unavailable' };

export type AuthClient = {
  authenticatedRequest(path: string, init?: RequestInit): Promise<Response>;
  authorizationUrl(provider: OAuthProvider): string;
  logout(): Promise<void>;
  restoreSession(options?: { force?: boolean }): Promise<AuthRestoreResult>;
};

type AuthClientOptions = {
  baseUrl?: string;
  fetch?: typeof fetch;
  timeoutMs?: number;
};

export function createAuthClient(options: AuthClientOptions = {}): AuthClient {
  const baseUrl = options.baseUrl ?? resolveUserApiUrl();
  const fetchImplementation = options.fetch ?? globalThis.fetch;
  const timeoutMs = options.timeoutMs ?? AUTH_REQUEST_TIMEOUT_MS;
  let restorePromise: Promise<AuthRestoreResult> | null = null;
  let accessToken: string | null = null;

  async function request(path: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const abortFromCaller = () => controller.abort();
    if (init.signal?.aborted) controller.abort();
    else init.signal?.addEventListener('abort', abortFromCaller, { once: true });
    const timeout = globalThis.setTimeout(() => controller.abort(), timeoutMs);
    try {
      return await fetchImplementation(`${baseUrl}${path}`, { ...init, signal: controller.signal });
    } finally {
      globalThis.clearTimeout(timeout);
      init.signal?.removeEventListener('abort', abortFromCaller);
    }
  }

  async function performRestore(): Promise<AuthRestoreResult> {
    accessToken = null;
    try {
      const accessResponse = await request('/auth/access', {
        method: 'POST',
        credentials: 'include',
        headers: { Accept: 'application/json' },
      });
      if (accessResponse.status === 401) return { kind: 'anonymous' };
      if (!accessResponse.ok) return { kind: 'unavailable' };

      const accessBody: unknown = await accessResponse.json();
      if (!isAccessTokenResponse(accessBody)) return { kind: 'unavailable' };
      accessToken = accessBody.accessToken;

      const meResponse = await request('/api/v1/users/me', {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${accessToken}`,
        },
      });
      if (meResponse.status === 401) {
        accessToken = null;
        return { kind: 'expired' };
      }
      if (!meResponse.ok) {
        accessToken = null;
        return { kind: 'unavailable' };
      }

      const currentUser = parseCurrentUser(await meResponse.json());
      if (currentUser == null) {
        accessToken = null;
        return { kind: 'unavailable' };
      }
      return { kind: 'authenticated', currentUser };
    } catch {
      accessToken = null;
      return { kind: 'unavailable' };
    }
  }

  return {
    async authenticatedRequest(path, init = {}) {
      if (!path.startsWith('/api/v1/') || path.startsWith('//')) {
        throw new Error('authenticatedRequest requires a relative user API path');
      }
      if (accessToken == null) throw new Error('Authentication required');
      const headers = new Headers(init.headers);
      headers.set('Authorization', `Bearer ${accessToken}`);
      const response = await request(path, { ...init, credentials: 'omit', headers });
      if (response.status === 401) {
        accessToken = null;
        restorePromise = null;
      }
      return response;
    },
    authorizationUrl(provider) {
      if (!OAUTH_PROVIDERS.includes(provider)) throw new Error('Unsupported OAuth provider');
      return `${baseUrl}/oauth2/authorization/${provider}`;
    },
    async logout() {
      try {
        const response = await request('/auth/logout', {
          method: 'POST',
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });
        if (response.status !== 204) throw new Error('Logout request failed');
      } finally {
        accessToken = null;
        restorePromise = null;
      }
    },
    restoreSession({ force = false } = {}) {
      if (force) restorePromise = null;
      restorePromise ??= performRestore();
      return restorePromise;
    },
  };
}

function isAccessTokenResponse(value: unknown): value is { accessToken: string } {
  return isRecord(value) && typeof value.accessToken === 'string' && value.accessToken.length > 0;
}

function parseCurrentUser(value: unknown): CurrentUser | null {
  if (!isRecord(value)) return null;
  const provider = typeof value.provider === 'string' ? value.provider.toLowerCase() : '';
  if (
    typeof value.userId !== 'number'
    || !Number.isSafeInteger(value.userId)
    || value.userId <= 0
    || !OAUTH_PROVIDERS.includes(provider as OAuthProvider)
    || typeof value.displayName !== 'string'
    || value.displayName.trim().length === 0
    || (value.profileImage !== null && typeof value.profileImage !== 'string')
  ) {
    return null;
  }
  return {
    userId: value.userId,
    provider: provider as OAuthProvider,
    displayName: value.displayName.trim(),
    profileImage: value.profileImage,
  };
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value != null && !Array.isArray(value);
}
