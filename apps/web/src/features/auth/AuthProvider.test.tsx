import { StrictMode, act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

import { AccountControl } from './AccountControl';
import { AuthProvider, useAuth } from './AuthProvider';
import type { AuthClient } from './api/authClient';

describe('AuthProvider와 AccountControl', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    window.history.replaceState({}, '', '/');
    vi.restoreAllMocks();
  });

  it('normal 401은 anonymous 로그인 action이고 StrictMode restore는 한 번만 실행한다', async () => {
    const client = authClient({ kind: 'anonymous' });
    ({ root, host } = await renderAuth(client, true));

    expect(client.restoreSession).toHaveBeenCalledTimes(1);
    expect(host.querySelector<HTMLButtonElement>('button')?.textContent).toBe('로그인');
    expect(host.textContent).not.toContain('로그인 서비스를 불러오지 못했습니다.');

    const loginButton = host.querySelector<HTMLButtonElement>('.account-login-button');
    await act(async () => loginButton?.click());
    await act(async () => Promise.resolve());
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(true);
    expect(document.activeElement?.textContent).toBe('카카오로 계속하기');
    await act(async () => host!.querySelector<HTMLButtonElement>('.auth-dialog-close')?.click());
    await act(async () => Promise.resolve());
    expect(document.activeElement).toBe(loginButton);
  });

  it('/auth/failure은 URL을 정리하고 generic error dialog를 자동으로 연다', async () => {
    window.history.replaceState({}, '', '/auth/failure?error=provider_detail');
    const client = authClient({ kind: 'anonymous' });
    ({ root, host } = await renderAuth(client));

    expect(window.location.pathname).toBe('/');
    expect(host.textContent).toContain('지금은 로그인을 연결하기 어려워요');
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(true);
  });

  it('/auth/success는 사용자 chip과 3초 notice를 표시하고 URL을 정리한다', async () => {
    window.history.replaceState({}, '', '/auth/success');
    const client = authClient({
      kind: 'authenticated',
      currentUser: { userId: 17, provider: 'google', displayName: '홍길동', profileImage: null },
    });
    ({ root, host } = await renderAuth(client));

    expect(window.location.pathname).toBe('/');
    expect(host.textContent).toContain('홍길동');
    expect(host.querySelector('[aria-live="polite"]')?.textContent).toContain('로그인되었습니다.');
  });

  it('/auth/success는 허용된 마이페이지 복귀 경로만 복원한다', async () => {
    window.history.replaceState({}, '', '/auth/success');
    window.sessionStorage.setItem('home-search:return-to', '/my/favorites');
    const client = authClient({
      kind: 'authenticated',
      currentUser: { userId: 17, provider: 'google', displayName: '홍길동', profileImage: null },
    });
    ({ root, host } = await renderAuth(client));

    expect(window.location.pathname).toBe('/my/favorites');
    expect(window.sessionStorage.getItem('home-search:return-to')).toBeNull();
  });

  it('authenticated request 401은 memory session을 지우고 만료 dialog를 연다', async () => {
    const client = authClient({
      kind: 'authenticated',
      currentUser: { userId: 17, provider: 'google', displayName: '홍길동', profileImage: null },
    });
    client.authenticatedRequest = vi.fn().mockResolvedValue(new Response(null, { status: 401 }));
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    await act(async () => root?.render(<AuthProvider client={client}><ExpiryProbe /></AuthProvider>));
    await act(async () => Promise.resolve());
    await act(async () => host?.querySelector<HTMLButtonElement>('button')?.click());
    await act(async () => Promise.resolve());

    expect(host.querySelector('[data-auth-probe]')?.textContent).toBe('anonymous');
    expect(host.textContent).toContain('로그인이 만료되었어요');
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(true);
  });

  it('인증 서비스 장애 rejection은 기존 사용자 표시를 유지하고 만료 dialog를 열지 않는다', async () => {
    const client = authClient({
      kind: 'authenticated',
      currentUser: { userId: 17, provider: 'google', displayName: '홍길동', profileImage: null },
    });
    client.authenticatedRequest = vi.fn().mockRejectedValue(new Error('Authentication unavailable'));
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    await act(async () => root?.render(<AuthProvider client={client}><ExpiryProbe /></AuthProvider>));
    await act(async () => Promise.resolve());
    await act(async () => host?.querySelector<HTMLButtonElement>('button')?.click());
    await act(async () => Promise.resolve());

    expect(host.querySelector('[data-auth-probe]')?.textContent).toBe('authenticated');
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(false);
  });

  it('startup refresh 장애는 public map과 분리하되 unavailable 상태를 보존한다', async () => {
    const client = authClient({ kind: 'unavailable' });
    ({ root, host } = await renderAuth(client));

    expect(host.querySelector<HTMLButtonElement>('.account-login-button')?.textContent).toBe('로그인');
    expect(host.querySelector('.account-control')?.getAttribute('data-auth-status')).toBe('unavailable');
    expect(host.querySelector('dialog')?.hasAttribute('open')).toBe(false);
  });

  it('logout API 실패에도 memory 사용자 상태를 지운다', async () => {
    const client = authClient({
      kind: 'authenticated',
      currentUser: { userId: 17, provider: 'google', displayName: '홍길동', profileImage: null },
    });
    client.logout = vi.fn().mockRejectedValue(new Error('logout unavailable'));
    host = document.createElement('div'); document.body.append(host); root = createRoot(host);
    await act(async () => root?.render(<AuthProvider client={client}><LogoutProbe /></AuthProvider>));
    await act(async () => Promise.resolve());

    await act(async () => host?.querySelector<HTMLButtonElement>('button')?.click());
    await act(async () => Promise.resolve());

    expect(host.querySelector('[data-auth-probe]')?.textContent).toBe('anonymous:none');
  });
});

function ExpiryProbe() {
  const auth = useAuth();
  return <><span data-auth-probe>{auth.status}</span><button type="button" onClick={() => void auth.authenticatedRequest('/api/v1/favorites/501').catch(() => undefined)}>관심 조회</button></>;
}

function LogoutProbe() {
  const auth = useAuth();
  return <><span data-auth-probe>{auth.status}:{auth.currentUser?.userId ?? 'none'}</span><button type="button" onClick={() => void auth.logout()}>로그아웃</button></>;
}

function authClient(result: Awaited<ReturnType<AuthClient['restoreSession']>>): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockRejectedValue(new Error('Authentication required')),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue(result),
  };
}

async function renderAuth(client: AuthClient, strict = false): Promise<{ root: Root; host: HTMLDivElement }> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  const content = (
    <MemoryRouter>
      <AuthProvider client={client} navigate={vi.fn()}>
        <AccountControl />
      </AuthProvider>
    </MemoryRouter>
  );
  await act(async () => root.render(strict ? <StrictMode>{content}</StrictMode> : content));
  await act(async () => Promise.resolve());
  return { root, host };
}
