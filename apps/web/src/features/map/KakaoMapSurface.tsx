import { useEffect, useRef, useState } from 'react';

import type { MapBoundsRequest, MapMarkersResult } from './api/fetchMapMarkers';
import {
  loadKakaoMapSdk,
  type KakaoCustomOverlay,
  type KakaoMap,
  type KakaoMapsApi,
} from './kakao/loadKakaoMapSdk';

type MapViewport = {
  bounds: MapBoundsRequest;
  level: number;
};

type MapFocusTarget = {
  lat: number;
  lng: number;
  level: number;
  seq: number;
};

export type KakaoMapRuntimeState = 'loading' | 'ready' | 'error';

type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];

type KakaoMapSurfaceProps = {
  appKey: string;
  focusTarget: MapFocusTarget | null;
  initialLevel: number;
  markers: MapMarkersResult | null;
  onComplexMarkerSelect: (parcelId: number) => void;
  onRuntimeErrorChange: (message: string | null) => void;
  onRuntimeStateChange: (state: KakaoMapRuntimeState) => void;
  onViewportChange: (viewport: MapViewport) => void;
};

const INITIAL_CENTER = {
  lat: 37.5663,
  lng: 126.978,
};

export function KakaoMapSurface({
  appKey,
  focusTarget,
  initialLevel,
  markers,
  onComplexMarkerSelect,
  onRuntimeErrorChange,
  onRuntimeStateChange,
  onViewportChange,
}: KakaoMapSurfaceProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsApiRef = useRef<KakaoMapsApi | null>(null);
  const overlaysRef = useRef<KakaoCustomOverlay[]>([]);
  const [runtimeState, setRuntimeState] = useState<KakaoMapRuntimeState>('loading');

  useEffect(() => {
    let disposed = false;
    let idleHandler: (() => void) | null = null;
    let idleMap: KakaoMap | null = null;
    const host = hostRef.current;

    if (!host) {
      return undefined;
    }

    setRuntimeState('loading');
    onRuntimeStateChange('loading');
    onRuntimeErrorChange(null);

    loadKakaoMapSdk(appKey)
      .then((maps) => {
        if (disposed) {
          return;
        }

        const map = new maps.Map(host, {
          center: new maps.LatLng(INITIAL_CENTER.lat, INITIAL_CENTER.lng),
          level: initialLevel,
        });
        const notifyViewport = () => {
          onViewportChange(viewportFromMap(map));
        };

        mapsApiRef.current = maps;
        mapRef.current = map;
        idleMap = map;
        idleHandler = notifyViewport;
        maps.event.addListener(map, 'idle', notifyViewport);

        setRuntimeState('ready');
        onRuntimeStateChange('ready');
        notifyViewport();
      })
      .catch((error: unknown) => {
        if (disposed) {
          return;
        }

        mapRef.current = null;
        mapsApiRef.current = null;
        setRuntimeState('error');
        onRuntimeStateChange('error');
        onRuntimeErrorChange(runtimeErrorMessage(error));
      });

    return () => {
      disposed = true;
      clearOverlays(overlaysRef.current);
      overlaysRef.current = [];

      if (idleMap && idleHandler) {
        mapsApiRef.current?.event.removeListener?.(idleMap, 'idle', idleHandler);
      }

      mapRef.current = null;
      mapsApiRef.current = null;
    };
  }, [appKey, initialLevel, onRuntimeErrorChange, onRuntimeStateChange, onViewportChange]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsApiRef.current;

    if (runtimeState !== 'ready' || !focusTarget || !map || !maps) {
      return;
    }

    map.setCenter?.(new maps.LatLng(focusTarget.lat, focusTarget.lng));
    map.setLevel?.(focusTarget.level);
  }, [focusTarget, runtimeState]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsApiRef.current;

    clearOverlays(overlaysRef.current);
    overlaysRef.current = [];

    if (runtimeState !== 'ready' || !map || !maps || !markers) {
      return undefined;
    }

    const nextOverlays =
      markers.kind === 'complex'
        ? markers.markers.map((marker) =>
            overlayForMarker(
              map,
              maps,
              marker.lat,
              marker.lng,
              overlayContentForComplexMarker(marker, onComplexMarkerSelect),
            ),
          )
        : markers.markers.map((marker) =>
            overlayForMarker(map, maps, marker.lat, marker.lng, overlayContentForRegionMarker(marker)),
          );

    overlaysRef.current = nextOverlays;

    return () => {
      clearOverlays(overlaysRef.current);
      overlaysRef.current = [];
    };
  }, [markers, onComplexMarkerSelect, runtimeState]);

  return (
    <div
      ref={hostRef}
      aria-label="Kakao map viewport"
      className="kakao-map-host"
      data-kakao-map-state={runtimeState}
    />
  );
}

function overlayForMarker(
  map: KakaoMap,
  maps: KakaoMapsApi,
  lat: number,
  lng: number,
  content: HTMLElement,
): KakaoCustomOverlay {
  const position = new maps.LatLng(lat, lng);
  const overlay = new maps.CustomOverlay({
    position,
    content,
    yAnchor: 1,
  });
  overlay.setMap(map);
  return overlay;
}

function viewportFromMap(map: KakaoMap): MapViewport {
  const bounds = map.getBounds();
  const southWest = bounds.getSouthWest();
  const northEast = bounds.getNorthEast();

  return {
    bounds: {
      swLat: southWest.getLat(),
      swLng: southWest.getLng(),
      neLat: northEast.getLat(),
      neLng: northEast.getLng(),
    },
    level: map.getLevel(),
  };
}

function clearOverlays(overlays: KakaoCustomOverlay[]) {
  overlays.forEach((overlay) => {
    overlay.setMap(null);
  });
}

function overlayContentForComplexMarker(
  marker: ComplexMapMarker,
  onComplexMarkerSelect: (parcelId: number) => void,
): HTMLElement {
  const element = document.createElement('button');
  element.type = 'button';
  element.className = 'kakao-map-overlay kakao-map-overlay-complex';
  element.setAttribute('aria-label', `Open detail for parcel ${marker.parcelId}`);
  element.textContent = `${formatAmount(marker.latestDealAmount)} · ${marker.unitCntSum} units`;
  element.addEventListener('click', () => {
    onComplexMarkerSelect(marker.parcelId);
  });
  return element;
}

function overlayContentForRegionMarker(marker: RegionMapMarker): HTMLElement {
  const element = document.createElement('span');
  element.className = 'kakao-map-overlay kakao-map-overlay-region';
  element.textContent = marker.name;
  return element;
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return 'No recent trade';
  }

  return `${amount.toLocaleString()} 10k KRW`;
}

function runtimeErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? ` ${error.message}` : '';
  return `Kakao map unavailable.${detail}`;
}
