import { useCallback, useEffect, useRef, useState } from 'react';

import {
  fetchMapMarkers,
  type MapBoundsRequest,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';
import { loadKakaoMapsSdk } from '../features/map/kakao/loadKakaoMapsSdk';
import type {
  KakaoCustomOverlay,
  KakaoEventHandle,
  KakaoMap,
  KakaoMapsApi,
} from '../features/map/kakao/kakaoMapsTypes';
import './App.css';

type MapRuntimeState = 'loading' | 'ready' | 'error';
type MarkerRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

const INITIAL_CENTER = {
  lat: 37.5663,
  lng: 126.978,
};

type MapViewport = {
  bounds: MapBoundsRequest;
  level: number;
};

type AppProps = {
  initialMapLevel?: number;
};

export function App({ initialMapLevel = 4 }: AppProps) {
  const mapContainerRef = useRef<HTMLDivElement | null>(null);
  const kakaoMapsRef = useRef<KakaoMapsApi | null>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const overlaysRef = useRef<KakaoCustomOverlay[]>([]);
  const markerRequestSeq = useRef(0);

  const [mapState, setMapState] = useState<MapRuntimeState>('loading');
  const [mapError, setMapError] = useState<string | null>(null);
  const [displayLevel, setDisplayLevel] = useState(initialMapLevel);
  const [viewport, setViewport] = useState<MapViewport | null>(null);
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('idle');
  const [markerError, setMarkerError] = useState<string | null>(null);

  const clearOverlays = useCallback(() => {
    overlaysRef.current.forEach((overlay) => {
      overlay.setMap(null);
    });
    overlaysRef.current = [];
  }, []);

  const syncViewportFromMap = useCallback((map: KakaoMap) => {
    const bounds = map.getBounds();
    const southWest = bounds.getSouthWest();
    const northEast = bounds.getNorthEast();
    const nextViewport: MapViewport = {
      bounds: {
        swLat: southWest.getLat(),
        swLng: southWest.getLng(),
        neLat: northEast.getLat(),
        neLng: northEast.getLng(),
      },
      level: map.getLevel(),
    };

    setDisplayLevel(nextViewport.level);
    setViewport((current) => (sameViewport(current, nextViewport) ? current : nextViewport));
  }, []);

  useEffect(() => {
    let cancelled = false;
    let idleListener: KakaoEventHandle | null = null;

    setMapState('loading');
    setMapError(null);
    setViewport(null);
    setMarkers(null);
    setMarkerState('idle');
    setMarkerError(null);
    clearOverlays();

    loadKakaoMapsSdk()
      .then((kakaoMaps) => {
        if (cancelled || !mapContainerRef.current) {
          return;
        }

        kakaoMapsRef.current = kakaoMaps;
        const map = new kakaoMaps.Map(mapContainerRef.current, {
          center: new kakaoMaps.LatLng(INITIAL_CENTER.lat, INITIAL_CENTER.lng),
          level: initialMapLevel,
        });
        mapRef.current = map;
        idleListener = kakaoMaps.event.addListener(map, 'idle', () => {
          syncViewportFromMap(map);
        });

        setMapState('ready');
        syncViewportFromMap(map);
      })
      .catch((error: unknown) => {
        if (cancelled) {
          return;
        }

        mapRef.current = null;
        kakaoMapsRef.current = null;
        clearOverlays();
        setMapState('error');
        setMapError(error instanceof Error ? error.message : 'Unknown Kakao map error');
      });

    return () => {
      cancelled = true;
      if (idleListener && kakaoMapsRef.current) {
        kakaoMapsRef.current.event.removeListener(idleListener);
      }
      clearOverlays();
      mapRef.current = null;
      kakaoMapsRef.current = null;
    };
  }, [clearOverlays, initialMapLevel, syncViewportFromMap]);

  useEffect(() => {
    if (mapState !== 'ready' || !viewport) {
      return;
    }

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
  }, [mapState, viewport]);

  useEffect(() => {
    clearOverlays();

    if (mapState !== 'ready' || !markers || markers.markers.length === 0) {
      return;
    }

    const map = mapRef.current;
    const kakaoMaps = kakaoMapsRef.current;
    if (!map || !kakaoMaps) {
      return;
    }

    if (markers.kind === 'complex') {
      overlaysRef.current = markers.markers.map(
        (marker) =>
          new kakaoMaps.CustomOverlay({
            position: new kakaoMaps.LatLng(marker.lat, marker.lng),
            content: complexMarkerContent(marker.latestDealAmount, marker.unitCntSum),
            yAnchor: 1,
          }),
      );
    } else {
      overlaysRef.current = markers.markers.map(
        (marker) =>
          new kakaoMaps.CustomOverlay({
            position: new kakaoMaps.LatLng(marker.lat, marker.lng),
            content: regionMarkerContent(marker.name),
            yAnchor: 1,
          }),
      );
    }
    overlaysRef.current.forEach((overlay) => {
      overlay.setMap(map);
    });

    return () => {
      clearOverlays();
    };
  }, [clearOverlays, mapState, markers]);

  function updateMapLevel(nextLevel: number) {
    const map = mapRef.current;
    if (!map) {
      setDisplayLevel(nextLevel);
      return;
    }

    map.setLevel(nextLevel);
    syncViewportFromMap(map);
  }

  function handleZoomIn() {
    updateMapLevel(Math.max(1, displayLevel - 1));
  }

  function handleZoomOut() {
    updateMapLevel(displayLevel + 1);
  }

  return (
    <main className="app-shell">
      <div className="app-bar">
        <h1 className="app-title">Home Search</h1>
        <span className="app-status">Level {displayLevel}</span>
      </div>

      <div
        ref={mapContainerRef}
        className="map-canvas"
        data-testid="kakao-map-surface"
        data-map-state={mapState}
        data-map-level={displayLevel}
        data-marker-kind={markers?.kind ?? 'none'}
        aria-label="Kakao map surface"
      />

      <div className="map-controls" aria-label="Map controls">
        <button type="button" aria-label="Zoom in" onClick={handleZoomIn}>
          +
        </button>
        <button type="button" aria-label="Zoom out" onClick={handleZoomOut}>
          -
        </button>
      </div>

      {mapState === 'error' ? (
        <p role="alert" className="map-alert">
          Map unavailable. {mapError}
        </p>
      ) : null}

      {markerState === 'loading' ? (
        <p role="status" aria-live="polite" className="map-status">
          Loading markers
        </p>
      ) : null}

      {markerState === 'empty' ? (
        <p role="status" aria-live="polite" className="map-status">
          No markers in this area
        </p>
      ) : null}

      {markerState === 'error' ? (
        <p role="alert" className="map-alert">
          Marker data unavailable. Map remains usable.
          {markerError ? ` ${markerError}` : null}
        </p>
      ) : null}
    </main>
  );
}

function sameViewport(current: MapViewport | null, next: MapViewport): boolean {
  if (!current) {
    return false;
  }

  return (
    current.level === next.level &&
    current.bounds.swLat === next.bounds.swLat &&
    current.bounds.swLng === next.bounds.swLng &&
    current.bounds.neLat === next.bounds.neLat &&
    current.bounds.neLng === next.bounds.neLng
  );
}

function complexMarkerContent(latestDealAmount: number | null, unitCntSum: number): HTMLElement {
  const marker = document.createElement('div');
  marker.className = 'map-marker map-marker--complex';
  marker.dataset.markerKind = 'complex';

  const amount = document.createElement('strong');
  amount.textContent = formatAmount(latestDealAmount);

  const units = document.createElement('span');
  units.textContent = `${unitCntSum.toLocaleString()} units`;

  marker.append(amount, units);
  return marker;
}

function regionMarkerContent(name: string): HTMLElement {
  const marker = document.createElement('div');
  marker.className = 'map-marker map-marker--region';
  marker.dataset.markerKind = 'region';

  const label = document.createElement('strong');
  label.textContent = name;

  const type = document.createElement('span');
  type.textContent = 'region';

  marker.append(label, type);
  return marker;
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return 'No recent trade';
  }

  return `${amount.toLocaleString()} 10k KRW`;
}
