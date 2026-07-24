import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { MapViewport } from '../../../app/mapAppTypes';
import { fetchMapMarkers, type MapMarkersResult } from '../api/fetchMapMarkers';
import { useMapMarkers } from './useMapMarkers';

vi.mock('../api/fetchMapMarkers', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/fetchMapMarkers')>()),
  fetchMapMarkers: vi.fn(),
}));

const fetchMapMarkersMock = vi.mocked(fetchMapMarkers);

describe('useMapMarkers 지도 요청 수명주기', () => {
  let root: Root | null = null;
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
    }
    host?.remove();
    root = null;
    host = null;
    fetchMapMarkersMock.mockReset();
  });

  it('viewport가 바뀌거나 unmount되면 더 이상 필요하지 않은 bbox 요청을 abort한다', async () => {
    const first = deferred<MapMarkersResult>();
    const second = deferred<MapMarkersResult>();
    fetchMapMarkersMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    ({ root, host } = await render(viewport(37.4)));

    const firstSignal = fetchMapMarkersMock.mock.calls[0]?.[1];
    expect(firstSignal).toBeInstanceOf(AbortSignal);
    expect(firstSignal?.aborted).toBe(false);

    await act(async () => root?.render(<Harness viewport={viewport(37.5)} />));
    const secondSignal = fetchMapMarkersMock.mock.calls[1]?.[1];
    expect(firstSignal?.aborted).toBe(true);
    expect(secondSignal).toBeInstanceOf(AbortSignal);
    expect(secondSignal?.aborted).toBe(false);

    await act(async () => {
      second.resolve({ kind: 'complex', markers: [] });
    });
    expect(host.dataset.state).toBe('empty');

    act(() => root?.unmount());
    root = null;
    expect(secondSignal?.aborted).toBe(true);
  });

  it('background refresh 실패 시 이미 표시한 marker를 유지한다', async () => {
    const refresh = deferred<MapMarkersResult>();
    fetchMapMarkersMock
      .mockResolvedValueOnce({
        kind: 'complex',
        markers: [{
          parcelId: 1001,
          complexId: 501,
          name: '유지할 단지',
          lat: 37.5,
          lng: 127,
          latestDealAmount: null,
          unitCntSum: null,
        }],
      })
      .mockReturnValueOnce(refresh.promise);
    ({ root, host } = await render(viewport(37.4)));
    expect(host.dataset.count).toBe('1');

    await act(async () => root?.render(<Harness viewport={viewport(37.5)} />));
    await act(async () => refresh.reject(new TypeError('Failed to fetch')));

    expect(host.dataset.state).toBe('error');
    expect(host.dataset.count).toBe('1');
    expect(host.textContent).not.toContain('Failed to fetch');
  });
});

function Harness({ viewport: currentViewport }: { viewport: MapViewport }) {
  const { markers, markerState } = useMapMarkers(currentViewport);
  return (
    <div
      id="map-marker-harness"
      data-count={markers?.markers.length ?? 0}
      data-state={markerState}
    />
  );
}

async function render(currentViewport: MapViewport) {
  const container = document.createElement('div');
  document.body.append(container);
  const nextRoot = createRoot(container);
  await act(async () => nextRoot.render(<Harness viewport={currentViewport} />));
  return {
    root: nextRoot,
    host: container.querySelector<HTMLDivElement>('#map-marker-harness')!,
  };
}

function viewport(swLat: number): MapViewport {
  return {
    bounds: {
      swLat,
      swLng: 126.8,
      neLat: swLat + 0.2,
      neLng: 127.2,
    },
    level: 4,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((next, fail) => {
    resolve = next;
    reject = fail;
  });
  return { promise, reject, resolve };
}
