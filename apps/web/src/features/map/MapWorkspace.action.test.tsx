import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { MapUiCommand, MapViewport } from '../../app/mapAppTypes';
import { MapWorkspace } from './MapWorkspace';

vi.mock('./KakaoMapSurface', async () => {
  const React = await import('react');
  return {
    KakaoMapSurface: (props: {
      onRuntimeStateChange: (state: 'ready') => void;
    }) => {
      const { onRuntimeStateChange } = props;
      React.useEffect(
        () => onRuntimeStateChange('ready'),
        [onRuntimeStateChange],
      );
      return <div data-testid="kakao-map" />;
    },
  };
});

describe('지도 one-shot chatbot command', () => {
  let root: Root | undefined;
  let host: HTMLDivElement | undefined;

  afterEach(() => {
    if (root) act(() => root?.unmount());
    host?.remove();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('command 전에는 조회하지 않고 받은 category 하나로 기존 viewport hook을 한 번 실행한다', async () => {
    vi.useFakeTimers();
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(response('HOSPITAL')), {
      status: 200,
    }));
    vi.stubGlobal('fetch', fetchMock);
    const consumed = vi.fn();
    const viewport: MapViewport = {
      bounds: { swLat: 37.503, swLng: 127.072, neLat: 37.523, neLng: 127.092 },
      level: 4,
    };
    host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);

    await renderWorkspace(root, viewport, null, consumed);
    await act(async () => vi.advanceTimersByTimeAsync(500));
    expect(fetchMock).not.toHaveBeenCalled();

    const command: MapUiCommand = {
      type: 'showNearbyCategory',
      actionId: 'action-request-1-hospital',
      category: 'HOSPITAL',
    };
    await renderWorkspace(root, viewport, command, consumed);
    await act(async () => vi.advanceTimersByTimeAsync(500));
    await act(async () => Promise.resolve());

    expect(consumed).toHaveBeenCalledTimes(1);
    expect(consumed).toHaveBeenCalledWith(command.actionId);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(JSON.parse(String(fetchMock.mock.calls[0]?.[1]?.body))).toEqual({
      ...viewport.bounds,
      level: 4,
      category: 'HOSPITAL',
    });

    await renderWorkspace(root, viewport, command, consumed);
    await act(async () => vi.advanceTimersByTimeAsync(500));
    expect(consumed).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});

async function renderWorkspace(
  root: Root,
  viewport: MapViewport,
  uiCommand: MapUiCommand | null,
  onUiCommandConsumed: (actionId: string) => void,
) {
  await act(async () => root.render(
    <MapWorkspace
      activeFilterCount={0}
      appKey="test-key"
      focusTarget={null}
      hiddenMarkerCount={0}
      initialLevel={4}
      markerError={null}
      markerState="ready"
      markers={null}
      onComplexMarkerSelect={vi.fn()}
      onFilterReset={vi.fn()}
      onRegionMarkerSelect={vi.fn()}
      onRetryMarkers={vi.fn()}
      onUiCommandConsumed={onUiCommandConsumed}
      onViewportChange={vi.fn()}
      onZoomIn={vi.fn()}
      onZoomOut={vi.fn()}
      selectedComplex={null}
      uiCommand={uiCommand}
      viewport={viewport}
    />,
  ));
}

function response(category: 'HOSPITAL') {
  return {
    bounds: { swLat: 37.503, swLng: 127.072, neLat: 37.523, neLng: 127.092 },
    level: 4,
    source: { provider: 'KAKAO_LOCAL', countBasis: 'PROVIDER_SEARCH' },
    generatedAt: '2026-07-21T04:00:00Z',
    category: {
      category,
      label: '병원',
      retrievedAt: '2026-07-21T03:59:50Z',
      places: [],
    },
  };
}
