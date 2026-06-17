import type { CSSProperties } from 'react';

import type {
  MapBoundsRequest,
  MapMarkersResult,
} from './api/fetchMapMarkers';
import type { KakaoMapRuntimeState } from './KakaoMapSurface';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];

type MapOverlayPanelsProps = {
  bounds: MapBoundsRequest;
  level: number;
  mapRuntimeError: string | null;
  mapRuntimeState: KakaoMapRuntimeState;
  markerError: string | null;
  markerState: MarkerRequestState;
  markers: MapMarkersResult | null;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
  onRetryMarkers: () => void;
  onZoomIn: () => void;
  onZoomOut: () => void;
};

export function MapOverlayPanels({
  bounds,
  level,
  mapRuntimeError,
  mapRuntimeState,
  markerError,
  markerState,
  markers,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
  onRetryMarkers,
  onZoomIn,
  onZoomOut,
}: MapOverlayPanelsProps) {
  return (
    <>
      <p className="map-status">{mapRuntimeStatusLabel(mapRuntimeState)}</p>
      {mapRuntimeState === 'ready' || markers == null ? null : (
        <FallbackMarkerLayer
          bounds={bounds}
          markers={markers}
          onComplexMarkerSelect={onComplexMarkerSelect}
          onRegionMarkerSelect={onRegionMarkerSelect}
        />
      )}

      <div aria-label="지도 조작" className="map-controls">
        <button type="button" aria-label="지도 확대" onClick={onZoomIn}>
          +
        </button>
        <button type="button" aria-label="지도 축소" onClick={onZoomOut}>
          -
        </button>
      </div>

      {markerState === 'loading' ? (
        <p className="map-feedback" role="status" aria-live="polite">
          마커 불러오는 중
        </p>
      ) : null}

      {markerState === 'empty' ? (
        <p className="map-feedback" role="status" aria-live="polite">
          이 영역에는 마커가 없습니다
        </p>
      ) : null}

      {markerState === 'error' ? (
        <p className="map-feedback map-feedback-error" role="alert">
          마커 데이터를 불러오지 못했습니다. 지도는 계속 사용할 수 있습니다.
          {markerError ? <span className="map-feedback-detail">{markerError}</span> : null}
          {' '}
          <button type="button" aria-label="마커 다시 불러오기" onClick={onRetryMarkers}>
            다시 시도
          </button>
        </p>
      ) : null}

      {mapRuntimeError && markerState !== 'error' ? (
        <p className="map-feedback map-feedback-error" role="alert">
          {mapRuntimeError}
        </p>
      ) : null}

      {markers?.kind === 'complex' && markers.markers.length > 0 ? (
        <ul aria-label="단지 마커" className="marker-preview-list">
          {markers.markers.map((marker) => (
            <li key={complexMarkerKey(marker)}>
              <button
                type="button"
                aria-label={complexMarkerAriaLabel(marker)}
                className="marker-list-button"
                data-marker-id={complexMarkerKey(marker)}
                onClick={() => {
                  onComplexMarkerSelect(marker);
                }}
              >
                <span className="marker-list-price">
                  최근 실거래 {formatMarkerAmount(marker.latestDealAmount)}
                </span>
                {markerSubtitle(marker) ? (
                  <span className="marker-list-subtitle">{markerSubtitle(marker)}</span>
                ) : null}
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {markers?.kind === 'region' && markers.markers.length > 0 ? (
        <ul aria-label="지역 마커" className="marker-preview-list">
          {markers.markers.map((marker) => (
            <li key={marker.id} data-marker-id={marker.id}>
              <button
                type="button"
                aria-label={`지역 이동 ${marker.name}`}
                className="marker-list-button marker-list-button-region"
                onClick={() => {
                  onRegionMarkerSelect(marker);
                }}
              >
                <span className="marker-list-price">{marker.name}</span>
                <span className="marker-list-subtitle">
                  {regionMarkerUnitOrActionLabel(marker, level)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      ) : null}
    </>
  );
}

function FallbackMarkerLayer({
  bounds,
  markers,
  onComplexMarkerSelect,
  onRegionMarkerSelect,
}: {
  bounds: MapBoundsRequest;
  markers: MapMarkersResult;
  onComplexMarkerSelect: (marker: ComplexMapMarker) => void;
  onRegionMarkerSelect: (marker: RegionMapMarker) => void;
}) {
  if (markers.markers.length === 0) {
    return null;
  }

  return (
    <ul aria-label="대체 지도 마커" className="fallback-marker-layer">
      {markers.kind === 'complex'
        ? markers.markers.map((marker) => (
            <li key={complexMarkerKey(marker)} style={mapMarkerPointStyle(marker.lat, marker.lng, bounds)}>
              <button
                type="button"
                aria-label={complexMarkerAriaLabel(marker)}
                className="fallback-map-marker fallback-map-marker-complex"
                data-fallback-marker-id={`complex-${complexMarkerKey(marker)}`}
                onClick={() => {
                  onComplexMarkerSelect(marker);
                }}
              >
                <span className="fallback-map-marker-kicker">
                  {marker.latestDealAmount == null ? '거래 없음' : '최근 실거래'}
                </span>
                <strong>{formatMarkerAmount(marker.latestDealAmount)}</strong>
                {markerSubtitle(marker) ? <span>{markerSubtitle(marker)}</span> : null}
              </button>
            </li>
          ))
        : markers.markers.map((marker) => (
            <li key={marker.id} style={mapMarkerPointStyle(marker.lat, marker.lng, bounds)}>
              <button
                type="button"
                aria-label={`지역 이동 ${marker.name}`}
                className="fallback-map-marker fallback-map-marker-region"
                data-fallback-marker-id={`region-${marker.id}`}
                onClick={() => {
                  onRegionMarkerSelect(marker);
                }}
              >
                <strong>{marker.name}</strong>
                <span>{regionMarkerUnitOrActionLabel(marker)}</span>
              </button>
            </li>
          ))}
    </ul>
  );
}

function formatMarkerAmount(amount: number | null): string {
  if (amount == null) {
    return '최근 거래 없음';
  }

  if (amount >= 10000) {
    const eok = amount / 10000;
    const formatted = Number.isInteger(eok) ? eok.toLocaleString() : eok.toFixed(1);
    return `${formatted}억`;
  }

  return `${amount.toLocaleString()}만`;
}

function markerSubtitle(marker: ComplexMapMarker): string | null {
  if (marker.name) {
    return marker.name;
  }

  if (marker.unitCntSum != null && marker.unitCntSum > 0) {
    return `${marker.unitCntSum.toLocaleString()}세대`;
  }

  return null;
}

function complexMarkerKey(marker: ComplexMapMarker): string {
  return marker.complexId == null
    ? `${marker.parcelId}`
    : `${marker.parcelId}-${marker.complexId}`;
}

function complexMarkerAriaLabel(marker: ComplexMapMarker): string {
  return marker.complexId == null
    ? `필지 ${marker.parcelId} 상세 열기`
    : `필지 ${marker.parcelId} 단지 ${marker.complexId} 상세 열기`;
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

function regionMarkerActionLabel(level: number): string {
  return level <= 4 ? '단지 보기' : '지도 이동';
}

function regionMarkerUnitOrActionLabel(marker: RegionMapMarker, level?: number): string {
  if (marker.unitCntSum != null && marker.unitCntSum > 0) {
    return `${marker.unitCntSum.toLocaleString()}세대`;
  }

  return level == null ? '세대수 없음' : regionMarkerActionLabel(level);
}

function mapRuntimeStatusLabel(state: KakaoMapRuntimeState): string {
  switch (state) {
    case 'loading':
      return '지도 준비 중';
    case 'ready':
      return '지도 준비 완료';
    case 'error':
      return '지도 대체 화면';
  }
}
