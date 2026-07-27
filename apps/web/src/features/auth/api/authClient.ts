import type { CurrentUser, OAuthProvider } from '../authTypes';
import { resolveUserApiUrl } from './resolveUserApiUrl';

const AUTH_REQUEST_TIMEOUT_MS = 5_000;
const CHATBOT_REQUEST_TIMEOUT_MS = 75_000;
const AUTH_MUTATION_LOCK_NAME = 'home-search-auth-refresh';
const OAUTH_PROVIDERS: readonly OAuthProvider[] = ['google', 'kakao', 'naver'];

export type AuthRestoreResult =
  | { kind: 'authenticated'; currentUser: CurrentUser }
  | { kind: 'anonymous' }
  | { kind: 'expired' }
  | { kind: 'unavailable' };

export type AuthClient = {
  authenticatedRequest(
    path: string,
    init?: RequestInit,
    target?: 'user' | 'public',
  ): Promise<Response>;
  authorizationUrl(provider: OAuthProvider): string;
  logout(): Promise<void>;
  restoreSession(options?: { force?: boolean }): Promise<AuthRestoreResult>;
};

type AuthClientOptions = {
  baseUrl?: string;
  publicApiBaseUrl?: string;
  fetch?: typeof fetch;
  timeoutMs?: number;
  chatbotTimeoutMs?: number;
};

type AccessRefreshResult =
  | { kind: 'refreshed'; accessToken: string }
  | { kind: 'expired'; response: Response }
  | { kind: 'unavailable' }
  | { kind: 'cancelled' };

export function createAuthClient(options: AuthClientOptions = {}): AuthClient {
  const baseUrl = options.baseUrl ?? resolveUserApiUrl();
  const publicApiBaseUrl = options.publicApiBaseUrl ?? resolvePublicApiBaseUrl();
  const fetchImplementation = options.fetch ?? globalThis.fetch;
  const timeoutMs = options.timeoutMs ?? AUTH_REQUEST_TIMEOUT_MS;
  const chatbotTimeoutMs = options.chatbotTimeoutMs ?? CHATBOT_REQUEST_TIMEOUT_MS;
  let restorePromise: Promise<AuthRestoreResult> | null = null;
  let accessRefreshPromise: Promise<AccessRefreshResult> | null = null;
  let accessToken: string | null = null;
  let authGeneration = 0;

  async function request(
    requestBaseUrl: string,
    path: string,
    init: RequestInit,
    requestTimeoutMs = timeoutMs,
  ): Promise<Response> {
    const controller = new AbortController();
    let timedOut = false;
    const abortFromCaller = () => controller.abort();
    if (init.signal?.aborted) controller.abort();
    else init.signal?.addEventListener('abort', abortFromCaller, { once: true });
    const timeout = globalThis.setTimeout(() => {
      timedOut = true;
      controller.abort();
    }, requestTimeoutMs);
    try {
      if (controller.signal.aborted) {
        throw controller.signal.reason ?? new DOMException('The operation was aborted', 'AbortError');
      }
      return await fetchImplementation(`${requestBaseUrl}${path}`, { ...init, signal: controller.signal });
    } catch (error) {
      if (timedOut) throw new DOMException('Request timed out', 'TimeoutError');
      throw error;
    } finally {
      globalThis.clearTimeout(timeout);
      init.signal?.removeEventListener('abort', abortFromCaller);
    }
  }

  async function performAccessRefresh(generation: number): Promise<AccessRefreshResult> {
    try {
      return await withAuthMutationLock(async () => {
        const accessResponse = await request(baseUrl, '/auth/access', {
          method: 'POST',
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });
        if (generation !== authGeneration) return { kind: 'cancelled' };
        if (accessResponse.status === 401) {
          accessToken = null;
          restorePromise = null;
          return { kind: 'expired', response: accessResponse };
        }
        if (!accessResponse.ok) return { kind: 'unavailable' };

        const accessBody: unknown = await accessResponse.json();
        if (!isAccessTokenResponse(accessBody)) return { kind: 'unavailable' };
        if (generation !== authGeneration) return { kind: 'cancelled' };
        accessToken = accessBody.accessToken;
        return { kind: 'refreshed', accessToken: accessBody.accessToken };
      });
    } catch {
      return { kind: 'unavailable' };
    }
  }

  function refreshAccessToken(): Promise<AccessRefreshResult> {
    if (accessRefreshPromise != null) return accessRefreshPromise;
    const generation = authGeneration;
    const pending = performAccessRefresh(generation);
    accessRefreshPromise = pending;
    void pending.then(
      () => {
        if (accessRefreshPromise === pending) accessRefreshPromise = null;
      },
      () => {
        if (accessRefreshPromise === pending) accessRefreshPromise = null;
      },
    );
    return pending;
  }

  async function performRestore(): Promise<AuthRestoreResult> {
    const generation = authGeneration;
    const refresh = await refreshAccessToken();
    if (refresh.kind === 'expired') return { kind: 'anonymous' };
    if (refresh.kind === 'cancelled') return { kind: 'anonymous' };
    if (refresh.kind === 'unavailable') return { kind: 'unavailable' };
    if (generation !== authGeneration) return { kind: 'anonymous' };

    try {
      const meResponse = await request(baseUrl, '/api/v1/users/me', {
        method: 'GET',
        headers: {
          Accept: 'application/json',
          Authorization: `Bearer ${refresh.accessToken}`,
        },
      });
      if (generation !== authGeneration) return { kind: 'anonymous' };
      if (!meResponse.ok) return { kind: 'unavailable' };

      const currentUser = parseCurrentUser(await meResponse.json());
      if (currentUser == null) return { kind: 'unavailable' };
      return { kind: 'authenticated', currentUser };
    } catch {
      return { kind: 'unavailable' };
    }
  }

  async function protectedRequest(
    path: string,
    init: RequestInit,
    target: 'user' | 'public',
    token: string,
  ): Promise<Response> {
    const headers = new Headers(init.headers);
    headers.set('Authorization', `Bearer ${token}`);
    return request(target === 'public' ? publicApiBaseUrl : baseUrl, path, {
      ...init,
      credentials: 'omit',
      headers,
    }, target === 'public' ? chatbotTimeoutMs : timeoutMs);
  }

  return {
    async authenticatedRequest(path, init = {}, target = 'user') {
      const chatbotPath = /^\/api\/v1\/chatbot\/query(?:\/stream)?$/;
      const userPath = /^\/api\/v1\/(?:users(?:[/?]|$)|favorites(?:[/?]|$))/;
      if (path.startsWith('//') || (target === 'public' ? !chatbotPath.test(path) : !userPath.test(path))) {
        throw new Error(`authenticatedRequest requires a relative ${target === 'public' ? 'chatbot' : 'user'} API path`);
      }
      const tokenUsed = accessToken;
      const generation = authGeneration;
      if (tokenUsed == null) throw new Error('Authentication required');

      const response = await protectedRequest(path, init, target, tokenUsed);
      if (response.status !== 401) return response;
      if (generation !== authGeneration) throw new Error('Authentication required');

      let retryToken = accessToken;
      if (retryToken == null || retryToken === tokenUsed) {
        const refresh = await refreshAccessToken();
        if (refresh.kind === 'expired') return refresh.response;
        if (refresh.kind === 'cancelled') throw new Error('Authentication required');
        if (refresh.kind === 'unavailable') throw new Error('Authentication unavailable');
        retryToken = refresh.accessToken;
      }
      if (generation !== authGeneration || accessToken == null) throw new Error('Authentication required');

      const retryResponse = await protectedRequest(path, init, target, retryToken);
      if (retryResponse.status === 401) throw new Error('Authentication unavailable');
      return retryResponse;
    },
    authorizationUrl(provider) {
      if (!OAUTH_PROVIDERS.includes(provider)) throw new Error('Unsupported OAuth provider');
      return `${baseUrl}/oauth2/authorization/${provider}`;
    },
    async logout() {
      authGeneration += 1;
      accessToken = null;
      restorePromise = null;
      try {
        await accessRefreshPromise;
        const response = await withAuthMutationLock(() => request(baseUrl, '/auth/logout', {
          method: 'POST',
          credentials: 'include',
          headers: { Accept: 'application/json' },
        }));
        if (response.status !== 204) throw new Error('Logout request failed');
      } finally {
        accessToken = null;
        restorePromise = null;
        accessRefreshPromise = null;
      }
    },
    restoreSession({ force = false } = {}) {
      if (force) restorePromise = null;
      restorePromise ??= performRestore();
      return restorePromise;
    },
  };
}

function withAuthMutationLock<T>(operation: () => Promise<T>): Promise<T> {
  if (typeof navigator === 'undefined' || navigator.locks == null) return operation();
  const locked = navigator.locks.request<Promise<T>>(
    AUTH_MUTATION_LOCK_NAME,
    { mode: 'exclusive' },
    operation,
  );
  return locked as unknown as Promise<T>;
}

function resolvePublicApiBaseUrl(): string {
  const configured = (import.meta.env.VITE_API_SERVER_IP as string | undefined)?.trim();
  if (!configured) {
    if (import.meta.env.DEV || import.meta.env.MODE === 'test') return 'http://localhost:8080';
    return window.location.origin;
  }
  const candidate = /^[a-z][a-z\d+\-.]*:\/\//i.test(configured) ? configured : `http://${configured}`;
  const url = new URL(candidate);
  if ((url.protocol !== 'http:' && url.protocol !== 'https:')
    || url.username
    || url.password
    || url.search
    || url.hash
    || (url.pathname !== '/' && url.pathname !== '')) {
    throw new Error('VITE_API_SERVER_IP must be an HTTP origin');
  }
  return url.origin;
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
