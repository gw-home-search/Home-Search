import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useComplexSearch } from './useComplexSearch';

describe('useComplexSearch 검색 lifecycle', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
  });

  it('연속 입력은 300ms 뒤 마지막 suggestion만 호출하고 submit 전 full search를 호출하지 않는다', async () => {
    vi.useFakeTimers();
    const requestedUrls: string[] = [];
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      requestedUrls.push(String(input));
      return Promise.resolve(jsonResponse([]));
    }));

    const { host, root } = await renderHarness();
    await type(host, '마');
    await type(host, '마포');

    expect(requestedUrls).toHaveLength(0);
    await act(async () => vi.advanceTimersByTimeAsync(299));
    expect(requestedUrls).toHaveLength(0);
    await act(async () => vi.advanceTimersByTimeAsync(1));

    expect(requestedUrls.filter((url) => url.includes('/suggestions'))).toHaveLength(1);
    expect(requestedUrls.filter((url) => !url.includes('/suggestions'))).toHaveLength(0);

    await submit(host);
    expect(requestedUrls.filter((url) => !url.includes('/suggestions'))).toHaveLength(1);
    await act(async () => root.unmount());
  });

  it('1글자 입력은 API를 호출하지 않고 접근 가능한 최소 길이 안내를 표시한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn(() => Promise.resolve(jsonResponse([])));
    vi.stubGlobal('fetch', fetchMock);

    const { host, root } = await renderHarness();
    await type(host, '마');
    await act(async () => vi.advanceTimersByTimeAsync(300));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(host.querySelector('[role="status"]')?.textContent).toBe('두 글자 이상 입력해 주세요');
    await act(async () => root.unmount());
  });

  it('새 입력은 제출된 이전 full search를 abort하고 늦은 응답을 무시한다', async () => {
    const oldResponse = deferred<Response>();
    const newResponse = deferred<Response>();
    const searchSignals: AbortSignal[] = [];
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/suggestions')) return Promise.resolve(jsonResponse([]));
      searchSignals.push(init?.signal as AbortSignal);
      return url.includes('q=Old') ? oldResponse.promise : newResponse.promise;
    }));

    const { host, root } = await renderHarness();
    await type(host, 'Old');
    await submit(host);
    await type(host, 'New');
    await submit(host);

    expect(searchSignals).toHaveLength(2);
    expect(searchSignals[0].aborted).toBe(true);

    newResponse.resolve(jsonResponse([{ complexId: 2, complexName: 'New Complex', parcelId: 20 }]));
    await flush();
    oldResponse.resolve(jsonResponse([{ complexId: 1, complexName: 'Old Complex', parcelId: 10 }]));
    await flush();

    expect(host.textContent).toContain('New Complex');
    expect(host.textContent).not.toContain('Old Complex');
    await act(async () => root.unmount());
  });
});

async function renderHarness() {
  const host = document.createElement('div');
  document.body.append(host);
  const root = createRoot(host);
  await act(async () => root.render(<SearchHarness />));
  return { host, root };
}

function SearchHarness() {
  const search = useComplexSearch({ focusMap: () => undefined, selectComplex: () => undefined });
  return (
    <form onSubmit={search.handleSearchSubmit}>
      <input
        aria-label="test search"
        name="q"
        onInput={(event) => search.handleSearchInputChange(event.currentTarget.value)}
      />
      <button type="submit">검색</button>
      {search.queryGuidance == null ? null : <p role="status">{search.queryGuidance}</p>}
      {search.searchResults.map((result) => <span key={result.complexId}>{result.complexName}</span>)}
    </form>
  );
}

async function type(host: HTMLElement, value: string) {
  await act(async () => {
    const input = host.querySelector<HTMLInputElement>('input');
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
}

async function submit(host: HTMLElement) {
  await act(async () => {
    host.querySelector('form')?.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await Promise.resolve();
  });
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((next) => { resolve = next; });
  return { promise, resolve };
}

function jsonResponse(body: unknown): Response {
  return { ok: true, status: 200, json: () => Promise.resolve(body) } as Response;
}
