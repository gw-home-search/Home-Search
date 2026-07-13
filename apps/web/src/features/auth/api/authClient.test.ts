import { afterEach, describe, expect, it, vi } from 'vitest';

import { createAuthClient } from './authClient';

describe('authClient', () => {
  afterEach(() => vi.restoreAllMocks());

  it('refresh cookie는 access에만 포함하고 JWT는 /me에만 전달한다', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7,
        provider: 'GOOGLE',
        displayName: '홍길동',
        profileImage: null,
      }));
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');
    const sessionStorageSpy = vi.spyOn(window.sessionStorage, 'setItem');

    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await expect(client.restoreSession()).resolves.toEqual({
      kind: 'authenticated',
      currentUser: {
        userId: 7,
        provider: 'google',
        displayName: '홍길동',
        profileImage: null,
      },
    });

    expect(fetchMock).toHaveBeenNthCalledWith(1, 'http://localhost:8082/auth/access', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(2, 'http://localhost:8082/api/v1/users/me', expect.objectContaining({
      method: 'GET',
      headers: expect.objectContaining({ Authorization: 'Bearer memory-jwt' }),
    }));
    expect(fetchMock.mock.calls[1]?.[1]).not.toHaveProperty('credentials');
    expect(localStorageSpy).not.toHaveBeenCalled();
    expect(sessionStorageSpy).not.toHaveBeenCalled();
  });

  it('401, network error, invalid body를 구분하고 restore rotation을 single-flight로 고정한다', async () => {
    const anonymousFetch = vi.fn<typeof fetch>().mockResolvedValue(errorResponse(401));
    const anonymous = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: anonymousFetch });
    const first = anonymous.restoreSession();
    const second = anonymous.restoreSession();

    await expect(first).resolves.toEqual({ kind: 'anonymous' });
    await expect(second).resolves.toEqual({ kind: 'anonymous' });
    expect(anonymousFetch).toHaveBeenCalledTimes(1);

    const unavailable = createAuthClient({
      baseUrl: 'http://localhost:8082',
      fetch: vi.fn<typeof fetch>().mockRejectedValue(new TypeError('offline')),
    });
    await expect(unavailable.restoreSession()).resolves.toEqual({ kind: 'unavailable' });

    const invalid = createAuthClient({
      baseUrl: 'http://localhost:8082',
      fetch: vi.fn<typeof fetch>().mockResolvedValue(jsonResponse({ accessToken: 123 })),
    });
    await expect(invalid.restoreSession()).resolves.toEqual({ kind: 'unavailable' });
  });

  it('logout만 cookie credential을 보내고 provider 경로는 allowlist로 조합한다', async () => {
    const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 204 }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });

    expect(client.authorizationUrl('kakao')).toBe('http://localhost:8082/oauth2/authorization/kakao');
    await expect(client.logout()).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith('http://localhost:8082/auth/logout', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
    }));
  });

  it('logout 응답 실패에도 memory access token을 폐기한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(503));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.logout()).rejects.toThrow('Logout request failed');
    await expect(client.authenticatedRequest('/api/v1/favorites/501'))
      .rejects.toThrow('Authentication required');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('/me 401은 세션 만료로 분류한다', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'short-lived-jwt' }))
      .mockResolvedValueOnce(errorResponse(401));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });

    await expect(client.restoreSession()).resolves.toEqual({ kind: 'expired' });
  });

  it('auth request는 timeout 뒤 unavailable로 비차단 종료한다', async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.fn<typeof fetch>((_input, init) => new Promise((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
      }));
      const client = createAuthClient({
        baseUrl: 'http://localhost:8082',
        fetch: fetchMock,
        timeoutMs: 50,
      });
      const result = client.restoreSession();

      await vi.advanceTimersByTimeAsync(50);
      await expect(result).resolves.toEqual({ kind: 'unavailable' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('authenticated request는 상대 user API에 Bearer만 보내고 401 뒤 token을 폐기한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-only-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(errorResponse(401));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/favorites/501', { method: 'PUT' }))
      .resolves.toMatchObject({ status: 204 });
    expect(fetchMock).toHaveBeenNthCalledWith(3, 'http://localhost:8082/api/v1/favorites/501', expect.objectContaining({
      method: 'PUT',
      credentials: 'omit',
      headers: expect.any(Headers),
    }));
    expect((fetchMock.mock.calls[2]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer memory-only-jwt');
    await expect(client.authenticatedRequest('https://evil.example/api/v1/favorites/501')).rejects.toThrow('relative user API path');
    await expect(client.authenticatedRequest('/auth/access')).rejects.toThrow('relative user API path');

    await expect(client.authenticatedRequest('/api/v1/favorites/501')).resolves.toMatchObject({ status: 401 });
    await expect(client.authenticatedRequest('/api/v1/favorites/501')).rejects.toThrow('Authentication required');
  });
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function errorResponse(status: number): Response {
  return new Response(null, { status });
}
