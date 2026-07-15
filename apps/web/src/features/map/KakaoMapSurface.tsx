import { useEffect, useRef, useState } from 'react';

import type { ComplexSelection, MapDisplayMode } from '../../app/mapAppTypes';
import { RoadviewPane } from './tools/RoadviewPane';
import type {
  DistanceMeasureState,
  MapPoint,
  MapToolMode,
  RoadviewRuntimeState,
} from './tools/mapToolTypes';
import type { MapBoundsRequest, MapMarkersResult } from './api/fetchMapMarkers';
import {
  loadKakaoMapSdk,
  type KakaoCustomOverlay,
  type KakaoMap,
  type KakaoMapsApi,
} from './kakao/loadKakaoMapSdk';
import { useKakaoDistanceMeasure } from './kakao/useKakaoDistanceMeasure';
import { useKakaoRoadview } from './kakao/useKakaoRoadview';
import type { NearbyPlace, NearbyPlaceCategory } from '../nearby-places/api/fetchNearbyPlaces';
import type { MapNearbyPlace } from '../nearby-places/mapNearbyPlaces';
import { createNearbyPlaceCategoryIcon } from '../nearby-places/NearbyPlaceCategoryIcon';
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
  activeTool: MapToolMode;
  cadastralEnabled: boolean;
  distanceState: DistanceMeasureState;
  facilitiesEnabled: boolean;
  focusTarget: MapFocusTarget | null;
  initialLevel: number;
  level: number;
  mapDisplayMode: MapDisplayMode;
  markers: MapMarkersResult | null;
  nearbyPlaces: MapNearbyPlace[];
  roadviewInitialPoint: MapPoint | null;
  roadviewState: RoadviewRuntimeState;
  selectedComplex: ComplexSelection | null;
  selectedNearbyPlaceId: string | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onDistanceLengthChange: (lengthMeters: number) => void;
  onDistancePointAdd: (point: MapPoint) => void;
  onExitRoadview: () => void;
  onNearbyPlaceSelect: (placeId: string | null) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
  onRuntimeErrorChange: (message: string | null) => void;
  onRuntimeStateChange: (state: KakaoMapRuntimeState) => void;
  onRoadviewStateChange: (state: RoadviewRuntimeState) => void;
  onViewportChange: (viewport: MapViewport) => void;
};

const INITIAL_CENTER = {
  lat: 36.35,
  lng: 127.8,
};

export function KakaoMapSurface({
  appKey,
  activeTool,
  cadastralEnabled,
  distanceState,
  facilitiesEnabled,
  focusTarget,
  initialLevel,
  level,
  mapDisplayMode,
  markers,
  nearbyPlaces,
  roadviewInitialPoint,
  roadviewState,
  selectedComplex,
  selectedNearbyPlaceId,
  onComplexMarkerSelect,
  onDistanceLengthChange,
  onDistancePointAdd,
  onExitRoadview,
  onNearbyPlaceSelect,
  onRegionMarkerSelect,
  onRuntimeErrorChange,
  onRuntimeStateChange,
  onRoadviewStateChange,
  onViewportChange,
}: KakaoMapSurfaceProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<KakaoMap | null>(null);
  const mapsApiRef = useRef<KakaoMapsApi | null>(null);
  const overlaysRef = useRef<KakaoCustomOverlay[]>([]);
  const nearbyPlaceOverlaysRef = useRef<KakaoCustomOverlay[]>([]);
  const [runtimeState, setRuntimeState] = useState<KakaoMapRuntimeState>('loading');
  const [roadviewHost, setRoadviewHost] = useState<HTMLDivElement | null>(null);

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
      clearOverlays(nearbyPlaceOverlaysRef.current);
      nearbyPlaceOverlaysRef.current = [];

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
    const map = mapRef.current;
    const maps = mapsApiRef.current;

    if (runtimeState !== 'ready' || !map || !maps) {
      return;
    }

    map.removeOverlayMapTypeId(maps.MapTypeId.TERRAIN);
    map.setMapTypeId(
      mapDisplayMode === 'hybrid' ? maps.MapTypeId.HYBRID : maps.MapTypeId.ROADMAP,
    );
    if (mapDisplayMode === 'terrain') {
      map.addOverlayMapTypeId(maps.MapTypeId.TERRAIN);
    }
  }, [mapDisplayMode, runtimeState]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsApiRef.current;
    if (runtimeState !== 'ready' || !map || !maps) return;

    if (cadastralEnabled) map.addOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);
    else map.removeOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);

    return () => map.removeOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);
  }, [cadastralEnabled, runtimeState]);

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
                activeTool !== 'none',
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
              overlayContentForRegionMarker(marker, level, onRegionMarkerSelect, activeTool !== 'none'),
            ),
          );

    overlaysRef.current = nextOverlays;

    return () => {
      clearOverlays(overlaysRef.current);
      overlaysRef.current = [];
    };
  }, [activeTool, level, markers, onComplexMarkerSelect, onRegionMarkerSelect, runtimeState, selectedComplex]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsApiRef.current;
    clearOverlays(nearbyPlaceOverlaysRef.current);
    nearbyPlaceOverlaysRef.current = [];
    if (runtimeState !== 'ready' || !facilitiesEnabled || !map || !maps) return undefined;

    nearbyPlaceOverlaysRef.current = nearbyPlaces.map((item) => overlayForMarker(
      map,
      maps,
      item.place.lat,
      item.place.lng,
      selectedNearbyPlaceId === item.place.placeId ? 3 : 2,
      overlayContentForNearbyPlace(
        item.place,
        item.category,
        selectedNearbyPlaceId === item.place.placeId,
        onNearbyPlaceSelect,
      ),
    ));

    return () => {
      clearOverlays(nearbyPlaceOverlaysRef.current);
      nearbyPlaceOverlaysRef.current = [];
    };
  }, [facilitiesEnabled, nearbyPlaces, onNearbyPlaceSelect, runtimeState, selectedNearbyPlaceId]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !facilitiesEnabled || !selectedNearbyPlaceId) return;
    const place = nearbyPlaces.find((candidate) => candidate.place.placeId === selectedNearbyPlaceId)?.place;
    const map = mapRef.current;
    const maps = mapsApiRef.current;
    if (place && map && maps) map.setCenter?.(new maps.LatLng(place.lat, place.lng));
  }, [facilitiesEnabled, nearbyPlaces, runtimeState, selectedNearbyPlaceId]);

  useEffect(() => {
    const map = mapRef.current;
    const maps = mapsApiRef.current;
    if (runtimeState !== 'ready' || !facilitiesEnabled || !map || !maps) return undefined;
    const clearSelection = () => onNearbyPlaceSelect(null);
    maps.event.addListener(map, 'click', clearSelection);
    return () => maps.event.removeListener?.(map, 'click', clearSelection);
  }, [facilitiesEnabled, onNearbyPlaceSelect, runtimeState]);

  useKakaoRoadview({
    active: activeTool === 'roadview',
    initialPoint: roadviewInitialPoint,
    map: mapRef.current,
    maps: mapsApiRef.current,
    roadviewHost,
    onStateChange: onRoadviewStateChange,
  });

  useKakaoDistanceMeasure({
    active: activeTool === 'distance',
    map: mapRef.current,
    maps: mapsApiRef.current,
    state: distanceState,
    onLengthChange: onDistanceLengthChange,
    onPointAdd: onDistancePointAdd,
  });

  useEffect(() => {
    if (runtimeState !== 'ready' || !mapRef.current) return;
    const map = mapRef.current;
    const center = map.getCenter?.();
    requestAnimationFrame(() => {
      map.relayout?.();
      if (center) map.setCenter?.(center);
    });
  }, [activeTool, runtimeState]);

  return (
    <div className="kakao-map-stage" data-roadview-open={activeTool === 'roadview'}>
      <div
        ref={hostRef}
        aria-label="카카오 지도 화면"
        className="kakao-map-host"
        data-kakao-map-state={runtimeState}
        hidden={activeTool === 'roadview'}
      />
      {activeTool === 'roadview' ? (
        <RoadviewPane ref={setRoadviewHost} state={roadviewState} onClose={onExitRoadview} />
      ) : null}
    </div>
  );
}

function overlayForMarker(
  map: KakaoMap,
  maps: KakaoMapsApi,
  lat: number,
  lng: number,
  zIndex: number,
  content: HTMLElement,
): KakaoCustomOverlay {
  const position = new maps.LatLng(lat, lng);
  const overlay = new maps.CustomOverlay({
    position,
    content,
    yAnchor: 1,
    zIndex,
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
  interactionDisabled: boolean,
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
  element.disabled = interactionDisabled;
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
  interactionDisabled: boolean,
): HTMLElement {
  const viewModel = createRegionMarkerViewModel(marker);
  const element = document.createElement('button');
  const name = document.createElement('strong');
  const action = document.createElement('span');

  element.type = 'button';
  element.className = 'kakao-map-overlay map-marker map-marker-region';
  element.setAttribute('aria-label', viewModel.ariaLabel);
  element.disabled = interactionDisabled;
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

function overlayContentForNearbyPlace(
  place: NearbyPlace,
  category: NearbyPlaceCategory,
  selected: boolean,
  onSelect: (placeId: string | null) => void,
): HTMLElement {
  const element = document.createElement('button');
  const icon = document.createElement('span');
  const label = document.createElement('span');
  element.type = 'button';
  element.className = 'kakao-map-overlay nearby-place-marker';
  element.dataset.category = category;
  element.dataset.selected = String(selected);
  element.setAttribute('aria-label', place.name);
  element.setAttribute('aria-pressed', String(selected));
  icon.className = 'nearby-place-marker-icon';
  icon.setAttribute('aria-hidden', 'true');
  icon.append(createNearbyPlaceCategoryIcon(category));
  element.append(icon);
  if (selected) {
    label.className = 'nearby-place-marker-label';
    label.textContent = place.name;
    element.append(label);
  }
  element.addEventListener('click', (event) => {
    event.stopPropagation();
    onSelect(place.placeId);
  });
  return element;
}

function runtimeErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? ` ${error.message}` : '';
  return `카카오 지도를 불러오지 못했습니다.${detail}`;
}
