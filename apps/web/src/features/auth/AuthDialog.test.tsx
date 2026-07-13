import { act, useState } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { AuthDialog } from './AuthDialog';
import type { OAuthProvider } from './authTypes';

describe('AuthDialog', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    root = undefined;
    host = undefined;
  });

  it('정확한 문구와 provider 순서를 accessible dialog로 제공하고 첫 action에 focus한다', async () => {
    const selected: OAuthProvider[] = [];
    ({ root, host } = await renderDialog((provider) => selected.push(provider)));

    const dialog = host.querySelector<HTMLDialogElement>('dialog');
    expect(dialog?.getAttribute('aria-labelledby')).toBe('auth-dialog-title');
    expect(dialog?.getAttribute('aria-describedby')).toBe('auth-dialog-description auth-dialog-auto-signup');
    expect(host.textContent).toContain('로그인 / 회원가입');
    expect(host.textContent).toContain('소셜 계정으로 홈서치를 이용하세요.');
    expect(host.textContent).toContain('처음 방문한 경우 계정이 자동으로 생성됩니다.');
    expect(host.textContent).toContain('소셜 계정의 비밀번호는 홈서치에 저장되지 않습니다.');

    const providerButtons = Array.from(host.querySelectorAll<HTMLButtonElement>('[data-auth-provider]'));
    expect(providerButtons.map((button) => button.textContent)).toEqual([
      '카카오로 계속하기',
      '네이버로 계속하기',
      'Google로 계속하기',
    ]);
    expect(host.querySelector('.auth-dialog-brand-copy')?.textContent).toBe('홈서치계정');
    expect(providerButtons.map((button) => button.querySelector('svg')?.getAttribute('data-provider-icon'))).toEqual([
      'kakao',
      'naver',
      'google',
    ]);
    expect(providerButtons.every((button) => button.querySelector('svg')?.getAttribute('aria-hidden') === 'true')).toBe(true);
    expect(providerButtons.every((button) => button.querySelector('.auth-provider-balance') == null)).toBe(true);
    expect(document.activeElement).toBe(providerButtons[0]);

    await act(async () => providerButtons[0]?.click());
    expect(selected).toEqual(['kakao']);
  });

  it('Escape, backdrop, 닫기 버튼으로 닫는다', async () => {
    const onClose = vi.fn();
    ({ root, host } = await renderDialog(vi.fn(), onClose));
    const dialog = host.querySelector<HTMLDialogElement>('dialog');

    await act(async () => dialog?.dispatchEvent(new Event('cancel', { cancelable: true })));
    await act(async () => dialog?.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    await act(async () => host!.querySelector<HTMLButtonElement>('button[aria-label="로그인 창 닫기"]')?.click());

    expect(onClose).toHaveBeenCalledTimes(3);
  });
});

async function renderDialog(
  onProviderSelect: (provider: OAuthProvider) => void,
  closeSpy?: () => void,
): Promise<{ root: Root; host: HTMLDivElement }> {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);

  function Harness() {
    const [open, setOpen] = useState(true);
    return (
      <AuthDialog
        connectingProvider={null}
        error={null}
        isOpen={open}
        onClose={() => {
          closeSpy?.();
          if (!closeSpy) setOpen(false);
        }}
        onProviderSelect={onProviderSelect}
        onRetry={vi.fn()}
      />
    );
  }

  await act(async () => root.render(<Harness />));
  return { root, host };
}
