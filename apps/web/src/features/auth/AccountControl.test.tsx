import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { AuthClient } from './api/authClient';
import { AccountControl } from './AccountControl';
import { AuthProvider } from './AuthProvider';

describe('AccountControl 사용자 메뉴', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    vi.restoreAllMocks();
  });

  it('anonymous header action은 계정 icon과 간결한 로그인 label을 함께 제공한다', async () => {
    ({ root, host } = await renderAccount(anonymousClient()));
    const login = host.querySelector<HTMLButtonElement>('.account-login-button');
    expect(login?.textContent).toContain('로그인');
    expect(login?.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('사용자 표시 필드만 노출하고 profile image 실패 시 이름 첫 글자로 대체한다', async () => {
    ({ root, host } = await renderAccount(authenticatedClient()));
    const chip = host.querySelector<HTMLButtonElement>('.account-chip');
    expect(chip?.textContent).toContain('매우 긴 한국어 사용자 이름');
    expect(chip?.textContent).not.toContain('Google');
    expect(chip?.hasAttribute('aria-haspopup')).toBe(false);
    expect(chip?.querySelector('.account-chip-chevron')?.tagName).toBe('svg');
    expect(host.textContent).not.toContain('person@example.com');
    expect(host.textContent).not.toContain('userId');

    const image = host.querySelector<HTMLImageElement>('.account-avatar');
    await act(async () => image?.dispatchEvent(new Event('error')));
    expect(host.querySelector('.account-avatar-fallback')?.textContent).toBe('매');

    await act(async () => chip?.click());
    expect(host.querySelector('[aria-label="계정 메뉴"]')?.textContent).toContain('Google 계정');
    expect(host.querySelector('[role="menu"]')).toBeNull();
    expect(Array.from(host.querySelectorAll<HTMLAnchorElement>('.account-menu a')).map((link) => link.getAttribute('href')))
      .toEqual(['/my']);
  });

  it('menu는 Escape와 outside pointer로 닫고 Escape 후 chip focus를 복원한다', async () => {
    ({ root, host } = await renderAccount(authenticatedClient()));
    const chip = host.querySelector<HTMLButtonElement>('.account-chip');
    await act(async () => chip?.click());
    expect(host.querySelector('[aria-label="계정 메뉴"]')).not.toBeNull();

    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })));
    await act(async () => Promise.resolve());
    expect(host.querySelector('[aria-label="계정 메뉴"]')).toBeNull();
    expect(document.activeElement).toBe(chip);

    await act(async () => chip?.click());
    await act(async () => document.body.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true })));
    expect(host.querySelector('[aria-label="계정 메뉴"]')).toBeNull();
  });

  it('logout은 응답 성공 여부와 무관하게 anonymous로 전환하고 실패 안내를 유지한다', async () => {
    const successClient = authenticatedClient();
    ({ root, host } = await renderAccount(successClient));
    await act(async () => host!.querySelector<HTMLButtonElement>('.account-chip')?.click());
    await act(async () => host!.querySelector<HTMLButtonElement>('.account-menu > button')?.click());
    expect(successClient.logout).toHaveBeenCalledTimes(1);
    expect(host.querySelector('.account-chip')).toBeNull();
    expect(host.textContent).toContain('로그인');

    act(() => root?.unmount());
    host.remove();
    const failureClient = authenticatedClient(true);
    ({ root, host } = await renderAccount(failureClient));
    await act(async () => host!.querySelector<HTMLButtonElement>('.account-chip')?.click());
    await act(async () => host!.querySelector<HTMLButtonElement>('.account-menu > button')?.click());
    expect(host.querySelector('.account-chip')).toBeNull();
    expect(host.textContent).toContain('로그인');
    expect(host.querySelector('.auth-notice')?.textContent).toContain('로그아웃을 완료하지 못했어요');
  });
});

function authenticatedClient(logoutFails = false): AuthClient {
  return {
    authenticatedRequest: vi.fn().mockRejectedValue(new Error('Authentication required')),
    authorizationUrl: vi.fn((provider) => `http://localhost:8082/oauth2/authorization/${provider}`),
    logout: logoutFails ? vi.fn().mockRejectedValue(new Error('failed')) : vi.fn().mockResolvedValue(undefined),
    restoreSession: vi.fn().mockResolvedValue({
      kind: 'authenticated',
      currentUser: {
        userId: 19,
        provider: 'google',
        displayName: '매우 긴 한국어 사용자 이름',
        profileImage: 'https://images.example.com/profile.png',
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

async function renderAccount(client: AuthClient): Promise<{ root: Root; host: HTMLDivElement }> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  await act(async () => root.render(
    <MemoryRouter>
      <AuthProvider client={client} navigate={vi.fn()}>
        <AccountControl />
      </AuthProvider>
    </MemoryRouter>,
  ));
  await act(async () => Promise.resolve());
  return { root, host };
}
