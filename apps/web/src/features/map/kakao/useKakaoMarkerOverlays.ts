import { useEffect, useRef } from 'react';

import type { ComplexSelection } from '../../../app/mapAppTypes';
import type { MapMarkersResult } from '../api/fetchMapMarkers';
import {
  createComplexMarkerViewModel,
  createRegionMarkerViewModel,
  isComplexMarkerSelected,
  regionMarkerDensityForLevel,
  type ComplexMapMarker,
  type RegionMapMarker,
} from '../markerViewModel';
import type { KakaoCustomOverlay, KakaoMap, KakaoMapsApi } from './loadKakaoMapSdk';
import { clearKakaoOverlays, createKakaoOverlay } from './overlayLifecycle';
import type { KakaoMapRuntimeState } from './useKakaoMapRuntime';

type MarkerOverlayEntry = {
  element: HTMLButtonElement;
  kind: MapMarkersResult['kind'];
  lat: number;
  lng: number;
  overlay: KakaoCustomOverlay;
};

type UseKakaoMarkerOverlaysArgs = {
  activeTool: string;
  level: number;
  map: KakaoMap | null;
  maps: KakaoMapsApi | null;
  markers: MapMarkersResult | null;
  runtimeState: KakaoMapRuntimeState;
  selectedComplex: ComplexSelection | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
};

export function useKakaoMarkerOverlays({
  activeTool,
  level,
  map,
  maps,
  markers,
  runtimeState,
  selectedComplex,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
}: UseKakaoMarkerOverlaysArgs): void {
  const entriesRef = useRef(new Map<string, MarkerOverlayEntry>());
  const ownerMapRef = useRef<KakaoMap | null>(null);

  useEffect(() => {
    const entries = entriesRef.current;
    if (ownerMapRef.current !== map) {
      clearEntries(entries);
      ownerMapRef.current = map;
    }
    if (runtimeState !== 'ready' || !map || !maps || !markers) {
      clearEntries(entries);
      return;
    }

    const nextKeys = new Set<string>();
    if (markers.kind === 'complex') {
      for (const marker of markers.markers) {
        const viewModel = createComplexMarkerViewModel(
          marker,
          isComplexMarkerSelected(marker, selectedComplex),
        );
        const key = `complex:${viewModel.key}`;
        nextKeys.add(key);
        const entry = ensureEntry(entries, key, 'complex', marker.lat, marker.lng, map, maps);
        updateComplexContent(entry.element, marker, viewModel, onComplexMarkerSelect, activeTool !== 'none');
      }
    } else {
      for (const marker of markers.markers) {
        const viewModel = createRegionMarkerViewModel(marker);
        const key = `region:${viewModel.key}`;
        nextKeys.add(key);
        const entry = ensureEntry(entries, key, 'region', marker.lat, marker.lng, map, maps);
        updateRegionContent(entry.element, marker, viewModel, level, onRegionMarkerSelect, activeTool !== 'none');
      }
    }

    for (const [key, entry] of entries) {
      if (!nextKeys.has(key)) {
        entry.overlay.setMap(null);
        entries.delete(key);
      }
    }
  }, [activeTool, level, map, maps, markers, onComplexMarkerSelect, onRegionMarkerSelect, runtimeState, selectedComplex]);

  useEffect(() => () => {
    clearEntries(entriesRef.current);
    ownerMapRef.current = null;
  }, []);
}

function ensureEntry(
  entries: Map<string, MarkerOverlayEntry>,
  key: string,
  kind: MapMarkersResult['kind'],
  lat: number,
  lng: number,
  map: KakaoMap,
  maps: KakaoMapsApi,
): MarkerOverlayEntry {
  const current = entries.get(key);
  if (current && current.kind === kind && current.lat === lat && current.lng === lng) return current;
  if (current) current.overlay.setMap(null);

  const element = document.createElement('button');
  const entry = {
    element,
    kind,
    lat,
    lng,
    overlay: createKakaoOverlay(map, maps, lat, lng, 1, element),
  };
  entries.set(key, entry);
  return entry;
}

function updateComplexContent(
  element: HTMLButtonElement,
  marker: ComplexMapMarker,
  viewModel: ReturnType<typeof createComplexMarkerViewModel>,
  onSelect: (marker: ComplexMapMarker) => void,
  interactionDisabled: boolean,
): void {
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
  element.replaceChildren(kicker, price, subtitle);
  element.onclick = () => onSelect(marker);
}

function updateRegionContent(
  element: HTMLButtonElement,
  marker: RegionMapMarker,
  viewModel: ReturnType<typeof createRegionMarkerViewModel>,
  level: number,
  onSelect: (marker: RegionMapMarker) => void,
  interactionDisabled: boolean,
): void {
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
  element.replaceChildren(name, action);
  element.onclick = () => onSelect(marker);
}

function clearEntries(entries: Map<string, MarkerOverlayEntry>): void {
  clearKakaoOverlays(Array.from(entries.values(), (entry) => entry.overlay));
  entries.clear();
}
