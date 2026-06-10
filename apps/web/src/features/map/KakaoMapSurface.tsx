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
  level: number;
  markers: MapMarkersResult | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
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
  level,
  markers,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
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

    if (runtimeState !== 'ready' || !map || map.getLevel() === level) {
      return;
    }

    map.setLevel?.(level);
  }, [level, runtimeState]);

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
            overlayForMarker(
              map,
              maps,
              marker.lat,
              marker.lng,
              overlayContentForRegionMarker(marker, onRegionMarkerSelect),
            ),
          );

    overlaysRef.current = nextOverlays;

    return () => {
      clearOverlays(overlaysRef.current);
      overlaysRef.current = [];
    };
  }, [markers, onComplexMarkerSelect, onRegionMarkerSelect, runtimeState]);

  return (
    <div
      ref={hostRef}
      aria-label="카카오 지도 화면"
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
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void,
): HTMLElement {
  const element = document.createElement('button');
  const kicker = document.createElement('span');
  const price = document.createElement('strong');
  const subtitle = markerSubtitle(marker);

  element.type = 'button';
  element.className = 'kakao-map-overlay kakao-map-overlay-complex';
  element.setAttribute('aria-label', complexMarkerAriaLabel(marker));

  kicker.className = 'kakao-map-overlay-kicker';
  kicker.textContent = marker.latestDealAmount == null ? '거래 없음' : '최근 실거래';
  price.className = 'kakao-map-overlay-price';
  price.textContent = formatMarkerAmount(marker.latestDealAmount);

  element.append(kicker, price);
  if (subtitle) {
    const subtitleElement = document.createElement('span');
    subtitleElement.className = 'kakao-map-overlay-subtitle';
    subtitleElement.textContent = subtitle;
    element.append(subtitleElement);
  }
  element.addEventListener('click', () => {
    onComplexMarkerSelect(marker);
  });
  return element;
}

function complexMarkerAriaLabel(marker: ComplexMapMarker): string {
  return marker.complexId == null
    ? `필지 ${marker.parcelId} 상세 열기`
    : `필지 ${marker.parcelId} 단지 ${marker.complexId} 상세 열기`;
}

function overlayContentForRegionMarker(
  marker: RegionMapMarker,
  onRegionMarkerSelect: (marker: RegionMapMarker) => void,
): HTMLElement {
  const element = document.createElement('button');
  const name = document.createElement('strong');
  const action = document.createElement('span');

  element.type = 'button';
  element.className = 'kakao-map-overlay kakao-map-overlay-region';
  element.setAttribute('aria-label', `지역 이동 ${marker.name}`);

  name.className = 'kakao-map-overlay-region-name';
  name.textContent = marker.name;
  action.className = 'kakao-map-overlay-region-action';
  action.textContent = regionMarkerUnitLabel(marker);

  element.append(name, action);
  element.addEventListener('click', () => {
    onRegionMarkerSelect(marker);
  });
  return element;
}

function regionMarkerUnitLabel(marker: RegionMapMarker): string {
  if (marker.unitCntSum == null || marker.unitCntSum <= 0) {
    return '세대수 없음';
  }

  return `${marker.unitCntSum.toLocaleString()}세대`;
}

function formatMarkerAmount(amount: number | null): string {
  if (amount == null) {
    return '최근 거래 없음';
  }

  if (amount >= 10000) {
    const eok = amount / 10000;
    const formatted = Number.isInteger(eok) ? eok.toLocaleString() : eok.toFixed(1);
    return `${formatted}억`;
  }

  return `${amount.toLocaleString()}만`;
}

function markerSubtitle(marker: ComplexMapMarker): string | null {
  if (marker.name) {
    return marker.name;
  }

  if (marker.unitCntSum != null && marker.unitCntSum > 0) {
    return `${marker.unitCntSum.toLocaleString()}세대`;
  }

  return null;
}

function runtimeErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? ` ${error.message}` : '';
  return `카카오 지도를 불러오지 못했습니다.${detail}`;
}
