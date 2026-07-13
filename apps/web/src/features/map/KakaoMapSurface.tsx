import { useEffect, useRef, useState } from 'react';

import type { ComplexSelection } from '../../app/mapAppTypes';
import type { MapBoundsRequest, MapMarkersResult } from './api/fetchMapMarkers';
import {
  loadKakaoMapSdk,
  type KakaoCustomOverlay,
  type KakaoMap,
  type KakaoMapsApi,
} from './kakao/loadKakaoMapSdk';
import {
  createComplexMarkerViewModel,
  createRegionMarkerViewModel,
  clampMapLevel,
  isComplexMarkerSelected,
  MAX_MAP_LEVEL,
  MIN_MAP_LEVEL,
  regionMarkerDensityForLevel,
  type ComplexMapMarker,
  type RegionMapMarker,
} from './markerViewModel';

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

type KakaoMapSurfaceProps = {
  appKey: string;
  focusTarget: MapFocusTarget | null;
  initialLevel: number;
  level: number;
  markers: MapMarkersResult | null;
  selectedComplex: ComplexSelection | null;
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
  selectedComplex,
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
          level: clampMapLevel(initialLevel),
        });
        map.setMinLevel?.(MIN_MAP_LEVEL);
        map.setMaxLevel?.(MAX_MAP_LEVEL);
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
    map.setLevel?.(clampMapLevel(focusTarget.level));
  }, [focusTarget, runtimeState]);

  useEffect(() => {
    const map = mapRef.current;

    if (runtimeState !== 'ready' || !map || map.getLevel() === level) {
      return;
    }

    map.setLevel?.(level);
  }, [level, runtimeState]);

  useEffect(() => {
    const host = hostRef.current;
    const map = mapRef.current;

    if (runtimeState !== 'ready' || !host || !map || typeof ResizeObserver === 'undefined') {
      return undefined;
    }

    const observer = new ResizeObserver(() => {
      const center = map.getCenter?.();
      map.relayout?.();
      if (center) {
        map.setCenter?.(center);
      }
    });
    observer.observe(host);

    return () => observer.disconnect();
  }, [runtimeState]);

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
              1,
              overlayContentForComplexMarker(
                marker,
                isComplexMarkerSelected(marker, selectedComplex),
                onComplexMarkerSelect,
              ),
            ),
          )
        : markers.markers.map((marker) =>
            overlayForMarker(
              map,
              maps,
              marker.lat,
              marker.lng,
              1,
              overlayContentForRegionMarker(marker, level, onRegionMarkerSelect),
            ),
          );

    overlaysRef.current = nextOverlays;

    return () => {
      clearOverlays(overlaysRef.current);
      overlaysRef.current = [];
    };
  }, [level, markers, onComplexMarkerSelect, onRegionMarkerSelect, runtimeState, selectedComplex]);

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
  yAnchor: number,
  content: HTMLElement,
): KakaoCustomOverlay {
  const position = new maps.LatLng(lat, lng);
  const overlay = new maps.CustomOverlay({
    position,
    content,
    yAnchor,
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
  isSelected: boolean,
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void,
): HTMLElement {
  const viewModel = createComplexMarkerViewModel(marker, isSelected);
  const element = document.createElement('button');
  const kicker = document.createElement('span');
  const price = document.createElement('strong');
  const subtitle = document.createElement('span');

  element.type = 'button';
  element.className = 'kakao-map-overlay map-marker map-marker-complex';
  element.setAttribute('aria-label', viewModel.ariaLabel);
  element.setAttribute('aria-pressed', String(viewModel.selected));
  element.dataset.state = viewModel.state;
  element.dataset.markerShape = viewModel.shape;

  kicker.className = 'kakao-map-overlay-kicker map-marker-kicker';
  kicker.textContent = viewModel.kicker;
  price.className = 'kakao-map-overlay-price';
  price.textContent = viewModel.price;
  subtitle.className = 'kakao-map-overlay-subtitle map-marker-subtitle';
  subtitle.textContent = viewModel.meta;

  element.append(kicker, price, subtitle);
  element.addEventListener('click', () => {
    onComplexMarkerSelect(marker);
  });
  return element;
}

function overlayContentForRegionMarker(
  marker: RegionMapMarker,
  level: number,
  onRegionMarkerSelect: (marker: RegionMapMarker) => void,
): HTMLElement {
  const viewModel = createRegionMarkerViewModel(marker);
  const element = document.createElement('button');
  const name = document.createElement('strong');
  const action = document.createElement('span');

  element.type = 'button';
  element.className = 'kakao-map-overlay map-marker map-marker-region';
  element.setAttribute('aria-label', viewModel.ariaLabel);
  element.dataset.markerShape = viewModel.shape;
  element.dataset.markerDensity = regionMarkerDensityForLevel(level);

  name.className = 'kakao-map-overlay-region-name map-marker-region-name';
  name.textContent = viewModel.name;
  action.className = 'kakao-map-overlay-region-action map-marker-region-unit';
  action.textContent = viewModel.meta;

  element.append(name, action);
  element.addEventListener('click', () => {
    onRegionMarkerSelect(marker);
  });
  return element;
}

function runtimeErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? ` ${error.message}` : '';
  return `카카오 지도를 불러오지 못했습니다.${detail}`;
}
