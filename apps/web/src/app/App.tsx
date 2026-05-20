import { useCallback, useEffect, useRef, useState } from 'react';

import {
  fetchMapMarkers,
  type MapBoundsRequest,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';
import { KakaoMapSurface } from '../features/map/KakaoMapSurface';
import './App.css';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';

const INITIAL_MARKER_BOUNDS: MapBoundsRequest = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

type MapViewport = {
  bounds: MapBoundsRequest;
  level: number;
};

type AppProps = {
  initialMapLevel?: number;
  kakaoMapAppKey?: string;
};

export function App({
  initialMapLevel = 4,
  kakaoMapAppKey = getConfiguredKakaoMapAppKey(),
}: AppProps) {
  const [viewport, setViewport] = useState<MapViewport>(() => ({
    bounds: INITIAL_MARKER_BOUNDS,
    level: initialMapLevel,
  }));
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);
  const [mapRuntimeError, setMapRuntimeError] = useState<string | null>(null);
  const markerRequestSeq = useRef(0);

  useEffect(() => {
    setViewport((current) => {
      if (current.level === initialMapLevel) {
        return current;
      }

      return { ...current, level: initialMapLevel };
    });
  }, [initialMapLevel]);

  useEffect(() => {
    const requestSeq = markerRequestSeq.current + 1;
    markerRequestSeq.current = requestSeq;
    let ignore = false;

    setMarkerState('loading');
    setMarkerError(null);

    fetchMapMarkers({
      bounds: viewport.bounds,
      level: viewport.level,
    })
      .then((nextMarkers) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }

        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.markers.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }

        setMarkers(null);
        setMarkerState('error');
        setMarkerError(error instanceof Error ? error.message : 'Unknown marker error');
      });

    return () => {
      ignore = true;
    };
  }, [viewport]);

  const handleViewportChange = useCallback((nextViewport: MapViewport) => {
    setViewport((current) => {
      if (sameViewport(current, nextViewport)) {
        return current;
      }

      return nextViewport;
    });
  }, []);

  function handleZoomIn() {
    setViewport((current) => ({
      ...current,
      level: Math.max(1, current.level - 1),
    }));
  }

  function handleZoomOut() {
    setViewport((current) => ({
      ...current,
      level: current.level + 1,
    }));
  }

  return (
    <main className="app-shell">
      <h1>Home Search</h1>

      <section aria-label="Map surface" className="map-surface">
        <p className="map-status">Map ready</p>
        <KakaoMapSurface
          appKey={kakaoMapAppKey}
          initialLevel={initialMapLevel}
          markers={markers}
          onRuntimeErrorChange={setMapRuntimeError}
          onViewportChange={handleViewportChange}
        />
        <div aria-label="Map controls" className="map-controls">
          <button type="button" aria-label="Zoom in" onClick={handleZoomIn}>
            +
          </button>
          <button type="button" aria-label="Zoom out" onClick={handleZoomOut}>
            -
          </button>
        </div>

        {markers?.kind === 'complex' && markers.markers.length > 0 ? (
          <ul aria-label="Complex markers">
            {markers.markers.map((marker) => (
              <li key={marker.parcelId} data-marker-id={marker.parcelId}>
                {formatAmount(marker.latestDealAmount)} - {marker.unitCntSum} units
              </li>
            ))}
          </ul>
        ) : null}

        {markers?.kind === 'region' && markers.markers.length > 0 ? (
          <ul aria-label="Region markers">
            {markers.markers.map((marker) => (
              <li key={marker.id} data-marker-id={marker.id}>
                {marker.name}
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      {markerState === 'loading' ? (
        <p role="status" aria-live="polite">
          Loading markers
        </p>
      ) : null}

      {markerState === 'empty' ? (
        <p role="status" aria-live="polite">
          No markers in this area
        </p>
      ) : null}

      {markerState === 'error' ? (
        <p role="alert">
          Marker data unavailable. Map remains usable.
          {markerError ? ` ${markerError}` : null}
        </p>
      ) : null}

      {mapRuntimeError && markerState !== 'error' ? <p role="alert">{mapRuntimeError}</p> : null}
    </main>
  );
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return 'No recent trade';
  }

  return `${amount.toLocaleString()} 10k KRW`;
}

function sameViewport(first: MapViewport, second: MapViewport): boolean {
  return (
    first.level === second.level &&
    first.bounds.swLat === second.bounds.swLat &&
    first.bounds.swLng === second.bounds.swLng &&
    first.bounds.neLat === second.bounds.neLat &&
    first.bounds.neLng === second.bounds.neLng
  );
}

function getConfiguredKakaoMapAppKey(): string {
  return import.meta.env.VITE_KAKAO_MAP_APP_KEY ?? '';
}
