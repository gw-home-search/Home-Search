import { useEffect, useMemo, useRef, useState } from 'react';

import type { ComplexSelection, MapDisplayMode, MapFocusTarget, MapViewport } from '../../app/mapAppTypes';
import type { MapMarkersResult } from './api/fetchMapMarkers';
import { KakaoMapSurface, type KakaoMapRuntimeState } from './KakaoMapSurface';
import { MapOverlayPanels } from './MapOverlayPanels';
import { MapControlRail } from './controls/MapControlRail';
import { DistanceMeasureBar } from './tools/DistanceMeasureBar';
import type { MapPoint } from './tools/mapToolTypes';
import { useMapToolState } from './tools/useMapToolState';
import { NearbyPlacePanel } from '../nearby-places/NearbyPlacePanel';
import type { NearbyPlaceCategory } from '../nearby-places/api/fetchNearbyPlaces';
import { useNearbyPlaces } from '../nearby-places/useNearbyPlaces';

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
  nearbyPlaceComplexId: number | null;
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
  nearbyPlaceComplexId,
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
  const [mapDisplayMode, setMapDisplayMode] = useState<MapDisplayMode>('roadmap');
  const toolToggleRef = useRef<HTMLButtonElement>(null);
  const commerceToggleRef = useRef<HTMLButtonElement>(null);
  const mapTools = useMapToolState();
  const [nearbyPlaceCategory, setNearbyPlaceCategory] = useState<NearbyPlaceCategory>('CAFE');
  const [selectedNearbyPlaceId, setSelectedNearbyPlaceId] = useState<string | null>(null);
  const nearbyPlaces = useNearbyPlaces(nearbyPlaceComplexId, mapTools.activeTool === 'commerce');
  const visibleNearbyPlaces = nearbyPlaces.data?.categories
    .find((category) => category.category === nearbyPlaceCategory)
    ?.places.slice(0, 5) ?? [];
  const roadviewInitialPoint = useMemo(
    () => resolveRoadviewInitialPoint(markers, selectedComplex, focusTarget),
    [focusTarget, markers, selectedComplex],
  );

  useEffect(() => {
    setNearbyPlaceCategory('CAFE');
    setSelectedNearbyPlaceId(null);
    if (mapTools.activeTool === 'commerce' && nearbyPlaceComplexId == null) {
      mapTools.exitActiveTool();
    }
  }, [mapTools.activeTool, mapTools.exitActiveTool, nearbyPlaceComplexId]);

  return (
    <section
      aria-label="지도 화면"
      className="map-surface"
      data-cadastral-visible={mapTools.cadastralEnabled}
      data-map-display-mode={mapDisplayMode}
      data-map-level={viewport.level}
      data-map-tool={mapTools.activeTool}
    >
      <KakaoMapSurface
        appKey={appKey}
        focusTarget={focusTarget}
        initialLevel={initialLevel}
        level={viewport.level}
        activeTool={mapTools.activeTool}
        cadastralEnabled={mapTools.cadastralEnabled}
        distanceState={mapTools.distance}
        mapDisplayMode={mapDisplayMode}
        markers={markers}
        nearbyPlaces={visibleNearbyPlaces}
        nearbyPlaceCategory={nearbyPlaceCategory}
        roadviewInitialPoint={roadviewInitialPoint}
        roadviewState={mapTools.roadviewState}
        selectedComplex={selectedComplex}
        selectedNearbyPlaceId={selectedNearbyPlaceId}
        onDistanceLengthChange={mapTools.setDistanceLength}
        onDistancePointAdd={mapTools.addDistancePoint}
        onExitRoadview={mapTools.exitActiveTool}
        onComplexMarkerSelect={onComplexMarkerSelect}
        onRegionMarkerSelect={onRegionMarkerSelect}
        onNearbyPlaceSelect={setSelectedNearbyPlaceId}
        onRuntimeErrorChange={setMapRuntimeError}
        onRuntimeStateChange={setMapRuntimeState}
        onRoadviewStateChange={mapTools.setRoadviewState}
        onViewportChange={onViewportChange}
      />

      {mapTools.activeTool === 'roadview' ? null : (
        <MapControlRail
          activeTool={mapTools.activeTool}
          cadastralEnabled={mapTools.cadastralEnabled}
          commerceAvailable={nearbyPlaceComplexId != null}
          disabled={mapRuntimeState !== 'ready'}
          displayMode={mapDisplayMode}
          level={viewport.level}
          commerceToggleRef={commerceToggleRef}
          toolToggleRef={toolToggleRef}
          onCadastralChange={mapTools.setCadastralEnabled}
          onDisplayModeChange={setMapDisplayMode}
          onToolModeChange={mapTools.changeTool}
          onZoomIn={onZoomIn}
          onZoomOut={onZoomOut}
        />
      )}

      {mapTools.activeTool === 'distance' ? (
        <DistanceMeasureBar
          state={mapTools.distance}
          onComplete={mapTools.completeDistance}
          onExit={mapTools.exitActiveTool}
          onReset={mapTools.resetDistance}
          onUndo={mapTools.undoDistancePoint}
        />
      ) : null}

      {mapTools.activeTool === 'commerce' ? (
        <NearbyPlacePanel
          data={nearbyPlaces.data}
          error={nearbyPlaces.error}
          selectedCategory={nearbyPlaceCategory}
          selectedPlaceId={selectedNearbyPlaceId}
          state={nearbyPlaces.state}
          onCategoryChange={(category) => {
            setNearbyPlaceCategory(category);
            setSelectedNearbyPlaceId(null);
          }}
          onClose={() => {
            mapTools.exitActiveTool();
            queueMicrotask(() => commerceToggleRef.current?.focus());
          }}
          onPlaceSelect={setSelectedNearbyPlaceId}
          onRetry={nearbyPlaces.retry}
        />
      ) : null}

      {mapTools.activeTool === 'roadview' ? null : (
        <MapOverlayPanels
          activeFilterCount={activeFilterCount}
          bounds={viewport.bounds}
          cadastralEnabled={mapTools.cadastralEnabled}
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
        />
      )}
    </section>
  );
}

function resolveRoadviewInitialPoint(
  markers: MapMarkersResult | null,
  selectedComplex: ComplexSelection | null,
  focusTarget: MapFocusTarget | null,
): MapPoint | null {
  if (markers?.kind === 'complex' && selectedComplex) {
    const selectedMarker = markers.markers.find((marker) =>
      marker.parcelId === selectedComplex.parcelId
      && (selectedComplex.complexId == null || marker.complexId === selectedComplex.complexId));
    if (selectedMarker) return { lat: selectedMarker.lat, lng: selectedMarker.lng };
  }
  return focusTarget ? { lat: focusTarget.lat, lng: focusTarget.lng } : null;
}
