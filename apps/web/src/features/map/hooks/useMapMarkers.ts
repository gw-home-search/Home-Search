import { useEffect, useRef, useState } from 'react';

import type { MapViewport, MarkerRequestState } from '../../../app/mapAppTypes';
import {
  fetchMapMarkers,
  type ComplexMarkerFilters,
  type MapMarkersResult,
} from '../api/fetchMapMarkers';
import { countActiveFilterGroups } from '../../filters/FilterPanel';

export const EMPTY_COMPLEX_MARKER_FILTERS: Required<ComplexMarkerFilters> = {
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
};

export function useMapMarkers(viewport: MapViewport) {
  const [markerFilters, setMarkerFilters] = useState<Required<ComplexMarkerFilters>>(
    EMPTY_COMPLEX_MARKER_FILTERS,
  );
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);
  const [markerRetrySeq, setMarkerRetrySeq] = useState(0);
  const markerRequestSeq = useRef(0);
  const markerRequestPending = useRef(true);

  useEffect(() => {
    const requestSeq = markerRequestSeq.current + 1;
    markerRequestSeq.current = requestSeq;
    let ignore = false;

    setMarkerState('loading');
    markerRequestPending.current = true;
    setMarkerError(null);

    fetchMapMarkers({ bounds: viewport.bounds, filters: markerFilters, level: viewport.level })
      .then((nextMarkers) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }
        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.markers.length === 0 ? 'empty' : 'ready');
        markerRequestPending.current = false;
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }
        setMarkers(null);
        setMarkerState('error');
        setMarkerError(error instanceof Error ? error.message : '알 수 없는 마커 오류');
        markerRequestPending.current = false;
      });

    return () => {
      ignore = true;
    };
  }, [markerFilters, markerRetrySeq, viewport]);

  return {
    activeFilterCount: countActiveFilterGroups(markerFilters),
    markerError,
    markerFilters,
    markers,
    markerState,
    resetMarkerFilters: () => setMarkerFilters(EMPTY_COMPLEX_MARKER_FILTERS),
    retryMarkers: () => {
      if (markerRequestPending.current) return;
      markerRequestPending.current = true;
      setMarkerState('loading');
      setMarkerRetrySeq((current) => current + 1);
    },
    setMarkerFilters,
  };
}
