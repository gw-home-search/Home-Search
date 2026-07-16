import { useEffect, useRef, useState } from 'react';

import type { MapDisplayMode, MapFocusTarget, MapViewport } from '../../../app/mapAppTypes';
import { clampMapLevel, MAX_MAP_LEVEL, MIN_MAP_LEVEL } from '../markerViewModel';
import {
  loadKakaoMapSdk,
  type KakaoMap,
  type KakaoMapsApi,
} from './loadKakaoMapSdk';

export type KakaoMapRuntimeState = 'loading' | 'ready' | 'error';

type UseKakaoMapRuntimeArgs = {
  activeTool: string;
  appKey: string;
  cadastralEnabled: boolean;
  focusTarget: MapFocusTarget | null;
  initialLevel: number;
  level: number;
  mapDisplayMode: MapDisplayMode;
  onRuntimeErrorChange: (message: string | null) => void;
  onRuntimeStateChange: (state: KakaoMapRuntimeState) => void;
  onViewportChange: (viewport: MapViewport) => void;
};

const INITIAL_CENTER = { lat: 36.35, lng: 127.8 };

export function useKakaoMapRuntime({
  activeTool,
  appKey,
  cadastralEnabled,
  focusTarget,
  initialLevel,
  level,
  mapDisplayMode,
  onRuntimeErrorChange,
  onRuntimeStateChange,
  onViewportChange,
}: UseKakaoMapRuntimeArgs) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [map, setMap] = useState<KakaoMap | null>(null);
  const [maps, setMaps] = useState<KakaoMapsApi | null>(null);
  const [runtimeState, setRuntimeState] = useState<KakaoMapRuntimeState>('loading');

  useEffect(() => {
    let disposed = false;
    let idleHandler: (() => void) | null = null;
    let idleMap: KakaoMap | null = null;
    let loadedMaps: KakaoMapsApi | null = null;
    const host = hostRef.current;
    if (!host) return undefined;

    setMap(null);
    setMaps(null);
    setRuntimeState('loading');
    onRuntimeStateChange('loading');
    onRuntimeErrorChange(null);

    loadKakaoMapSdk(appKey)
      .then((nextMaps) => {
        if (disposed) return;
        const nextMap = new nextMaps.Map(host, {
          center: new nextMaps.LatLng(INITIAL_CENTER.lat, INITIAL_CENTER.lng),
          level: clampMapLevel(initialLevel),
        });
        nextMap.setMinLevel?.(MIN_MAP_LEVEL);
        nextMap.setMaxLevel?.(MAX_MAP_LEVEL);
        const notifyViewport = () => onViewportChange(viewportFromMap(nextMap));

        idleMap = nextMap;
        loadedMaps = nextMaps;
        idleHandler = notifyViewport;
        nextMaps.event.addListener(nextMap, 'idle', notifyViewport);
        setMap(nextMap);
        setMaps(nextMaps);
        setRuntimeState('ready');
        onRuntimeStateChange('ready');
        notifyViewport();
      })
      .catch((error: unknown) => {
        if (disposed) return;
        setMap(null);
        setMaps(null);
        setRuntimeState('error');
        onRuntimeStateChange('error');
        onRuntimeErrorChange(runtimeErrorMessage(error));
      });

    return () => {
      disposed = true;
      if (idleMap && idleHandler) {
        loadedMaps?.event.removeListener?.(idleMap, 'idle', idleHandler);
      }
    };
  }, [appKey, initialLevel, onRuntimeErrorChange, onRuntimeStateChange, onViewportChange]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !focusTarget || !map || !maps) return;
    map.setCenter?.(new maps.LatLng(focusTarget.lat, focusTarget.lng));
    map.setLevel?.(clampMapLevel(focusTarget.level));
  }, [focusTarget, map, maps, runtimeState]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !map || map.getLevel() === level) return;
    map.setLevel?.(level);
  }, [level, map, runtimeState]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !map || !maps) return;
    map.removeOverlayMapTypeId(maps.MapTypeId.TERRAIN);
    map.setMapTypeId(mapDisplayMode === 'hybrid' ? maps.MapTypeId.HYBRID : maps.MapTypeId.ROADMAP);
    if (mapDisplayMode === 'terrain') map.addOverlayMapTypeId(maps.MapTypeId.TERRAIN);
  }, [map, mapDisplayMode, maps, runtimeState]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !map || !maps) return undefined;
    if (cadastralEnabled) map.addOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);
    else map.removeOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);
    return () => map.removeOverlayMapTypeId(maps.MapTypeId.USE_DISTRICT);
  }, [cadastralEnabled, map, maps, runtimeState]);

  useEffect(() => {
    const host = hostRef.current;
    if (runtimeState !== 'ready' || !host || !map || typeof ResizeObserver === 'undefined') {
      return undefined;
    }
    const observer = new ResizeObserver(() => {
      const center = map.getCenter?.();
      map.relayout?.();
      if (center) map.setCenter?.(center);
    });
    observer.observe(host);
    return () => observer.disconnect();
  }, [map, runtimeState]);

  useEffect(() => {
    if (runtimeState !== 'ready' || !map) return;
    const center = map.getCenter?.();
    requestAnimationFrame(() => {
      map.relayout?.();
      if (center) map.setCenter?.(center);
    });
  }, [activeTool, map, runtimeState]);

  return { hostRef, map, maps, runtimeState };
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

function runtimeErrorMessage(error: unknown): string {
  const detail = error instanceof Error ? ` ${error.message}` : '';
  return `카카오 지도를 불러오지 못했습니다.${detail}`;
}
