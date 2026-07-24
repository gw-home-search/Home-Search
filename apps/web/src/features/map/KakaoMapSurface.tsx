import { useState } from 'react';

import type {
  ComplexSelection,
  MapDisplayMode,
  MapFocusTarget,
  MapViewport,
} from '../../app/mapAppTypes';
import type { MapMarkersResult } from './api/fetchMapMarkers';
import { useKakaoDistanceMeasure } from './kakao/useKakaoDistanceMeasure';
import { useKakaoMapRuntime, type KakaoMapRuntimeState } from './kakao/useKakaoMapRuntime';
import { useKakaoMarkerOverlays } from './kakao/useKakaoMarkerOverlays';
import { useKakaoNearbyPlaceOverlays } from './kakao/useKakaoNearbyPlaceOverlays';
import { useKakaoRoadview } from './kakao/useKakaoRoadview';
import type { ComplexMapMarker, RegionMapMarker } from './markerViewModel';
import type { MapNearbyPlace } from '../nearby-places/mapNearbyPlaces';
import { RoadviewPane } from './tools/RoadviewPane';
import type {
  DistanceMeasureState,
  MapPoint,
  MapToolMode,
  RoadviewRuntimeState,
} from './tools/mapToolTypes';
import type { RequestFailure } from '../../shared/http/requestFailure';

export type { KakaoMapRuntimeState } from './kakao/useKakaoMapRuntime';

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
  runtimeRetrySequence: number;
  selectedComplex: ComplexSelection | null;
  selectedNearbyPlaceId: string | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onDistanceLengthChange: (lengthMeters: number) => void;
  onDistancePointAdd: (point: MapPoint) => void;
  onExitRoadview: () => void;
  onNearbyPlaceSelect: (placeId: string | null) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
  onRuntimeErrorChange: (failure: RequestFailure | null) => void;
  onRuntimeStateChange: (state: KakaoMapRuntimeState) => void;
  onRoadviewStateChange: (state: RoadviewRuntimeState) => void;
  onViewportChange: (viewport: MapViewport) => void;
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
  runtimeRetrySequence,
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
  const [roadviewHost, setRoadviewHost] = useState<HTMLDivElement | null>(null);
  const { hostRef, map, maps, runtimeState } = useKakaoMapRuntime({
    activeTool,
    appKey,
    cadastralEnabled,
    focusTarget,
    initialLevel,
    level,
    mapDisplayMode,
    retrySequence: runtimeRetrySequence,
    onRuntimeErrorChange,
    onRuntimeStateChange,
    onViewportChange,
  });

  useKakaoMarkerOverlays({
    activeTool,
    level,
    map,
    maps,
    markers,
    runtimeState,
    selectedComplex,
    onComplexMarkerSelect,
    onRegionMarkerSelect,
  });
  useKakaoNearbyPlaceOverlays({
    facilitiesEnabled,
    map,
    maps,
    nearbyPlaces,
    runtimeState,
    selectedNearbyPlaceId,
    onNearbyPlaceSelect,
  });
  useKakaoRoadview({
    active: activeTool === 'roadview',
    initialPoint: roadviewInitialPoint,
    map,
    maps,
    roadviewHost,
    onStateChange: onRoadviewStateChange,
  });
  useKakaoDistanceMeasure({
    active: activeTool === 'distance',
    map,
    maps,
    state: distanceState,
    onLengthChange: onDistanceLengthChange,
    onPointAdd: onDistancePointAdd,
  });

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
