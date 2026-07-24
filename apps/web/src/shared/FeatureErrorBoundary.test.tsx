import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { FeatureErrorBoundary } from './FeatureErrorBoundary';

describe('FeatureErrorBoundary', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
    vi.restoreAllMocks();
  });

  it('한 기능의 render 오류를 격리하고 다른 surface를 유지한다', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <>
          <FeatureErrorBoundary feature="exploration">
            <BrokenFeature />
          </FeatureErrorBoundary>
          <section aria-label="지도 화면">계속 사용할 지도</section>
        </>,
      );
    });

    expect(host.textContent).toContain('이 영역을 표시하지 못했어요');
    expect(host.textContent).toContain('이 영역 다시 열기');
    expect(host.querySelector('[aria-label="지도 화면"]')?.textContent).toBe('계속 사용할 지도');
  });
});

function BrokenFeature(): never {
  throw new Error('private render detail');
}
