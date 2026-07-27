import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  applyFilterRange,
  flushAsyncState,
  jsonResponse,
  renderApp,
  resetAppTestState,
  resolveApiUrl,
  unmount,
} from './appTestHarness';

describe('App 필터', () => {
  afterEach(resetAppTestState);

  it('public map UI는 map-first design landmarks와 active filter state를 고정한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    expect(rootElement.querySelector('[data-ui-surface="map-first"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="filter-controls"]')).not.toBeNull();
    expect(rootElement.querySelector('[data-ui-layer="exploration-panel"]')).not.toBeNull();
    expect(rootElement.querySelector('.filter-status')).toBeNull();

    await applyFilterRange(rootElement, '평형', '20', '');
    await applyFilterRange(rootElement, '가격', '', '15');
    await applyFilterRange(rootElement, '세대수', '300', '');

    const filterPanel = rootElement.querySelector<HTMLElement>(
      '[data-ui-layer="filter-controls"]',
    );
    expect(filterPanel?.dataset.filterState).toBe('active');
    expect(filterPanel?.textContent).not.toContain('필터 3개 적용');
    expect(filterPanel?.textContent).toContain('20평 이상');
    expect(filterPanel?.textContent).toContain('15억 이하');
    expect(filterPanel?.textContent).toContain('300세대 이상');

    const resetButton = rootElement.querySelector<HTMLButtonElement>(
      'button[aria-label="마커 필터 초기화"]',
    );
    expect(resetButton).not.toBeNull();
    await act(async () => {
      resetButton?.click();
    });
    await flushAsyncState();

    const resetFilterPanel = rootElement.querySelector<HTMLElement>(
      '[data-ui-layer="filter-controls"]',
    );
    expect(resetFilterPanel?.dataset.filterState).toBe('idle');
    expect(resetFilterPanel?.querySelector('.filter-status')).toBeNull();
    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.45,
          swLng: 126.85,
          neLat: 37.7,
          neLng: 127.2,
          pyeongMin: null,
          pyeongMax: null,
          priceEokMin: null,
          priceEokMax: null,
          ageMin: null,
          ageMax: null,
          unitMin: null,
          unitMax: null,
          bcRatMin: null,
          bcRatMax: null,
          vlRatMin: null,
          vlRatMax: null,
        }),
      }),
    );

    unmount(root);
  });

  it('filter control을 documented complex marker request field에 적용한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse([]));
    vi.stubGlobal('fetch', fetchMock);

    const { root, rootElement } = await renderApp({ initialMapLevel: 4 });
    await flushAsyncState();

    await applyFilterRange(rootElement, '평형', '20', '34');
    await applyFilterRange(rootElement, '가격', '8.5', '15');
    await applyFilterRange(rootElement, '입주년차', '5', '25');
    await applyFilterRange(rootElement, '세대수', '300', '1200');
    await applyFilterRange(rootElement, '건폐율', '55.5', '72.3');
    await applyFilterRange(rootElement, '용적률', '180.1', '250.6');

    expect(fetchMock).toHaveBeenLastCalledWith(
      resolveApiUrl('/api/v1/map/complexes'),
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify({
          swLat: 37.45,
          swLng: 126.85,
          neLat: 37.7,
          neLng: 127.2,
          pyeongMin: 20,
          pyeongMax: 34,
          priceEokMin: 8.5,
          priceEokMax: 15,
          ageMin: 5,
          ageMax: 25,
          unitMin: 300,
          unitMax: 1200,
          bcRatMin: 55.5,
          bcRatMax: 72.3,
          vlRatMin: 180.1,
          vlRatMax: 250.6,
        }),
      }),
    );

    unmount(root);
  });
});
