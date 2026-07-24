import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getUserFeedback } from './feedback/feedbackCatalog';
import { RequestStateNotice } from './RequestStateNotice';

describe('RequestStateNotice 요청 상태 안내', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    vi.useRealTimers();
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
    host?.remove();
    host = null;
  });

  it('ready에는 UI가 없고 loading은 150ms 뒤에만 표시한다', async () => {
    vi.useFakeTimers();
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);

    await act(async () => {
      root?.render(
        <RequestStateNotice
          state="ready"
          loadingMessage="불러오는 중"
          emptyMessage="결과가 없습니다"
          feedback={getUserFeedback('COMPLEX_UNAVAILABLE')}
        />,
      );
    });
    expect(testHost.textContent).toBe('');

    await act(async () => {
      root?.render(
        <RequestStateNotice
          state="loading"
          loadingMessage="불러오는 중"
          emptyMessage="결과가 없습니다"
          feedback={getUserFeedback('COMPLEX_UNAVAILABLE')}
        />,
      );
    });
    expect(testHost.textContent).not.toContain('불러오는 중');

    act(() => vi.advanceTimersByTime(149));
    expect(testHost.textContent).not.toContain('불러오는 중');
    act(() => vi.advanceTimersByTime(1));
    expect(testHost.textContent).toContain('불러오는 중');
  });

  it('error는 allowlist 사용자 문구와 복구 action만 표시한다', async () => {
    const onRetry = vi.fn();
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);

    await act(async () => {
      root?.render(
        <RequestStateNotice
          state="error"
          loadingMessage="불러오는 중"
          emptyMessage="결과가 없습니다"
          feedback={getUserFeedback('COMPLEX_UNAVAILABLE')}
          onRetry={onRetry}
        />,
      );
    });

    expect(testHost.textContent).toContain('단지 정보를 불러오지 못했어요');
    expect(testHost.textContent).toContain('지도와 다른 단지는 계속 볼 수 있어요.');
    expect(testHost.textContent).toContain('단지 다시 불러오기');
    expect(testHost.textContent).not.toContain('Failed to fetch');
    expect(testHost.textContent).not.toContain('HTTP 500');
    expect(testHost.textContent).not.toContain('오류 정보');
    expect(testHost.querySelector('details')).toBeNull();

    act(() => testHost.querySelector<HTMLButtonElement>('button')?.click());
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('loading 뒤 ready는 보이는 notice를 제거하고 완료만 aria-live로 알린다', async () => {
    vi.useFakeTimers();
    const testHost = document.createElement('div');
    host = testHost;
    document.body.append(testHost);
    root = createRoot(testHost);
    const notice = (state: 'loading' | 'ready') => (
      <RequestStateNotice
        state={state}
        loadingMessage="불러오는 중"
        emptyMessage="결과가 없습니다"
        feedback={getUserFeedback('COMPLEX_UNAVAILABLE')}
      />
    );

    await act(async () => root?.render(notice('loading')));
    act(() => vi.advanceTimersByTime(150));
    expect(testHost.textContent).toContain('불러오는 중');

    await act(async () => root?.render(notice('ready')));
    expect(testHost.querySelector('.request-state-notice')).toBeNull();
    expect(testHost.querySelector('[aria-live="polite"]')?.textContent).toBe('불러오기를 완료했어요');
  });
});
