import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { NearbyPlacePanel } from './NearbyPlacePanel';
import type { NearbyPlaces } from './api/fetchNearbyPlaces';

const DATA: NearbyPlaces = {
  complexId: 501,
  center: { lat: 37.321, lng: 127.109 },
  radiusMeters: 800,
  source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
  generatedAt: '2026-07-13T03:00:01Z',
  categories: [
    {
      category: 'CAFE', label: '카페', matchedCount: 18, returnedCount: 1, hasMore: true,
      retrievedAt: '2026-07-13T03:00:00Z',
      places: [{
        placeId: 'kakao:1', name: '가까운 카페', categoryDetail: null, lat: 37.322, lng: 127.108,
        distanceMeters: 72, address: '경기도 주소', roadAddress: null, phone: null, placeUrl: null,
      }],
    },
    {
      category: 'HOSPITAL', label: '병원', matchedCount: 2, returnedCount: 0, hasMore: true,
      retrievedAt: '2026-07-13T03:00:00Z', places: [],
    },
  ],
};

describe('NearbyPlacePanel 주변 장소 패널', () => {
  let root: Root | null = null;
  afterEach(() => {
    if (root) act(() => root?.unmount());
    root = null;
  });

  it('Kakao 검색 기준 count tab과 거리순 목록을 표시하고 선택을 동기화한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onCategoryChange = vi.fn();
    const onPlaceSelect = vi.fn();
    await act(async () => root?.render(
      <NearbyPlacePanel
        data={DATA}
        error={null}
        selectedCategory="CAFE"
        selectedPlaceId={null}
        state="ready"
        onCategoryChange={onCategoryChange}
        onClose={vi.fn()}
        onPlaceSelect={onPlaceSelect}
        onRetry={vi.fn()}
      />,
    ));

    expect(host.textContent).toContain('반경 800m · Kakao 장소 검색 기준');
    expect(host.querySelector('button[aria-label="카페 18개"]')).not.toBeNull();
    expect(host.textContent).toContain('가까운 카페');
    expect(host.textContent).toContain('72m');
    await act(async () => host.querySelector<HTMLButtonElement>('[data-place-id="kakao:1"]')?.click());
    expect(onPlaceSelect).toHaveBeenCalledWith('kakao:1');
  });

  it('로딩 중에도 6개 상권 종류를 보여주고 선택할 수 있다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);
    const onCategoryChange = vi.fn();

    await act(async () => root?.render(
      <NearbyPlacePanel
        data={null}
        error={null}
        selectedCategory="CAFE"
        selectedPlaceId={null}
        state="loading"
        onCategoryChange={onCategoryChange}
        onClose={vi.fn()}
        onPlaceSelect={vi.fn()}
        onRetry={vi.fn()}
      />,
    ));

    const categories = host.querySelectorAll('[aria-label="상권 카테고리"] [role="tab"]');
    expect(categories).toHaveLength(6);
    expect(host.textContent).toContain('카페');
    expect(host.textContent).toContain('음식점');
    expect(host.textContent).toContain('편의점');
    expect(host.textContent).toContain('병원');
    expect(host.textContent).toContain('약국');
    expect(host.textContent).toContain('학교');

    await act(async () => host.querySelector<HTMLButtonElement>('button[aria-label="학교"]')?.click());
    expect(onCategoryChange).toHaveBeenCalledWith('SCHOOL');
  });

  it('category tab은 roving tabindex와 화살표 keyboard selection을 제공한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);
    const onCategoryChange = vi.fn();
    await act(async () => root?.render(
      <NearbyPlacePanel
        data={DATA}
        error={null}
        selectedCategory="CAFE"
        selectedPlaceId={null}
        state="ready"
        onCategoryChange={onCategoryChange}
        onClose={vi.fn()}
        onPlaceSelect={vi.fn()}
        onRetry={vi.fn()}
      />,
    ));

    const tabs = Array.from(host.querySelectorAll<HTMLButtonElement>('[role="tab"]'));
    expect(tabs[0]?.tabIndex).toBe(0);
    expect(tabs[1]?.tabIndex).toBe(-1);
    tabs[0]?.focus();
    await act(async () => tabs[0]?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    expect(onCategoryChange).toHaveBeenCalledWith('RESTAURANT');
    expect(document.activeElement).toBe(tabs[1]);
    expect(tabs[0]?.getAttribute('aria-controls')).toBe('nearby-place-panel-CAFE');
    expect(host.querySelector('[role="tabpanel"]')?.id).toBe('nearby-place-panel-CAFE');
    host.remove();
  });
});
