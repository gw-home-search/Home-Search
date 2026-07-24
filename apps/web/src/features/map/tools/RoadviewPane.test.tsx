import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RoadviewPane } from './RoadviewPane';

describe('거리뷰 패널', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
  });

  it('runtime 오류를 catalog 문구와 명확한 닫기 action으로만 표시한다', async () => {
    const host = document.createElement('div');
    const onClose = vi.fn();
    root = createRoot(host);

    await act(async () => {
      root?.render(<RoadviewPane state="error" onClose={onClose} />);
    });

    expect(host.textContent).toContain('거리뷰를 불러오지 못했어요');
    expect(host.textContent).toContain('거리뷰 닫기');
    expect(host.textContent).not.toContain('거리뷰를 불러오지 못했습니다');

    act(() => [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent === '거리뷰 닫기')?.click());
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
