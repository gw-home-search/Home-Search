import { useCallback, useEffect, useState } from 'react';

import type { MapBoundsRequest } from '../api/fetchMapMarkers';
import type { MapFocusTarget, MapViewport } from '../../../app/mapAppTypes';

const INITIAL_MARKER_BOUNDS: MapBoundsRequest = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

export function useMapViewport(initialMapLevel: number) {
  const [viewport, setViewport] = useState<MapViewport>(() => ({
    bounds: INITIAL_MARKER_BOUNDS,
    level: initialMapLevel,
  }));
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null);

  useEffect(() => {
    setViewport((current) => current.level === initialMapLevel
      ? current
      : { ...current, level: initialMapLevel });
  }, [initialMapLevel]);

  const handleViewportChange = useCallback((nextViewport: MapViewport) => {
    setViewport((current) => sameViewport(current, nextViewport) ? current : nextViewport);
  }, []);

  const handleZoomIn = useCallback(() => {
    setViewport((current) => ({ ...current, level: Math.max(1, current.level - 1) }));
  }, []);

  const handleZoomOut = useCallback(() => {
    setViewport((current) => ({ ...current, level: current.level + 1 }));
  }, []);

  const focusMap = useCallback((lat: number, lng: number, level: number, delta: number) => {
    setViewport(viewportAroundPoint(lat, lng, level, delta));
    setMapFocusTarget((current) => ({
      lat,
      lng,
      level,
      seq: (current?.seq ?? 0) + 1,
    }));
  }, []);

  return {
    focusMap,
    handleViewportChange,
    handleZoomIn,
    handleZoomOut,
    mapFocusTarget,
    viewport,
  };
}

function viewportAroundPoint(
  lat: number,
  lng: number,
  level: number,
  delta: number,
): MapViewport {
  return {
    bounds: {
      swLat: lat - delta,
      swLng: lng - delta,
      neLat: lat + delta,
      neLng: lng + delta,
    },
    level,
  };
}

function sameViewport(first: MapViewport, second: MapViewport): boolean {
  return (
    first.level === second.level
    && first.bounds.swLat === second.bounds.swLat
    && first.bounds.swLng === second.bounds.swLng
    && first.bounds.neLat === second.bounds.neLat
    && first.bounds.neLng === second.bounds.neLng
  );
}
