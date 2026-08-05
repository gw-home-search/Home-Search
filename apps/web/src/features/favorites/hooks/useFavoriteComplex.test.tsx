import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthProvider } from '../../auth/AuthProvider';
import type { AuthClient } from '../../auth/api/authClient';
import { resetFavoriteStore } from '../favoriteStore';
import { useFavoriteComplex } from './useFavoriteComplex';

describe('useFavoriteComplex 즐겨찾기 상태', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;
  afterEach(() => { if (root) act(() => root?.unmount()); host?.remove(); root = null; host = null; resetFavoriteStore(); });

  it('anonymous에서는 network 요청 없이 로그인용 상태를 제공한다', async () => {
    const request = vi.fn<AuthClient['authenticatedRequest']>();
    const client = authClient({ kind: 'anonymous' }, request);
    ({ root, host } = await render(client, 501));
    expect(host.dataset.phase).toBe('anonymous');
    expect(request).not.toHaveBeenCalled();
  });

  it('complex 전환 뒤 늦은 이전 응답이 새 상태를 덮지 않는다', async () => {
    const first = deferred<Response>();
    const second = deferred<Response>();
    const request = vi.fn<AuthClient['authenticatedRequest']>((path) => path.endsWith('/501') ? first.promise : second.promise);
    const client = authClient({ kind: 'authenticated', currentUser: {
      userId: 77, provider: 'google', displayName: '테스터', email: 'user@example.com', profileImage: null,
    } }, request);
    ({ root, host } = await render(client, 501));
    await act(async () => root?.render(tree(client, 502)));
    await act(async () => second.resolve(jsonResponse({ complexId: 502, favorite: false, savedAt: null })));
    expect(host.dataset.phase).toBe('ready');
    expect(host.dataset.favorite).toBe('false');
    await act(async () => first.resolve(jsonResponse({ complexId: 501, favorite: true, savedAt: '2026-07-13T06:00:00Z' })));
    expect(host.dataset.favorite).toBe('false');
  });

  it('favorite PUT은 즉시 optimistic 반영하고 API 실패 시 이전 상태로 rollback한다', async () => {
    const mutation = deferred<Response>();
    const request = vi.fn<AuthClient['authenticatedRequest']>()
      .mockResolvedValueOnce(jsonResponse({ complexId: 501, favorite: false, savedAt: null }))
      .mockReturnValueOnce(mutation.promise);
    const client = authClient({ kind: 'authenticated', currentUser: {
      userId: 77, provider: 'google', displayName: '테스터', email: 'user@example.com', profileImage: null,
    } }, request);
    ({ root, host } = await render(client, 501));

    await act(async () => host!.querySelector<HTMLButtonElement>('button')?.click());
    expect(host.dataset.phase).toBe('saving');
    expect(host.dataset.favorite).toBe('true');

    await act(async () => mutation.resolve(new Response(null, { status: 503 })));
    expect(host.dataset.phase).toBe('error');
    expect(host.dataset.favorite).toBe('false');
  });

  it('다른 사용자로 바뀌면 이전 사용자의 cached favorite를 사용하지 않는다', async () => {
    const firstRequest = vi.fn<AuthClient['authenticatedRequest']>()
      .mockResolvedValue(jsonResponse({ complexId: 501, favorite: true, savedAt: '2026-07-13T06:00:00Z' }));
    const firstClient = authClient({ kind: 'authenticated', currentUser: {
      userId: 77, provider: 'google', displayName: '첫 사용자', email: 'user@example.com', profileImage: null,
    } }, firstRequest);
    ({ root, host } = await render(firstClient, 501));
    expect(host.dataset.favorite).toBe('true');

    act(() => root?.unmount());
    host.remove();
    root = null;
    host = null;

    const secondRequest = vi.fn<AuthClient['authenticatedRequest']>()
      .mockResolvedValue(jsonResponse({ complexId: 501, favorite: false, savedAt: null }));
    const secondClient = authClient({ kind: 'authenticated', currentUser: {
      userId: 88, provider: 'google', displayName: '둘째 사용자', email: 'user@example.com', profileImage: null,
    } }, secondRequest);
    ({ root, host } = await render(secondClient, 501));

    expect(secondRequest).toHaveBeenCalledTimes(1);
    expect(host.dataset.favorite).toBe('false');
  });
});

function Harness({ complexId }: { complexId: number }) {
  const favorite = useFavoriteComplex(complexId);
  return <div id="favorite-harness" data-phase={favorite.favoriteState.phase} data-favorite={String(favorite.favoriteState.favorite)}><button type="button" onClick={() => void favorite.onFavoriteToggle()}>toggle</button></div>;
}
function tree(client: AuthClient, complexId: number) { return <AuthProvider client={client}><Harness complexId={complexId} /></AuthProvider>; }
async function render(client: AuthClient, complexId: number) {
  const host = document.createElement('div'); document.body.append(host); const root = createRoot(host);
  await act(async () => root.render(tree(client, complexId))); await act(async () => Promise.resolve());
  return { root, host: host.querySelector<HTMLDivElement>('#favorite-harness')! };
}
function authClient(result: Awaited<ReturnType<AuthClient['restoreSession']>>, request: AuthClient['authenticatedRequest']): AuthClient {
  return { authenticatedRequest: request, authorizationUrl: vi.fn(), logout: vi.fn(), restoreSession: vi.fn().mockResolvedValue(result) };
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((next) => { resolve = next; }); return { promise, resolve }; }
function jsonResponse(body: unknown) { return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }); }
