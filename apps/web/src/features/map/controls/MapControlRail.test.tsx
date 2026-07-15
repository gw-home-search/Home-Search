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

  it('주변시설 버튼은 작업 모드를 바꾸지 않고 독립 선택창만 연다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onToolModeChange = vi.fn();

    await act(async () => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
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

    const facilityToggle = host.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]');
    expect(facilityToggle).not.toBeNull();
    expect(facilityToggle?.closest('#map-tools-menu')).toBeNull();
    expect(facilityToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(facilityToggle?.hasAttribute('aria-pressed')).toBe(false);

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="지도 도구 선택"]')?.click());
    expect(host.querySelector('#map-tools-menu button[aria-label="주변시설 선택"]')).toBeNull();

    await act(async () => facilityToggle?.click());
    expect(onToolModeChange).not.toHaveBeenCalled();
    expect(facilityToggle?.getAttribute('aria-expanded')).toBe('true');
    expect(host.querySelector('[aria-label="주변시설 종류 선택"]')).not.toBeNull();
    expect(host.querySelector('[aria-label="지도 도구 메뉴"]')).toBeNull();
  });

  it('주변시설 선택창에서 Escape를 누르면 선택은 유지하고 버튼으로 focus를 복원한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const onToolModeChange = vi.fn();

    await act(async () => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
        facilityCategories={['SUPERMARKET']}
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

    const facilityToggle = host.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]');
    await act(async () => facilityToggle?.click());
    expect(facilityToggle?.getAttribute('aria-expanded')).toBe('true');

    await act(async () => document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' })));
    expect(onToolModeChange).not.toHaveBeenCalled();
    expect(facilityToggle?.getAttribute('aria-expanded')).toBe('false');
    expect(facilityToggle?.dataset.active).toBe('true');
    expect(document.activeElement).toBe(facilityToggle);
    host.remove();
  });

  it('지도 runtime이 준비되면 단지 선택 없이 주변시설 설정을 활성화한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    await act(async () => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
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
    expect(host.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]')?.disabled).toBe(false);
  });

  it('주변시설 category를 0개까지 해제하고 최대 3개까지만 선택한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onFacilityCategoriesChange = vi.fn();
    const render = (facilityCategories: Array<'SUPERMARKET' | 'HOSPITAL' | 'SCHOOL'>) => root?.render(
      <MapControlRail
        activeTool="none"
        cadastralEnabled={false}
        facilityCategories={facilityCategories}
        disabled={false}
        displayMode="roadmap"
        level={4}
        onCadastralChange={vi.fn()}
        onFacilityCategoriesChange={onFacilityCategoriesChange}
        onDisplayModeChange={vi.fn()}
        onToolModeChange={vi.fn()}
        onZoomIn={vi.fn()}
        onZoomOut={vi.fn()}
      />
    );

    await act(async () => render(['SUPERMARKET', 'HOSPITAL', 'SCHOOL']));
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="주변시설 선택"]')?.click());
    expect(host.querySelectorAll('.map-facility-options button[aria-pressed="true"]')).toHaveLength(3);
    expect(host.querySelectorAll('.map-facility-category-check')).toHaveLength(3);
    expect(host.querySelector('.map-facility-live')).toBeNull();
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="음식점 선택"]')?.click());
    expect(onFacilityCategoriesChange).not.toHaveBeenCalled();
    expect(host.textContent).toContain('주변시설은 최대 3개까지 선택할 수 있습니다.');
    expect(host.querySelector('.map-facility-live')).not.toBeNull();

    await act(async () => render(['SUPERMARKET']));
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="대형마트 선택 해제"]')?.click());
    expect(onFacilityCategoriesChange).toHaveBeenLastCalledWith([]);

    await act(async () => render(['SUPERMARKET', 'HOSPITAL', 'SCHOOL']));
    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="주변시설 전체 해제"]')?.click());
    expect(onFacilityCategoriesChange).toHaveBeenLastCalledWith([]);
  });
});
