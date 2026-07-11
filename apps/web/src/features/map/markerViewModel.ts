import type { ComplexSelection } from '../../app/mapAppTypes';
import type { MapMarkersResult } from './api/fetchMapMarkers';

export type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
export type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];

export type ComplexMarkerViewModel = {
  kind: 'complex';
  shape: 'price-card';
  semanticClass: 'complex';
  key: string;
  ariaLabel: string;
  kicker: string;
  price: string;
  meta: string | null;
  symbol: 'price-card';
  selected: boolean;
  state: 'idle' | 'selected';
};

export type RegionMarkerViewModel = {
  kind: 'region';
  shape: 'split-card';
  semanticClass: 'region';
  key: string;
  ariaLabel: string;
  name: string;
  meta: string;
};

export function createComplexMarkerViewModel(
  marker: ComplexMapMarker,
  selected: boolean,
): ComplexMarkerViewModel {
  return {
    kind: 'complex',
    shape: 'price-card',
    semanticClass: 'complex',
    key: marker.complexId == null ? `${marker.parcelId}` : `${marker.parcelId}-${marker.complexId}`,
    ariaLabel: marker.complexId == null
      ? `필지 ${marker.parcelId} 상세 열기`
      : `필지 ${marker.parcelId} 단지 ${marker.complexId} 상세 열기`,
    kicker: marker.latestDealAmount == null ? '거래 없음' : '최근 실거래',
    price: formatMarkerAmount(marker.latestDealAmount),
    meta: marker.name ?? positiveUnitLabel(marker.unitCntSum) ?? '단지 정보 없음',
    symbol: 'price-card',
    selected,
    state: selected ? 'selected' : 'idle',
  };
}

export function createRegionMarkerViewModel(marker: RegionMapMarker): RegionMarkerViewModel {
  return {
    kind: 'region',
    shape: 'split-card',
    semanticClass: 'region',
    key: `${marker.id}`,
    ariaLabel: `지역 이동 ${marker.name}`,
    name: marker.name,
    meta: positiveUnitLabel(marker.unitCntSum) ?? '세대수 없음',
  };
}

export function isComplexMarkerSelected(
  marker: ComplexMapMarker,
  selection: ComplexSelection | null,
): boolean {
  if (selection == null) return false;
  if (marker.complexId != null && selection.complexId != null) {
    return marker.complexId === selection.complexId;
  }
  return marker.parcelId === selection.parcelId;
}

export function declutterComplexMarkers(
  markers: ComplexMapMarker[],
  selection: ComplexSelection | null,
  level: number,
): { markers: ComplexMapMarker[]; hiddenCount: number } {
  const cellSize = level <= 2 ? 0.00008 : level === 3 ? 0.00018 : 0.00035;
  const cells = new Map<string, ComplexMapMarker>();
  for (const marker of markers) {
    const key = `${Math.round(marker.lat / cellSize)}:${Math.round(marker.lng / cellSize)}`;
    const current = cells.get(key);
    if (current == null || isComplexMarkerSelected(marker, selection)) cells.set(key, marker);
  }
  const visible = Array.from(cells.values());
  return { markers: visible, hiddenCount: markers.length - visible.length };
}

function positiveUnitLabel(unitCntSum: number | null): string | null {
  return unitCntSum != null && unitCntSum > 0
    ? `${unitCntSum.toLocaleString()}세대`
    : null;
}

function formatMarkerAmount(amount: number | null): string {
  if (amount == null) return '최근 거래 없음';
  if (amount >= 10000) {
    const eok = amount / 10000;
    return `${Number.isInteger(eok) ? eok.toLocaleString() : eok.toFixed(1)}억`;
  }
  return `${amount.toLocaleString()}만`;
}
