import type { CSSProperties } from 'react';

import type { ComplexSelection } from '../../app/mapAppTypes';
import { RequestStateNotice } from '../../shared/RequestStateNotice';
import type {
  MapBoundsRequest,
  MapMarkersResult,
} from './api/fetchMapMarkers';
import type { KakaoMapRuntimeState } from './KakaoMapSurface';
import { MapToolNotice } from './tools/MapToolNotice';
import {
  createComplexMarkerViewModel,
  createRegionMarkerViewModel,
  isComplexMarkerSelected,
  regionMarkerDensityForLevel,
  type ComplexMapMarker,
  type RegionMapMarker,
} from './markerViewModel';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
type MapOverlayPanelsProps = {
  activeFilterCount: number;
  bounds: MapBoundsRequest;
  cadastralEnabled: boolean;
  mapRuntimeError: string | null;
  mapRuntimeState: KakaoMapRuntimeState;
  markerError: string | null;
  markerState: MarkerRequestState;
  level: number;
  markers: MapMarkersResult | null;
  hiddenMarkerCount?: number;
  selectedComplex: ComplexSelection | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
  onRetryMarkers: () => void;
  onResetFilters: () => void;
};

export function MapOverlayPanels({
  activeFilterCount,
  bounds,
  cadastralEnabled,
  mapRuntimeError,
  mapRuntimeState,
  markerError,
  markerState,
  level,
  markers,
  hiddenMarkerCount = 0,
  selectedComplex,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
  onRetryMarkers,
  onResetFilters,
}: MapOverlayPanelsProps) {
  return (
    <>
      {mapRuntimeState === 'ready' || markers == null ? null : (
        <FallbackMarkerLayer
          bounds={bounds}
          level={level}
          markers={markers}
          selectedComplex={selectedComplex}
          onComplexMarkerSelect={onComplexMarkerSelect}
          onRegionMarkerSelect={onRegionMarkerSelect}
        />
      )}

      <div className="map-notices">
        <MapToolNotice cadastralEnabled={cadastralEnabled} />
        {hiddenMarkerCount > 0 ? <p className="map-density-note" role="status">가까운 단지 {hiddenMarkerCount.toLocaleString()}개는 확대하면 표시됩니다</p> : null}
        <RequestStateNotice
          className="map-feedback"
          state={markerState}
          loadingMessage="이 지역의 단지를 불러오는 중"
          emptyMessage={activeFilterCount > 0
            ? '조건에 맞는 단지가 없습니다'
            : '이 지도 영역에는 표시할 단지가 없습니다'}
          errorMessage="단지 정보를 불러오지 못했어요"
          secondaryMessage="지도 이동과 확대·축소는 계속 사용할 수 있습니다"
          technicalError={markerError}
          retryAriaLabel="마커 다시 불러오기"
          onRetry={onRetryMarkers}
          secondaryAction={activeFilterCount > 0 ? (
            <button type="button" onClick={onResetFilters}>필터 전체 초기화</button>
          ) : null}
        />

        {mapRuntimeError && markerState !== 'error' ? (
          <RequestStateNotice
            className="map-feedback"
            state="error"
            loadingMessage="지도를 불러오는 중"
            emptyMessage=""
            errorMessage="지도를 불러오지 못했어요"
            secondaryMessage="기본 지도 화면에서 탐색을 계속할 수 있습니다"
            technicalError={mapRuntimeError}
          />
        ) : null}
      </div>
    </>
  );
}

function FallbackMarkerLayer({
  bounds,
  level,
  markers,
  selectedComplex,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
}: {
  bounds: MapBoundsRequest;
  level: number;
  markers: MapMarkersResult;
  selectedComplex: ComplexSelection | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
}) {
  if (markers.markers.length === 0) {
    return null;
  }

  return (
    <ul aria-label="대체 지도 마커" className="fallback-marker-layer">
      {markers.kind === 'complex'
        ? markers.markers.map((marker) => {
            const viewModel = createComplexMarkerViewModel(
              marker,
              isComplexMarkerSelected(marker, selectedComplex),
            );
            return (
            <li className="fallback-marker-position-complex" key={viewModel.key} style={mapMarkerPointStyle(marker.lat, marker.lng, bounds)}>
              <button
                type="button"
                aria-label={viewModel.ariaLabel}
                aria-pressed={viewModel.selected}
                className="fallback-map-marker map-marker map-marker-complex"
                data-fallback-marker-id={`complex-${viewModel.key}`}
                data-marker-shape={viewModel.shape}
                data-state={viewModel.state}
                onClick={() => {
                  onComplexMarkerSelect(marker);
                }}
              >
                <span className="map-marker-kicker">{viewModel.kicker}</span>
                <strong className="map-marker-price">{viewModel.price}</strong>
                <span className="map-marker-subtitle">{viewModel.meta}</span>
              </button>
            </li>
            );
          })
        : markers.markers.map((marker) => {
            const viewModel = createRegionMarkerViewModel(marker);
            return (
            <li className="fallback-marker-position-region" key={viewModel.key} style={mapMarkerPointStyle(marker.lat, marker.lng, bounds)}>
              <button
                type="button"
                aria-label={viewModel.ariaLabel}
                className="fallback-map-marker map-marker map-marker-region"
                data-fallback-marker-id={`region-${viewModel.key}`}
                data-marker-density={regionMarkerDensityForLevel(level)}
                data-marker-shape={viewModel.shape}
                onClick={() => {
                  onRegionMarkerSelect(marker);
                }}
              >
                <strong className="map-marker-region-name">{viewModel.name}</strong>
                <span className="map-marker-region-unit">{viewModel.meta}</span>
              </button>
            </li>
          );})}
    </ul>
  );
}

function mapMarkerPointStyle(lat: number, lng: number, bounds: MapBoundsRequest): CSSProperties {
  const lngRange = bounds.neLng - bounds.swLng;
  const latRange = bounds.neLat - bounds.swLat;
  const x = lngRange === 0 ? 50 : ((lng - bounds.swLng) / lngRange) * 100;
  const y = latRange === 0 ? 50 : 100 - ((lat - bounds.swLat) / latRange) * 100;

  return {
    left: `${clampPercent(x, 8, 92)}%`,
    top: `${clampPercent(y, 14, 88)}%`,
  };
}

function clampPercent(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return 50;
  }

  return Math.min(max, Math.max(min, value));
}
