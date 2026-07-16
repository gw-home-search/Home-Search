import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { MapOverlayPanels } from './MapOverlayPanels';

describe('MapOverlayPanels 지도 오버레이', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
  });

  it('선택한 complex marker를 aria-pressed와 data-state로 노출한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <MapOverlayPanels
          activeFilterCount={0}
          bounds={{ swLat: 37.4, swLng: 126.9, neLat: 37.6, neLng: 127.1 }}
          cadastralEnabled={false}
          mapRuntimeError="fallback"
          mapRuntimeState="error"
          markerError={null}
          markerState="ready"
          level={4}
          markers={{
            kind: 'complex',
            markers: [{
              parcelId: 1001,
              complexId: 501,
              name: '선택 단지',
              lat: 37.5,
              lng: 127,
              latestDealAmount: 125000,
              unitCntSum: 740,
            }],
          }}
          selectedComplex={{ parcelId: 1001, complexId: 501 }}
          onComplexMarkerSelect={vi.fn()}
          onRegionMarkerSelect={vi.fn()}
          onRetryMarkers={vi.fn()}
          onResetFilters={vi.fn()}
        />,
      );
    });

    const marker = host.querySelector<HTMLButtonElement>('[data-fallback-marker-id="complex-1001-501"]');
    expect(marker?.getAttribute('aria-pressed')).toBe('true');
    expect(marker?.dataset.state).toBe('selected');
    expect(marker?.classList.contains('map-marker-complex')).toBe(true);
    expect(marker?.dataset.markerShape).toBe('price-card');
    expect(marker?.querySelector('.map-marker-kicker')?.textContent).toBe('최근 실거래');
    expect(marker?.querySelector('.map-marker-price')?.textContent).toBe('12.5억');
    expect(marker?.querySelector('.map-marker-subtitle')?.textContent).toBe('선택 단지 · 740세대');
  });

  it('region marker는 name/unit 두 band와 split-card shape를 노출한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <MapOverlayPanels
          activeFilterCount={0}
          bounds={{ swLat: 37.4, swLng: 126.9, neLat: 37.6, neLng: 127.1 }}
          cadastralEnabled={false}
          mapRuntimeError="fallback"
          mapRuntimeState="error"
          markerError={null}
          markerState="ready"
          level={10}
          markers={{ kind: 'region', level: 'si-do', markers: [{ id: 1, name: '서울', lat: 37.5, lng: 127, unitCntSum: 1200 }] }}
          selectedComplex={null}
          onComplexMarkerSelect={vi.fn()}
          onRegionMarkerSelect={vi.fn()}
          onRetryMarkers={vi.fn()}
          onResetFilters={vi.fn()}
        />,
      );
    });

    const marker = host.querySelector<HTMLButtonElement>('[data-fallback-marker-id="region-1"]');
    expect(marker?.dataset.markerShape).toBe('split-card');
    expect(marker?.dataset.markerDensity).toBe('standard');
    expect(marker?.querySelector('.map-marker-region-name')?.textContent).toBe('서울');
    expect(marker?.querySelector('.map-marker-region-unit')?.textContent).toBe('1,200세대');
  });

  it('전국 overview level도 region marker의 name/unit 두 band를 유지한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <MapOverlayPanels
          activeFilterCount={0}
          bounds={{ swLat: 33, swLng: 124, neLat: 39, neLng: 132 }}
          cadastralEnabled={false}
          level={12}
          mapRuntimeError="fallback"
          mapRuntimeState="error"
          markerError={null}
          markerState="ready"
          markers={{ kind: 'region', level: 'si-do', markers: [{ id: 1, name: '강원특별자치도', lat: 37.8, lng: 128.2, unitCntSum: 335822 }] }}
          selectedComplex={null}
          onComplexMarkerSelect={vi.fn()}
          onRegionMarkerSelect={vi.fn()}
          onRetryMarkers={vi.fn()}
          onResetFilters={vi.fn()}
        />,
      );
    });

    const marker = host.querySelector<HTMLButtonElement>('[data-fallback-marker-id="region-1"]');
    expect(marker?.dataset.markerDensity).toBe('overview');
    expect(marker?.querySelector('.map-marker-region-name')?.textContent).toBe('강원특별자치도');
    expect(marker?.querySelector('.map-marker-region-unit')?.textContent).toBe('335,822세대');
  });

  it('map runtime error와 marker empty notice를 하나의 map-local stack에 배치한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <MapOverlayPanels
          activeFilterCount={0}
          bounds={{ swLat: 37.4, swLng: 126.9, neLat: 37.6, neLng: 127.1 }}
          cadastralEnabled={false}
          mapRuntimeError="fallback"
          mapRuntimeState="error"
          markerError={null}
          markerState="empty"
          level={10}
          markers={{ kind: 'complex', markers: [] }}
          selectedComplex={null}
          onComplexMarkerSelect={vi.fn()}
          onRegionMarkerSelect={vi.fn()}
          onRetryMarkers={vi.fn()}
          onResetFilters={vi.fn()}
        />,
      );
    });

    expect(host.querySelectorAll('.map-notices > .map-feedback')).toHaveLength(2);
  });
});
