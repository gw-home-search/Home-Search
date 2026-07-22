import { act } from 'react';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  type AuthClient,
  unmount,
} from '../../app/appTestHarness';

describe('마이페이지 사용자 흐름', () => {
  let root: Root | undefined;

  beforeEach(() => resetAppTestState());

  afterEach(() => {
    if (root) unmount(root);
    vi.restoreAllMocks();
  });

  it('개요에서 사용자와 관심 단지 수, 최근 관심 활동을 보여준다', async () => {
    window.history.pushState({}, '', '/my');
    const client = authenticatedClient([
      { complexId: 501, savedAt: '2026-07-21T08:30:00Z' },
      { complexId: 502, savedAt: '2026-07-20T06:00:00Z' },
    ], 12);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const complexId = Number(String(input).split('/').pop());
      return jsonResponse(complexDetail(complexId));
    }));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();
    await flushAsyncState();

    expect(rendered.rootElement.querySelector('main')?.textContent).toContain('마이페이지');
    expect(rendered.rootElement.textContent).toContain('홈서치 사용자');
    expect(rendered.rootElement.textContent).toContain('관심 단지 12곳');
    expect(rendered.rootElement.textContent).toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('관심 단지에 추가했어요');
    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/favorites?page=0&size=3',
      expect.objectContaining({ method: 'GET' }),
      'user',
    );
  });

  it('관심 목록은 일부 상세 실패를 격리하고 해당 관심 항목을 해제한다', async () => {
    window.history.pushState({}, '', '/my/favorites');
    const client = authenticatedClient([
      { complexId: 501, savedAt: '2026-07-21T08:30:00Z' },
      { complexId: 502, savedAt: '2026-07-20T06:00:00Z' },
    ], 2);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/502')) return { ok: false, status: 404, json: async () => ({}) } as Response;
      return jsonResponse(complexDetail(501));
    }));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('단지 #502');
    expect(rendered.rootElement.textContent).toContain('정보 다시 불러오기');

    const remove = rendered.rootElement.querySelector<HTMLButtonElement>('button[aria-label="테스트 단지 501 관심 해제"]');
    await act(async () => remove?.click());
    await flushAsyncState();

    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/favorites/501',
      expect.objectContaining({ method: 'DELETE' }),
      'user',
    );
    expect(rendered.rootElement.textContent).not.toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('관심 단지에서 해제했습니다.');
  });

  it('비로그인 직접 접근은 관심 API를 호출하지 않고 로그인 안내를 제공한다', async () => {
    window.history.pushState({}, '', '/my');
    const client = anonymousClient();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('로그인이 필요한 페이지예요');
    expect(rendered.rootElement.querySelector('[data-ui-component="auth-dialog"]')).not.toBeNull();
    expect(client.authenticatedRequest).not.toHaveBeenCalled();
    expect(fetchMock).not.toHaveBeenCalled();
    expect(window.sessionStorage.getItem('home-search:return-to')).toBe('/my');
  });
});

function authenticatedClient(
  content: Array<{ complexId: number; savedAt: string }>,
  totalElements: number,
): AuthClient {
  return {
    authenticatedRequest: vi.fn(async (path: string, init?: RequestInit) => {
      if (path.startsWith('/api/v1/favorites?')) {
        return jsonResponse({ content, page: 0, size: path.includes('size=3') ? 3 : 20, totalElements, totalPages: 1 });
      }
      if (path.startsWith('/api/v1/favorites/') && init?.method === 'DELETE') {
        return { ok: true, status: 204 } as Response;
      }
      throw new Error(`Unexpected request: ${path}`);
    }),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({
      kind: 'authenticated',
      currentUser: {
        userId: 19,
        provider: 'google',
        displayName: '홈서치 사용자',
        profileImage: null,
      },
    }),
  };
}

function anonymousClient(): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockRejectedValue(new Error('Authentication required')),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({ kind: 'anonymous' }),
  };
}

function complexDetail(complexId: number) {
  return {
    parcelId: complexId + 1000,
    complexId,
    latitude: 37.5,
    longitude: 127.0,
    address: `서울특별시 테스트로 ${complexId}`,
    displayName: `테스트 단지 ${complexId}`,
    tradeName: `테스트 단지 ${complexId}`,
    name: `테스트 단지 ${complexId}`,
    dongCnt: 8,
    unitCnt: 720,
    platArea: null,
    archArea: null,
    totArea: null,
    bcRat: null,
    vlRat: null,
    useDate: '2017-07-01',
    prediction: null,
  };
}
