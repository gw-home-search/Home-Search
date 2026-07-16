import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { useComplexSearch } from './useComplexSearch';

describe('useComplexSearch 검색 lifecycle', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    document.body.innerHTML = '';
  });

  it('새 검색은 이전 요청을 abort하고 늦은 이전 응답을 무시한다', async () => {
    const oldResponse = deferred<Response>();
    const newResponse = deferred<Response>();
    const searchSignals: AbortSignal[] = [];
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes('/suggestions')) return Promise.resolve(jsonResponse([]));
      searchSignals.push(init?.signal as AbortSignal);
      return url.includes('q=Old') ? oldResponse.promise : newResponse.promise;
    }));

    const host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    await act(async () => root.render(<SearchHarness />));

    await typeAndDebounce(host, 'Old');
    await typeAndDebounce(host, 'New');

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

function SearchHarness() {
  const search = useComplexSearch({ focusMap: () => undefined, selectComplex: () => undefined });
  return (
    <div>
      <input aria-label="test search" onInput={(event) => search.handleSearchInputChange(event.currentTarget.value)} />
      {search.searchResults.map((result) => <span key={result.complexId}>{result.complexName}</span>)}
    </div>
  );
}

async function typeAndDebounce(host: HTMLElement, value: string) {
  await act(async () => {
    const input = host.querySelector<HTMLInputElement>('input');
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
    }
  });
  await act(async () => new Promise((resolve) => setTimeout(resolve, 320)));
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
