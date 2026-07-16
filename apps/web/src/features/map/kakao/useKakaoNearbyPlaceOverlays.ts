import { useEffect, useRef } from 'react';

import type { MapNearbyPlace } from '../../nearby-places/mapNearbyPlaces';
import { createNearbyPlaceCategoryIcon } from '../../nearby-places/NearbyPlaceCategoryIcon';
import type { NearbyPlace, NearbyPlaceCategory } from '../../nearby-places/api/fetchNearbyPlaces';
import type { KakaoCustomOverlay, KakaoMap, KakaoMapsApi } from './loadKakaoMapSdk';
import { clearKakaoOverlays, createKakaoOverlay } from './overlayLifecycle';
import type { KakaoMapRuntimeState } from './useKakaoMapRuntime';

type UseKakaoNearbyPlaceOverlaysArgs = {
  facilitiesEnabled: boolean;
  map: KakaoMap | null;
  maps: KakaoMapsApi | null;
  nearbyPlaces: MapNearbyPlace[];
  runtimeState: KakaoMapRuntimeState;
  selectedNearbyPlaceId: string | null;
  onNearbyPlaceSelect: (placeId: string | null) => void;
};

export function useKakaoNearbyPlaceOverlays({
  facilitiesEnabled,
  map,
  maps,
  nearbyPlaces,
  runtimeState,
  selectedNearbyPlaceId,
  onNearbyPlaceSelect,
}: UseKakaoNearbyPlaceOverlaysArgs): void {
  const overlaysRef = useRef<KakaoCustomOverlay[]>([]);

  useEffect(() => {
    clearKakaoOverlays(overlaysRef.current);
    overlaysRef.current = [];
    if (runtimeState !== 'ready' || !facilitiesEnabled || !map || !maps) return undefined;

    overlaysRef.current = nearbyPlaces.map((item) => createKakaoOverlay(
      map,
      maps,
      item.place.lat,
      item.place.lng,
      selectedNearbyPlaceId === item.place.placeId ? 3 : 2,
      nearbyPlaceContent(
        item.place,
        item.category,
        selectedNearbyPlaceId === item.place.placeId,
        onNearbyPlaceSelect,
      ),
    ));
    return () => {
      clearKakaoOverlays(overlaysRef.current);
      overlaysRef.current = [];
    };
  }, [facilitiesEnabled, map, maps, nearbyPlaces, onNearbyPlaceSelect, runtimeState, selectedNearbyPlaceId]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !facilitiesEnabled || !selectedNearbyPlaceId) return;
    const place = nearbyPlaces.find((candidate) => candidate.place.placeId === selectedNearbyPlaceId)?.place;
    if (place && map && maps) map.setCenter?.(new maps.LatLng(place.lat, place.lng));
  }, [facilitiesEnabled, map, maps, nearbyPlaces, runtimeState, selectedNearbyPlaceId]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !facilitiesEnabled || !map || !maps) return undefined;
    const clearSelection = () => onNearbyPlaceSelect(null);
    maps.event.addListener(map, 'click', clearSelection);
    return () => maps.event.removeListener?.(map, 'click', clearSelection);
  }, [facilitiesEnabled, map, maps, onNearbyPlaceSelect, runtimeState]);
}

function nearbyPlaceContent(
  place: NearbyPlace,
  category: NearbyPlaceCategory,
  selected: boolean,
  onSelect: (placeId: string | null) => void,
): HTMLElement {
  const element = document.createElement('button');
  const icon = document.createElement('span');
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
    const label = document.createElement('span');
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
