import { afterEach, describe, expect, it, vi } from 'vitest';

import { createAuthClient } from './authClient';

describe('authClient 인증 요청', () => {
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

  it('/me 401은 refresh token 만료가 아닌 인증 서비스 장애로 분류한다', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'short-lived-jwt' }))
      .mockResolvedValueOnce(errorResponse(401));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });

    await expect(client.restoreSession()).resolves.toEqual({ kind: 'unavailable' });
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

  it('보호 요청 401은 access를 갱신하고 새 Bearer로 원 요청을 한 번 재시도한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-only-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'rotated-memory-jwt' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
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
    expect(fetchMock).toHaveBeenNthCalledWith(4, 'http://localhost:8082/auth/access', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
    }));
    expect(fetchMock).toHaveBeenNthCalledWith(5, 'http://localhost:8082/api/v1/favorites/501', expect.objectContaining({
      method: 'PUT',
      credentials: 'omit',
      headers: expect.any(Headers),
    }));
    expect((fetchMock.mock.calls[4]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer rotated-memory-jwt');
    await expect(client.authenticatedRequest('https://evil.example/api/v1/favorites/501')).rejects.toThrow('relative user API path');
    await expect(client.authenticatedRequest('/auth/access')).rejects.toThrow('relative user API path');
  });

  it('동시에 401이 된 보호 요청은 refresh 한 번을 공유하고 모두 재시도한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'shared-new-jwt' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    const [first, second] = await Promise.all([
      client.authenticatedRequest('/api/v1/favorites/501'),
      client.authenticatedRequest('/api/v1/favorites/502'),
    ]);

    expect(first.status).toBe(204);
    expect(second.status).toBe(204);
    expect(fetchMock.mock.calls.filter(([input]) => input === 'http://localhost:8082/auth/access')).toHaveLength(2);
    expect((fetchMock.mock.calls[5]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer shared-new-jwt');
    expect((fetchMock.mock.calls[6]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer shared-new-jwt');
  });

  it('갱신 완료 뒤 늦게 도착한 old-token 401은 추가 rotation 없이 최신 token으로 재시도한다', async () => {
    let resolveLateResponse!: (response: Response) => void;
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveLateResponse = resolve;
      }))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new-jwt' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    const refreshedRequest = client.authenticatedRequest('/api/v1/favorites/501');
    const lateRequest = client.authenticatedRequest('/api/v1/favorites/502');
    await expect(refreshedRequest).resolves.toMatchObject({ status: 204 });
    resolveLateResponse(errorResponse(401));
    await expect(lateRequest).resolves.toMatchObject({ status: 204 });

    expect(fetchMock.mock.calls.filter(([input]) => input === 'http://localhost:8082/auth/access')).toHaveLength(2);
    expect((fetchMock.mock.calls[6]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer new-jwt');
  });

  it('refresh 401만 실제 세션 만료로 반환하고 memory token을 폐기한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'expired-access-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(errorResponse(401));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/favorites/501')).resolves.toMatchObject({ status: 401 });
    expect(fetchMock).toHaveBeenNthCalledWith(4, 'http://localhost:8082/auth/access', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
    }));
    await expect(client.authenticatedRequest('/api/v1/favorites/501')).rejects.toThrow('Authentication required');
  });

  refreshUnavailableCase('403', () => errorResponse(403));
  refreshUnavailableCase('5xx', () => errorResponse(503));
  refreshUnavailableCase('invalid body', () => jsonResponse({ accessToken: 123 }));

  it('refresh timeout은 인증 서비스 장애로 분류한다', async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.fn<typeof fetch>()
        .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
        .mockResolvedValueOnce(jsonResponse({
          userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
        }))
        .mockResolvedValueOnce(errorResponse(401))
        .mockImplementationOnce((_input, init) => new Promise((_resolve, reject) => {
          init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
        }));
      const client = createAuthClient({
        baseUrl: 'http://localhost:8082',
        fetch: fetchMock,
        timeoutMs: 50,
      });
      await client.restoreSession();

      const request = client.authenticatedRequest('/api/v1/favorites/501');
      const assertion = expect(request).rejects.toThrow('Authentication unavailable');
      await vi.advanceTimersByTimeAsync(50);

      await assertion;
    } finally {
      vi.useRealTimers();
    }
  });

  it('갱신 직후 재시도 401은 세션 만료가 아닌 인증 서비스 장애로 분류한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new-jwt' }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/favorites/501'))
      .rejects.toThrow('Authentication unavailable');
    await expect(client.authenticatedRequest('/api/v1/favorites/501'))
      .resolves.toMatchObject({ status: 204 });

    expect((fetchMock.mock.calls[5]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer new-jwt');
  });

  it('chatbot POST의 JSON body와 header를 refresh 뒤 재시도에도 보존한다', async () => {
    const body = JSON.stringify({ question: '잠실엘스 최근 거래' });
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new-jwt' }))
      .mockResolvedValueOnce(jsonResponse({ success: true }));
    const client = createAuthClient({
      baseUrl: 'http://localhost:8082',
      publicApiBaseUrl: 'http://localhost:8080',
      fetch: fetchMock,
    });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/chatbot/query', {
      method: 'POST',
      body,
      headers: { 'Content-Type': 'application/json' },
    }, 'public')).resolves.toMatchObject({ status: 200 });

    expect(fetchMock.mock.calls[2]?.[1]?.body).toBe(body);
    expect(fetchMock.mock.calls[4]?.[1]?.body).toBe(body);
    expect((fetchMock.mock.calls[4]?.[1]?.headers as Headers).get('Content-Type')).toBe('application/json');
    expect((fetchMock.mock.calls[4]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer new-jwt');
  });

  it('caller abort는 시작된 재시도 요청의 signal과 반환 Promise에 전달된다', async () => {
    let retrySignal: AbortSignal | null | undefined;
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'new-jwt' }))
      .mockImplementationOnce((_input, init) => new Promise((_resolve, reject) => {
        retrySignal = init?.signal;
        init?.signal?.addEventListener(
          'abort',
          () => reject(new DOMException('aborted', 'AbortError')),
          { once: true },
        );
      }));
    const client = createAuthClient({
      baseUrl: 'http://localhost:8082',
      publicApiBaseUrl: 'http://localhost:8080',
      fetch: fetchMock,
    });
    await client.restoreSession();
    const caller = new AbortController();

    const request = client.authenticatedRequest('/api/v1/chatbot/query', {
      method: 'POST',
      body: JSON.stringify({ question: '잠실엘스 최근 거래' }),
      signal: caller.signal,
    }, 'public');
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(5));
    caller.abort();

    await assertion;
    expect(retrySignal?.aborted).toBe(true);
  });

  it('refresh 대기 중 caller가 abort되면 rotation은 끝내되 원 요청은 재시도하지 않는다', async () => {
    let resolveRefresh!: (response: Response) => void;
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveRefresh = resolve;
      }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();
    const caller = new AbortController();

    const request = client.authenticatedRequest('/api/v1/favorites/501', { signal: caller.signal });
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' });
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
    caller.abort();
    resolveRefresh(jsonResponse({ accessToken: 'new-jwt' }));

    await assertion;
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it('서로 다른 client의 refresh는 Web Lock으로 직렬화하고 token을 저장소에 공유하지 않는다', async () => {
    let lockQueue = Promise.resolve();
    let activeLockCount = 0;
    let maximumActiveLockCount = 0;
    const lockRequest = vi.fn((name: string, options: LockOptions, callback: (lock: Lock) => Promise<unknown>) => {
      const result = lockQueue.then(async () => {
        activeLockCount += 1;
        maximumActiveLockCount = Math.max(maximumActiveLockCount, activeLockCount);
        try {
          return await callback({ name, mode: options.mode ?? 'exclusive' } as Lock);
        } finally {
          activeLockCount -= 1;
        }
      });
      lockQueue = result.then(() => undefined, () => undefined);
      return result;
    });
    const restoreLocks = stubNavigatorLocks({ request: lockRequest } as unknown as LockManager);
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem');
    let tokenSequence = 0;
    const fetchMock = vi.fn<typeof fetch>(async (input) => {
      if (String(input).endsWith('/auth/access')) {
        tokenSequence += 1;
        return jsonResponse({ accessToken: `tab-jwt-${tokenSequence}` });
      }
      return jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      });
    });
    try {
      const firstTab = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
      const secondTab = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });

      await expect(Promise.all([firstTab.restoreSession(), secondTab.restoreSession()])).resolves.toEqual([
        expect.objectContaining({ kind: 'authenticated' }),
        expect.objectContaining({ kind: 'authenticated' }),
      ]);

      expect(maximumActiveLockCount).toBe(1);
      expect(lockRequest).toHaveBeenCalledTimes(2);
      expect(lockRequest.mock.calls.every(([name]) => name === 'home-search-auth-refresh')).toBe(true);
      expect(localStorageSpy).not.toHaveBeenCalled();
    } finally {
      restoreLocks();
    }
  });

  it('refresh 중 logout은 같은 Web Lock 뒤 cookie를 폐기하고 늦은 refresh가 token을 복원하지 못한다', async () => {
    let lockQueue = Promise.resolve();
    const lockRequest = vi.fn((name: string, options: LockOptions, callback: (lock: Lock) => Promise<unknown>) => {
      const result = lockQueue.then(() => callback({ name, mode: options.mode ?? 'exclusive' } as Lock));
      lockQueue = result.then(() => undefined, () => undefined);
      return result;
    });
    const restoreLocks = stubNavigatorLocks({ request: lockRequest } as unknown as LockManager);
    let resolveRefresh!: (response: Response) => void;
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockImplementationOnce(() => new Promise((resolve) => {
        resolveRefresh = resolve;
      }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    try {
      const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
      await client.restoreSession();

      const protectedCall = client.authenticatedRequest('/api/v1/favorites/501');
      const protectedAssertion = expect(protectedCall).rejects.toThrow('Authentication required');
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4));
      const logout = client.logout();
      resolveRefresh(jsonResponse({ accessToken: 'late-jwt' }));

      await protectedAssertion;
      await expect(logout).resolves.toBeUndefined();
      await expect(client.authenticatedRequest('/api/v1/favorites/501'))
        .rejects.toThrow('Authentication required');

      expect(fetchMock.mock.calls.map(([input]) => String(input))).toEqual([
        'http://localhost:8082/auth/access',
        'http://localhost:8082/api/v1/users/me',
        'http://localhost:8082/api/v1/favorites/501',
        'http://localhost:8082/auth/access',
        'http://localhost:8082/auth/logout',
      ]);
      expect(lockRequest.mock.calls.every(([name]) => name === 'home-search-auth-refresh')).toBe(true);
    } finally {
      restoreLocks();
    }
  });

  it('favorite 목록의 허용된 pagination query를 user API로 전달한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-only-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(jsonResponse({
        content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
      }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/favorites?page=0&size=20'))
      .resolves.toMatchObject({ status: 200 });
    expect(fetchMock).toHaveBeenNthCalledWith(
      3,
      'http://localhost:8082/api/v1/favorites?page=0&size=20',
      expect.objectContaining({ credentials: 'omit' }),
    );
  });

  it('chatbot request는 allowlist된 public API 경로에만 memory JWT를 전달한다', async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-only-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(jsonResponse({ success: true, answer: '근거 답변' }));
    const client = createAuthClient({
      baseUrl: 'http://localhost:8082',
      publicApiBaseUrl: 'http://localhost:8080',
      fetch: fetchMock,
    });
    await client.restoreSession();

    await expect(client.authenticatedRequest(
      '/api/v1/chatbot/query',
      { method: 'POST' },
      'public',
    )).resolves.toMatchObject({ status: 200 });
    expect(fetchMock).toHaveBeenNthCalledWith(3, 'http://localhost:8080/api/v1/chatbot/query', expect.objectContaining({
      method: 'POST',
      credentials: 'omit',
      headers: expect.any(Headers),
    }));
    expect((fetchMock.mock.calls[2]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer memory-only-jwt');

    await expect(client.authenticatedRequest('/api/v1/map/regions', {}, 'public'))
      .rejects.toThrow('relative chatbot API path');
    await expect(client.authenticatedRequest('https://evil.example/api/v1/chatbot/query', {}, 'public'))
      .rejects.toThrow('relative chatbot API path');
  });

  it('chatbot request는 짧은 auth timeout과 분리된 timeout을 사용한다', async () => {
    vi.useFakeTimers();
    try {
      let chatbotSignal: AbortSignal | null | undefined;
      const fetchMock = vi.fn<typeof fetch>()
        .mockResolvedValueOnce(jsonResponse({ accessToken: 'memory-only-jwt' }))
        .mockResolvedValueOnce(jsonResponse({
          userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
        }))
        .mockImplementationOnce((_input, init) => new Promise((_resolve, reject) => {
          chatbotSignal = init?.signal;
          init?.signal?.addEventListener(
            'abort',
            () => reject(new DOMException('signal is aborted without reason', 'AbortError')),
            { once: true },
          );
        }));
      const client = createAuthClient({
        baseUrl: 'http://localhost:8082',
        publicApiBaseUrl: 'http://localhost:8080',
        fetch: fetchMock,
        timeoutMs: 50,
        chatbotTimeoutMs: 100,
      });
      await client.restoreSession();

      const request = client.authenticatedRequest(
        '/api/v1/chatbot/query',
        { method: 'POST' },
        'public',
      );
      const result = request.then(
        () => null,
        (error: unknown) => error,
      );
      await vi.advanceTimersByTimeAsync(50);

      expect(chatbotSignal?.aborted).toBe(false);

      await vi.advanceTimersByTimeAsync(50);
      await expect(result).resolves.toMatchObject({ name: 'TimeoutError' });
      expect(chatbotSignal?.aborted).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });
});

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function errorResponse(status: number): Response {
  return new Response(null, { status });
}

function refreshUnavailableCase(label: string, refreshFailure: () => Response): void {
  it(`refresh ${label}는 인증 서비스 장애로 분류하고 다음 요청에서 다시 복구한다`, async () => {
    const fetchMock = vi.fn<typeof fetch>()
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'old-jwt' }))
      .mockResolvedValueOnce(jsonResponse({
        userId: 7, provider: 'GOOGLE', displayName: '홍길동', profileImage: null,
      }))
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(refreshFailure())
      .mockResolvedValueOnce(errorResponse(401))
      .mockResolvedValueOnce(jsonResponse({ accessToken: 'recovered-jwt' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    const client = createAuthClient({ baseUrl: 'http://localhost:8082', fetch: fetchMock });
    await client.restoreSession();

    await expect(client.authenticatedRequest('/api/v1/favorites/501'))
      .rejects.toThrow('Authentication unavailable');
    await expect(client.authenticatedRequest('/api/v1/favorites/501'))
      .resolves.toMatchObject({ status: 204 });

    expect(fetchMock.mock.calls.filter(([input]) => input === 'http://localhost:8082/auth/access')).toHaveLength(3);
    expect((fetchMock.mock.calls[6]?.[1]?.headers as Headers).get('Authorization')).toBe('Bearer recovered-jwt');
  });
}

function stubNavigatorLocks(locks: LockManager): () => void {
  const original = Object.getOwnPropertyDescriptor(navigator, 'locks');
  Object.defineProperty(navigator, 'locks', { configurable: true, value: locks });
  return () => {
    if (original == null) Reflect.deleteProperty(navigator, 'locks');
    else Object.defineProperty(navigator, 'locks', original);
  };
}
