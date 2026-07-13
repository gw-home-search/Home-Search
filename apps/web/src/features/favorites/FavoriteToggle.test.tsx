import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { FavoriteToggle } from './FavoriteToggle';

const favoriteCss = readFileSync(`${process.cwd()}/src/features/favorites/favorite.css`, 'utf8');
const designSystemCss = readFileSync(`${process.cwd()}/src/app/design-system.css`, 'utf8');

describe('FavoriteToggle 즐겨찾기 토글', () => {
  let root: Root | null = null;
  afterEach(() => { if (root) act(() => root?.unmount()); root = null; });

  it('anonymous, checking, saved 상태의 label과 pressed/disabled를 구분한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onToggle = vi.fn();
    await act(async () => root?.render(<FavoriteToggle state={{ phase: 'anonymous', favorite: false }} liveMessage="" onToggle={onToggle} />));
    let button = host.querySelector<HTMLButtonElement>('button');
    expect(button?.ariaLabel).toBe('로그인하고 관심 단지 저장');
    act(() => button?.click());
    expect(onToggle).toHaveBeenCalledTimes(1);

    await act(async () => root?.render(<FavoriteToggle state={{ phase: 'checking', favorite: null }} liveMessage="" onToggle={onToggle} />));
    button = host.querySelector('button');
    expect(button?.ariaLabel).toBe('관심 상태 확인 중');
    expect(button?.disabled).toBe(true);
    expect(button?.getAttribute('aria-busy')).toBe('true');

    await act(async () => root?.render(<FavoriteToggle state={{ phase: 'ready', favorite: true }} liveMessage="관심 단지에 저장했습니다." onToggle={onToggle} />));
    button = host.querySelector('button');
    expect(button?.ariaLabel).toBe('관심 단지 해제');
    expect(button?.getAttribute('aria-pressed')).toBe('true');
    expect(host.querySelector('[aria-live="polite"]')?.textContent).toBe('관심 단지에 저장했습니다.');

    await act(async () => root?.render(<FavoriteToggle state={{ phase: 'error', favorite: null }} liveMessage="" onToggle={onToggle} />));
    expect(host.querySelector<HTMLButtonElement>('button')?.disabled).toBe(true);
  });

  it('상세 header의 하트는 빨간색이며 원형 surface를 만들지 않는다', () => {
    const hoverRule = favoriteCss.match(/\.favorite-toggle:hover:not\(:disabled\) \{([^}]*)\}/)?.[1] ?? '';
    expect(designSystemCss).toContain('--hs-map-color-favorite: #e11d48;');
    expect(favoriteCss).toContain('color: var(--hs-map-color-favorite);');
    expect(hoverRule).toContain('background: transparent;');
    expect(hoverRule).toContain('opacity: .72;');
    expect(favoriteCss).toContain('.favorite-toggle[data-favorite-selected="true"] { background: transparent; color: var(--hs-map-color-favorite); }');
    expect(favoriteCss).not.toContain('background: var(--hs-map-color-surface);');
    expect(favoriteCss).not.toContain('background: var(--hs-map-color-brand-soft);');
  });
});
