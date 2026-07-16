import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { ComplexSelection } from '../../../app/mapAppTypes';
import type { MapMarkersResult } from '../api/fetchMapMarkers';
import type { KakaoCustomOverlay, KakaoMap, KakaoMapsApi } from './loadKakaoMapSdk';
import { useKakaoMarkerOverlays } from './useKakaoMarkerOverlays';

const MARKERS: Extract<MapMarkersResult, { kind: 'complex' }> = {
  kind: 'complex',
  markers: [
    { parcelId: 1, complexId: 101, name: '첫 단지', lat: 37.1, lng: 127.1, latestDealAmount: 100000, unitCntSum: 500 },
    { parcelId: 2, complexId: 202, name: '둘째 단지', lat: 37.2, lng: 127.2, latestDealAmount: 90000, unitCntSum: 300 },
  ],
};

describe('useKakaoMarkerOverlays 마커 lifecycle', () => {
  let host: HTMLDivElement;
  let root: Root;
  let fake: ReturnType<typeof createFakeOverlayRuntime>;

  beforeEach(() => {
    host = document.createElement('div');
    root = createRoot(host);
    fake = createFakeOverlayRuntime();
  });

  afterEach(() => {
    act(() => root.unmount());
  });

  it('같은 marker key의 selection 변경은 overlay를 재생성하지 않고 DOM 상태만 갱신한다', async () => {
    await renderProbe(root, fake, MARKERS, null);
    const firstOverlay = fake.overlays[0];
    expect(fake.overlays).toHaveLength(2);
    expect(firstOverlay?.content.dataset.state).toBe('idle');

    await renderProbe(root, fake, MARKERS, { parcelId: 1, complexId: 101 });

    expect(fake.overlays).toHaveLength(2);
    expect(fake.overlays[0]).toBe(firstOverlay);
    expect(firstOverlay?.content.dataset.state).toBe('selected');
    expect(firstOverlay?.content.getAttribute('aria-pressed')).toBe('true');
  });

  it('marker가 제거되면 해당 overlay만 map에서 해제한다', async () => {
    await renderProbe(root, fake, MARKERS, null);
    const [retained, removed] = fake.overlays;

    await renderProbe(root, fake, { ...MARKERS, markers: [MARKERS.markers[0]!] }, null);

    expect(removed?.setMap).toHaveBeenLastCalledWith(null);
    expect(retained?.setMap).not.toHaveBeenCalledWith(null);
  });

  it('unmount하면 남아 있는 모든 overlay를 해제한다', async () => {
    await renderProbe(root, fake, MARKERS, null);
    const overlays = [...fake.overlays];

    act(() => root.unmount());

    overlays.forEach((overlay) => expect(overlay.setMap).toHaveBeenLastCalledWith(null));
    root = createRoot(host);
  });
});

function Probe({
  fake,
  markers,
  selectedComplex,
}: {
  fake: ReturnType<typeof createFakeOverlayRuntime>;
  markers: MapMarkersResult;
  selectedComplex: ComplexSelection | null;
}) {
  useKakaoMarkerOverlays({
    activeTool: 'none',
    level: 4,
    map: fake.map,
    maps: fake.maps,
    markers,
    runtimeState: 'ready',
    selectedComplex,
    onComplexMarkerSelect: fake.onComplexMarkerSelect,
    onRegionMarkerSelect: fake.onRegionMarkerSelect,
  });
  return null;
}

async function renderProbe(
  root: Root,
  fake: ReturnType<typeof createFakeOverlayRuntime>,
  markers: MapMarkersResult,
  selectedComplex: ComplexSelection | null,
): Promise<void> {
  await act(async () => {
    root.render(<Probe fake={fake} markers={markers} selectedComplex={selectedComplex} />);
  });
}

function createFakeOverlayRuntime() {
  const overlays: Array<KakaoCustomOverlay & { content: HTMLButtonElement }> = [];
  const map = {} as KakaoMap;
  const maps = {
    LatLng: class {
      constructor(_lat: number, _lng: number) {}
    },
    CustomOverlay: class {
      content: HTMLButtonElement;
      setMap = vi.fn();
      constructor(options: { content: HTMLElement | string }) {
        this.content = options.content as HTMLButtonElement;
        overlays.push(this);
      }
    },
  } as unknown as KakaoMapsApi;
  return {
    map,
    maps,
    overlays,
    onComplexMarkerSelect: vi.fn(),
    onRegionMarkerSelect: vi.fn(),
  };
}
