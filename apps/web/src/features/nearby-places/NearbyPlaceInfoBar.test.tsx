import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NearbyPlaceInfoBar } from './NearbyPlaceInfoBar';
import type { ViewportNearbyPlaceState } from './useViewportNearbyPlaces';

describe('주변시설 정보 바', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
    host?.remove();
    host = null;
  });

  for (const state of ['loading', 'empty', 'zoom-required', 'ready', 'idle'] satisfies ViewportNearbyPlaceState[]) {
    it(`${state} 상태는 지도 위에 지속 상태 바를 표시하지 않는다`, async () => {
      host = document.createElement('div');
      root = createRoot(host);

      await act(async () => root?.render(
        <NearbyPlaceInfoBar error={null} place={null} state={state} onRetry={vi.fn()} />,
      ));

      expect(host.querySelector('.nearby-place-info-bar')).toBeNull();
    });
  }

  it('업데이트 실패는 지도 사용을 막지 않는 재시도 바를 유지한다', async () => {
    host = document.createElement('div');
    root = createRoot(host);
    const onRetry = vi.fn();

    await act(async () => root?.render(
      <NearbyPlaceInfoBar error="주변시설 업데이트를 실패했습니다." place={null} state="error" onRetry={onRetry} />,
    ));
    await act(async () => host?.querySelector<HTMLButtonElement>('button')?.click());

    expect(host?.textContent).toContain('주변시설 업데이트를 실패했습니다.');
    expect(onRetry).toHaveBeenCalledOnce();
  });
});
