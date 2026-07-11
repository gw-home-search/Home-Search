import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { RequestStateNotice } from './RequestStateNotice';

describe('RequestStateNotice', () => {
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
          errorMessage="불러오지 못했어요"
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
          errorMessage="불러오지 못했어요"
        />,
      );
    });
    expect(testHost.textContent).not.toContain('불러오는 중');

    act(() => vi.advanceTimersByTime(149));
    expect(testHost.textContent).not.toContain('불러오는 중');
    act(() => vi.advanceTimersByTime(1));
    expect(testHost.textContent).toContain('불러오는 중');
  });

  it('error는 사용자 문구와 복구 action, 닫힌 기술 정보를 표시한다', async () => {
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
          errorMessage="단지 정보를 불러오지 못했어요"
          secondaryMessage="지도 이동과 확대·축소는 계속 사용할 수 있습니다"
          technicalError="Failed to fetch complex markers: 500 Invalid parameter format."
          onRetry={onRetry}
        />,
      );
    });

    expect(testHost.textContent).toContain('단지 정보를 불러오지 못했어요');
    expect(testHost.textContent).not.toContain('Failed to fetch');
    const details = testHost.querySelector<HTMLDetailsElement>('details');
    expect(details?.open).toBe(false);
    expect(details?.textContent).toContain('HTTP 500');
    expect(details?.textContent).toContain('Invalid parameter format.');

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
        errorMessage="불러오지 못했어요"
      />
    );

    await act(async () => root?.render(notice('loading')));
    act(() => vi.advanceTimersByTime(150));
    expect(testHost.textContent).toContain('불러오는 중');

    await act(async () => root?.render(notice('ready')));
    expect(testHost.querySelector('.request-state-notice')).toBeNull();
    expect(testHost.querySelector('[aria-live="polite"]')?.textContent).toBe('불러오기를 완료했습니다');
  });
});
