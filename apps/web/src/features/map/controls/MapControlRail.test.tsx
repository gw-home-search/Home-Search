import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MapControlRail } from './MapControlRail';

describe('MapControlRail 지도 컨트롤', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
  });

  it('지도 형식과 지도 도구 메뉴를 하나만 열고 Escape 후 toggle로 focus를 복원한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <MapControlRail
          activeTool="none"
          cadastralEnabled={false}
          disabled={false}
          displayMode="roadmap"
          level={4}
          commerceAvailable={false}
          onCadastralChange={vi.fn()}
          onDisplayModeChange={vi.fn()}
          onToolModeChange={vi.fn()}
          onZoomIn={vi.fn()}
          onZoomOut={vi.fn()}
        />,
      );
    });

    const displayToggle = host.querySelector<HTMLButtonElement>('button[aria-label="지도 형식 선택"]');
    const toolToggle = host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]');

    await act(async () => displayToggle?.click());
    expect(displayToggle?.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('[aria-label="지도 형식 메뉴"]')).not.toBeNull();

    await act(async () => toolToggle?.click());
    expect(displayToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(toolToggle?.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('[aria-label="지도 형식 메뉴"]')).toBeNull();
    expect(host.querySelector('[aria-label="지도 도구 메뉴"]')).not.toBeNull();

    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })));
    expect(toolToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(toolToggle);

    host.remove();
  });

  it('거리뷰·거리는 작업 모드로, 지적은 독립 overlay toggle로 전달한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onToolModeChange = vi.fn();
    const onCadastralChange = vi.fn();

    await act(async () => {
      root?.render(
        <MapControlRail
          activeTool="none"
          cadastralEnabled={false}
          disabled={false}
          displayMode="roadmap"
          level={4}
          commerceAvailable
          onCadastralChange={onCadastralChange}
          onDisplayModeChange={vi.fn()}
          onToolModeChange={onToolModeChange}
          onZoomIn={vi.fn()}
          onZoomOut={vi.fn()}
        />,
      );
    });

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="거리뷰 사용"]')?.click());
    expect(onToolModeChange).toHaveBeenLastCalledWith('roadview');

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지적편집도 표시"]')?.click());
    expect(onCadastralChange).toHaveBeenLastCalledWith(true);

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="거리 측정 사용"]')?.click());
    expect(onToolModeChange).toHaveBeenLastCalledWith('distance');

  });

  it('상권을 도구 메뉴 밖의 독립 버튼으로 보여주고 바로 연다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onToolModeChange = vi.fn();

    await act(async () => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
        commerceAvailable
        disabled={false}
        displayMode="roadmap"
        level={4}
        onCadastralChange={vi.fn()}
        onDisplayModeChange={vi.fn()}
        onToolModeChange={onToolModeChange}
        onZoomIn={vi.fn()}
        onZoomOut={vi.fn()}
      />,
    ));

    const commerceToggle = host.querySelector<HTMLButtonElement>('button[aria-label="주변 상권 보기"]');
    expect(commerceToggle).not.toBeNull();
    expect(commerceToggle?.closest('#map-tools-menu')).toBeNull();

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    expect(host.querySelector('#map-tools-menu button[aria-label="주변 상권 보기"]')).toBeNull();

    await act(async () => commerceToggle?.click());
    expect(onToolModeChange).toHaveBeenLastCalledWith('commerce');
  });

  it('상권 모드에서 Escape를 누르면 종료하고 독립 상권 버튼으로 focus를 복원한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const onToolModeChange = vi.fn();

    await act(async () => root?.render(
      <MapControlRail
        activeTool="commerce"
        cadastralEnabled={false}
        commerceAvailable
        disabled={false}
        displayMode="roadmap"
        level={4}
        onCadastralChange={vi.fn()}
        onDisplayModeChange={vi.fn()}
        onToolModeChange={onToolModeChange}
        onZoomIn={vi.fn()}
        onZoomOut={vi.fn()}
      />,
    ));

    const commerceToggle = host.querySelector<HTMLButtonElement>('button[aria-label="주변 상권 보기"]');
    expect(commerceToggle?.getAttribute('aria-pressed')).toBe('true');

    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })));
    expect(onToolModeChange).toHaveBeenCalledWith('none');
    expect(document.activeElement).toBe(commerceToggle);
    host.remove();
  });

  it('확정된 complexId가 없으면 상권 도구만 비활성화한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    await act(async () => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
        commerceAvailable={false}
        disabled={false}
        displayMode="roadmap"
        level={4}
        onCadastralChange={vi.fn()}
        onDisplayModeChange={vi.fn()}
        onToolModeChange={vi.fn()}
        onZoomIn={vi.fn()}
        onZoomOut={vi.fn()}
      />,
    ));
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="주변 상권 보기"]')?.disabled).toBe(true);
  });
});
