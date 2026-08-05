import { act } from 'react';
import type { Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createFakeKakaoSdk,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  type AuthClient,
  unmount,
} from '../../app/appTestHarness';
import { setCachedFavorite } from '../favorites/favoriteStore';

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

  it('마이페이지 경로를 이동해도 하나의 지도 shell과 Kakao Map 인스턴스를 유지한다', async () => {
    window.history.pushState({}, '', '/my');
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 33, swLng: 124, neLat: 39, neLng: 132 },
      level: 12,
    });
    vi.stubGlobal('kakao', sdk.kakao);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));
    const client = authenticatedClient([], 0);

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();

    expect(rendered.rootElement.querySelector('[data-ui-surface="map-first"]')).not.toBeNull();
    expect(rendered.rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(rendered.rootElement.querySelector('[aria-label="마이페이지 패널"]')).not.toBeNull();
    expect(sdk.kakao.maps.Map).toHaveBeenCalledTimes(1);

    await act(async () => {
      rendered.rootElement.querySelector<HTMLAnchorElement>('a[href="/my/favorites"]')?.click();
    });
    await flushAsyncState();

    expect(window.location.pathname).toBe('/my/favorites');
    expect(rendered.rootElement.querySelector('[aria-label="지도 화면"]')).not.toBeNull();
    expect(sdk.kakao.maps.Map).toHaveBeenCalledTimes(1);
  });

  it('마이페이지 toolbar는 앱 제목과 경쟁하는 두 번째 h1을 만들지 않는다', async () => {
    window.history.pushState({}, '', '/my');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const rendered = await renderApp({ authClient: authenticatedClient([], 0) });
    root = rendered.root;
    await flushAsyncState();

    expect(rendered.rootElement.querySelectorAll('h1')).toHaveLength(1);
    expect(rendered.rootElement.querySelector('.my-page-toolbar h2')?.textContent).toBe('마이페이지');
  });

  it('계정 화면은 중복 안내와 로그아웃 action 없이 연결 정보만 보여준다', async () => {
    window.history.pushState({}, '', '/my/account');
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const rendered = await renderApp({ authClient: authenticatedClient([], 0) });
    root = rendered.root;
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('표시 이름');
    expect(rendered.rootElement.textContent).toContain('연결 계정');
    expect(rendered.rootElement.textContent).not.toContain('개인정보 수정은 현재 지원하지 않습니다.');
    expect(rendered.rootElement.querySelector('.account-logout-action')).toBeNull();
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
    expect(rendered.rootElement.textContent).toContain('단지 정보 다시 확인');
    expect(rendered.rootElement.querySelector('button[aria-label="단지 #502 관심 해제"]')).not.toBeNull();

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

  it('관심 단지는 기존 목록을 유지한 채 20건씩 더 불러온다', async () => {
    window.history.pushState({}, '', '/my/favorites');
    const firstPage = Array.from({ length: 20 }, (_, index) => ({
      complexId: 501 + index,
      savedAt: '2026-07-21T08:30:00Z',
    }));
    const client = authenticatedPagedClient(firstPage, [{ complexId: 521, savedAt: '2026-07-20T06:00:00Z' }]);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const complexId = Number(String(input).split('/').pop());
      return Number.isSafeInteger(complexId) ? jsonResponse(complexDetail(complexId)) : jsonResponse([]);
    }));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();
    await flushAsyncState();

    const loadMore = Array.from(rendered.rootElement.querySelectorAll<HTMLButtonElement>('button'))
      .find((button) => button.textContent === '더 보기');
    expect(loadMore).toBeDefined();
    await act(async () => loadMore?.click());
    await flushAsyncState();
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('테스트 단지 521');
    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/favorites?page=1&size=20',
      expect.objectContaining({ method: 'GET' }),
      'user',
    );
  });

  it('관심 목록 새로고침 실패 시 기존 행을 유지하고 목록 안에 복구 action을 표시한다', async () => {
    window.history.pushState({}, '', '/my/favorites');
    const client = authenticatedClient([
      { complexId: 501, savedAt: '2026-07-21T08:30:00Z' },
    ], 1);
    const baseRequest = client.authenticatedRequest;
    let listCalls = 0;
    client.authenticatedRequest = vi.fn(async (path, init, target) => {
      if (path.startsWith('/api/v1/favorites?')) {
        listCalls += 1;
        if (listCalls > 1) return new Response(null, { status: 503 });
      }
      return baseRequest(path, init, target);
    });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const complexId = Number(String(input).split('/').pop());
      return jsonResponse(complexDetail(complexId));
    }));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();
    await flushAsyncState();
    expect(rendered.rootElement.textContent).toContain('테스트 단지 501');

    act(() => setCachedFavorite(999, true));
    await flushAsyncState();
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('관심 단지를 새로 확인하지 못했어요');
    expect(rendered.rootElement.textContent).toContain('관심 단지 새로 확인');
  });

  it('첫 페이지의 관심 단지를 해제하면 뒤의 항목으로 목록을 채운다', async () => {
    window.history.pushState({}, '', '/my/favorites');
    const firstPage = Array.from({ length: 20 }, (_, index) => ({
      complexId: 501 + index,
      savedAt: '2026-07-21T08:30:00Z',
    }));
    const client = authenticatedRemovalClient(firstPage, { complexId: 521, savedAt: '2026-07-20T06:00:00Z' });
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const complexId = Number(String(input).split('/').pop());
      return Number.isSafeInteger(complexId) ? jsonResponse(complexDetail(complexId)) : jsonResponse([]);
    }));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();
    await flushAsyncState();

    await act(async () => {
      rendered.rootElement.querySelector<HTMLButtonElement>('button[aria-label="테스트 단지 501 관심 해제"]')?.click();
    });
    await flushAsyncState();
    await flushAsyncState();

    expect(rendered.rootElement.textContent).not.toContain('테스트 단지 501');
    expect(rendered.rootElement.textContent).toContain('테스트 단지 521');
    expect(client.authenticatedRequest).toHaveBeenCalledWith(
      '/api/v1/favorites?page=0&size=20',
      expect.objectContaining({ method: 'GET' }),
      'user',
    );
  });

  it('관심 목록에서 연 상세를 닫으면 동일한 목록 위치와 focus로 돌아가고 하트 상태가 동기화된다', async () => {
    window.history.pushState({}, '', '/my/favorites');
    const sdk = createFakeKakaoSdk({
      bounds: { swLat: 37.4, swLng: 126.8, neLat: 37.7, neLng: 127.2 },
      level: 12,
    });
    vi.stubGlobal('kakao', sdk.kakao);
    const client = authenticatedClient([{ complexId: 501, savedAt: '2026-07-21T08:30:00Z' }], 1);
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => propertyResponse(String(input))));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    const focusSpy = vi.spyOn(HTMLElement.prototype, 'focus');
    await flushAsyncState();
    await flushAsyncState();

    const favoriteRow = rendered.rootElement.querySelector<HTMLButtonElement>('button[aria-label="테스트 단지 501 지도에서 보기"]');
    await act(async () => favoriteRow?.click());
    await flushAsyncState();
    await flushAsyncState();

    expect(window.location.pathname).toBe('/my/favorites');
    expect(rendered.rootElement.querySelector('[aria-label="단지 상세 패널"]')).not.toBeNull();
    expect(sdk.map.setLevel).toHaveBeenCalledWith(4);

    await act(async () => rendered.rootElement.querySelector<HTMLButtonElement>('button[aria-label="관심 단지 해제"]')?.click());
    await flushAsyncState();
    await act(async () => rendered.rootElement.querySelector<HTMLButtonElement>('button[aria-label="상세에서 뒤로가기"]')?.click());
    await flushAsyncState();

    expect(rendered.rootElement.querySelector('[aria-label="단지 상세 패널"]')).toBeNull();
    expect(rendered.rootElement.textContent).not.toContain('테스트 단지 501');
    const currentNavigation = rendered.rootElement.querySelector<HTMLAnchorElement>('.my-page-route-nav a[aria-current="page"]');
    expect(focusSpy.mock.instances).toContain(currentNavigation);
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
    expect(rendered.rootElement.querySelector('[data-ui-component="auth-dialog"]')?.hasAttribute('open')).toBe(false);
    expect(client.authenticatedRequest).not.toHaveBeenCalled();
    expect(fetchMock).toHaveBeenCalledWith(
      expect.stringContaining('/api/v1/map/regions'),
      expect.objectContaining({ method: 'POST' }),
    );
    expect(window.sessionStorage.getItem('home-search:return-to')).toBe('/my');
  });

  it('인증 서비스 초기 장애는 비로그인으로 오인하지 않고 복구 안내를 제공한다', async () => {
    window.history.pushState({}, '', '/my');
    const client = unavailableClient();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])));

    const rendered = await renderApp({ authClient: client });
    root = rendered.root;
    await flushAsyncState();

    expect(rendered.rootElement.textContent).toContain('지금은 로그인을 연결하기 어려워요');
    expect(rendered.rootElement.textContent).not.toContain('로그인이 필요한 페이지예요');
    expect(client.authenticatedRequest).not.toHaveBeenCalled();
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
      if (path.startsWith('/api/v1/favorites/') && init?.method === 'GET') {
        const complexId = Number(path.split('/').pop());
        return jsonResponse({ complexId, favorite: true, savedAt: '2026-07-21T08:30:00Z' });
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
        email: 'user@example.com',
        profileImage: null,
      },
    }),
  };
}

function authenticatedPagedClient(
  firstPage: Array<{ complexId: number; savedAt: string }>,
  secondPage: Array<{ complexId: number; savedAt: string }>,
): AuthClient {
  const client = authenticatedClient(firstPage, firstPage.length + secondPage.length);
  client.authenticatedRequest = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/v1/favorites?page=0&size=20') {
      return jsonResponse({ content: firstPage, page: 0, size: 20, totalElements: 21, totalPages: 2 });
    }
    if (path === '/api/v1/favorites?page=1&size=20') {
      return jsonResponse({ content: secondPage, page: 1, size: 20, totalElements: 21, totalPages: 2 });
    }
    if (path.startsWith('/api/v1/favorites/') && init?.method === 'GET') {
      const complexId = Number(path.split('/').pop());
      return jsonResponse({ complexId, favorite: true, savedAt: '2026-07-21T08:30:00Z' });
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  return client;
}

function authenticatedRemovalClient(
  firstPage: Array<{ complexId: number; savedAt: string }>,
  replacement: { complexId: number; savedAt: string },
): AuthClient {
  let removed = false;
  const client = authenticatedClient(firstPage, firstPage.length + 1);
  client.authenticatedRequest = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/v1/favorites?page=0&size=20') {
      const content = removed ? [...firstPage.slice(1), replacement] : firstPage;
      return jsonResponse({ content, page: 0, size: 20, totalElements: removed ? 20 : 21, totalPages: removed ? 1 : 2 });
    }
    if (path === '/api/v1/favorites/501' && init?.method === 'DELETE') {
      removed = true;
      return { ok: true, status: 204 } as Response;
    }
    if (path.startsWith('/api/v1/favorites/') && init?.method === 'GET') {
      const complexId = Number(path.split('/').pop());
      return jsonResponse({ complexId, favorite: true, savedAt: '2026-07-21T08:30:00Z' });
    }
    throw new Error(`Unexpected request: ${path}`);
  });
  return client;
}

function propertyResponse(url: string): Response {
  if (url.includes('/api/v1/complex/501/trades')) {
    return jsonResponse({ parcelId: 1501, complexId: 501, content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 });
  }
  if (url.includes('/api/v1/complex/501/trade-trend')) return jsonResponse([]);
  if (url.includes('/api/v1/complex/501')) return jsonResponse(complexDetail(501));
  if (url.includes('/api/v1/detail/1501/complexes')) return jsonResponse([]);
  return jsonResponse([]);
}

function anonymousClient(): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockRejectedValue(new Error('Authentication required')),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({ kind: 'anonymous' }),
  };
}

function unavailableClient(): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockRejectedValue(new Error('Authentication unavailable')),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({ kind: 'unavailable' }),
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
