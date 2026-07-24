import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RootErrorBoundary } from './RootErrorBoundary';

describe('앱 오류 경계', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
    vi.restoreAllMocks();
  });

  it('app shell render 오류를 기술 원문 없이 안전한 새로고침 화면으로 바꾼다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const onReload = vi.fn();
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <RootErrorBoundary onReload={onReload}>
          <BrokenApp />
        </RootErrorBoundary>,
      );
    });

    expect(host.textContent).toContain('홈서치를 표시하지 못했어요');
    expect(host.textContent).toContain('페이지 새로고침');
    expect(host.textContent).not.toContain('private root render detail');

    act(() => host.querySelector<HTMLButtonElement>('button')?.click());
    expect(onReload).toHaveBeenCalledTimes(1);
  });
});

function BrokenApp(): never {
  throw new Error('private root render detail');
}
