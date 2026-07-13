import { useState } from 'react';

import type { ComplexSelection, MapFocusTarget, MapViewport } from '../../app/mapAppTypes';
import type { MapMarkersResult } from './api/fetchMapMarkers';
import { KakaoMapSurface, type KakaoMapRuntimeState } from './KakaoMapSurface';
import { MapOverlayPanels } from './MapOverlayPanels';

type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];

type MapWorkspaceProps = {
  activeFilterCount: number;
  appKey: string;
  focusTarget: MapFocusTarget | null;
  initialLevel: number;
  markerError: string | null;
  markerState: 'loading' | 'ready' | 'empty' | 'error';
  markers: MapMarkersResult | null;
  hiddenMarkerCount: number;
  selectedComplex: ComplexSelection | null;
  viewport: MapViewport;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onFilterReset: () => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
  onRetryMarkers: () => void;
  onViewportChange: (viewport: MapViewport) => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
};

export function MapWorkspace({
  activeFilterCount,
  appKey,
  focusTarget,
  initialLevel,
  markerError,
  markerState,
  markers,
  hiddenMarkerCount,
  selectedComplex,
  viewport,
  onComplexMarkerSelect,
  onFilterReset,
  onRegionMarkerSelect,
  onRetryMarkers,
  onViewportChange,
  onZoomIn,
  onZoomOut,
}: MapWorkspaceProps) {
  const [mapRuntimeState, setMapRuntimeState] = useState<KakaoMapRuntimeState>('loading');
  const [mapRuntimeError, setMapRuntimeError] = useState<string | null>(null);

  return (
    <section aria-label="지도 화면" className="map-surface" data-map-level={viewport.level}>
      <KakaoMapSurface
        appKey={appKey}
        focusTarget={focusTarget}
        initialLevel={initialLevel}
        level={viewport.level}
        markers={markers}
        selectedComplex={selectedComplex}
        onComplexMarkerSelect={onComplexMarkerSelect}
        onRegionMarkerSelect={onRegionMarkerSelect}
        onRuntimeErrorChange={setMapRuntimeError}
        onRuntimeStateChange={setMapRuntimeState}
        onViewportChange={onViewportChange}
      />

      <MapOverlayPanels
        activeFilterCount={activeFilterCount}
        bounds={viewport.bounds}
        mapRuntimeError={mapRuntimeError}
        mapRuntimeState={mapRuntimeState}
        markerError={markerError}
        markerState={markerState}
        level={viewport.level}
        markers={markers}
        hiddenMarkerCount={hiddenMarkerCount}
        selectedComplex={selectedComplex}
        onComplexMarkerSelect={onComplexMarkerSelect}
        onRegionMarkerSelect={onRegionMarkerSelect}
        onRetryMarkers={onRetryMarkers}
        onResetFilters={onFilterReset}
        onZoomIn={onZoomIn}
        onZoomOut={onZoomOut}
      />
    </section>
  );
}
