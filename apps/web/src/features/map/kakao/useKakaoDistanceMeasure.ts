import { useEffect, useRef } from 'react';

import type { DistanceMeasureState, MapPoint } from '../tools/mapToolTypes';
import type {
  KakaoMap,
  KakaoMapMouseEvent,
  KakaoMapsApi,
  KakaoCustomOverlay,
  KakaoPolyline,
} from './loadKakaoMapSdk';

type UseKakaoDistanceMeasureArgs = {
  active: boolean;
  map: KakaoMap | null;
  maps: KakaoMapsApi | null;
  state: DistanceMeasureState;
  onLengthChange: (lengthMeters: number) => void;
  onPointAdd: (point: MapPoint) => void;
};

export function useKakaoDistanceMeasure({
  active,
  map,
  maps,
  state,
  onLengthChange,
  onPointAdd,
}: UseKakaoDistanceMeasureArgs) {
  const polylineRef = useRef<KakaoPolyline | null>(null);
  const pointMarkersRef = useRef<KakaoCustomOverlay[]>([]);

  useEffect(() => {
    if (!active || !map || !maps) return undefined;

    const polyline = new maps.Polyline({
      map,
      path: [],
      strokeColor: '#0e7490',
      strokeOpacity: 0.9,
      strokeWeight: 4,
    });
    polylineRef.current = polyline;

    return () => {
      polyline.setMap(null);
      polylineRef.current = null;
      pointMarkersRef.current.forEach((marker) => marker.setMap(null));
      pointMarkersRef.current = [];
    };
  }, [active, map, maps]);

  useEffect(() => {
    if (!active || !map || !maps || state.phase !== 'drawing') return undefined;

    const clickHandler = (event: KakaoMapMouseEvent) => {
      onPointAdd({ lat: event.latLng.getLat(), lng: event.latLng.getLng() });
    };
    maps.event.addListener(map, 'click', clickHandler as (...args: never[]) => void);
    return () => maps.event.removeListener?.(map, 'click', clickHandler as (...args: never[]) => void);
  }, [active, map, maps, onPointAdd, state.phase]);

  useEffect(() => {
    if (!active || !map || !maps || !polylineRef.current) return;

    const path = state.points.map((point) => new maps.LatLng(point.lat, point.lng));
    polylineRef.current.setPath(path);
    onLengthChange(path.length >= 2 ? polylineRef.current.getLength() : 0);

    pointMarkersRef.current.forEach((marker) => marker.setMap(null));
    pointMarkersRef.current = path.map((position, index) => {
      const content = document.createElement('span');
      content.className = 'distance-point-marker';
      content.setAttribute('aria-hidden', 'true');
      content.dataset.pointIndex = String(index + 1);
      const overlay = new maps.CustomOverlay({ position, content, yAnchor: 0.5 });
      overlay.setMap(map);
      return overlay;
    });
  }, [active, map, maps, onLengthChange, state.points]);
}
