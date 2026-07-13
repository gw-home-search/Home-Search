import { useCallback, useEffect, useState } from 'react';

import type { MapBoundsRequest } from '../api/fetchMapMarkers';
import type { MapFocusTarget, MapViewport } from '../../../app/mapAppTypes';
import {
  clampMapLevel,
  MAX_MAP_LEVEL,
  MIN_MAP_LEVEL,
} from '../markerViewModel';

const INITIAL_MARKER_BOUNDS: MapBoundsRequest = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

export function useMapViewport(initialMapLevel: number) {
  const [viewport, setViewport] = useState<MapViewport>(() => ({
    bounds: INITIAL_MARKER_BOUNDS,
    level: clampMapLevel(initialMapLevel),
  }));
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null);

  useEffect(() => {
    const nextLevel = clampMapLevel(initialMapLevel);
    setViewport((current) => current.level === nextLevel
      ? current
      : { ...current, level: nextLevel });
  }, [initialMapLevel]);

  const handleViewportChange = useCallback((nextViewport: MapViewport) => {
    const clampedViewport = { ...nextViewport, level: clampMapLevel(nextViewport.level) };
    setViewport((current) => sameViewport(current, clampedViewport) ? current : clampedViewport);
  }, []);

  const handleZoomIn = useCallback(() => {
    setViewport((current) => ({ ...current, level: Math.max(MIN_MAP_LEVEL, current.level - 1) }));
  }, []);

  const handleZoomOut = useCallback(() => {
    setViewport((current) => ({ ...current, level: Math.min(MAX_MAP_LEVEL, current.level + 1) }));
  }, []);

  const focusMap = useCallback((lat: number, lng: number, level: number, delta: number) => {
    const nextLevel = clampMapLevel(level);
    setViewport(viewportAroundPoint(lat, lng, nextLevel, delta));
    setMapFocusTarget((current) => ({
      lat,
      lng,
      level: nextLevel,
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
