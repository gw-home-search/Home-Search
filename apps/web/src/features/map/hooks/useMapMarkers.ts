import { useEffect, useRef, useState } from 'react';

import type { MapViewport, MarkerRequestState } from '../../../app/mapAppTypes';
import {
  fetchMapMarkers,
  type ComplexMarkerFilters,
  type MapMarkersResult,
} from '../api/fetchMapMarkers';
import { countActiveFilterGroups } from '../../filters/FilterPanel';
import {
  isCancelledFailure,
  toRequestFailure,
  type RequestFailure,
} from '../../../shared/http/requestFailure';

export const EMPTY_COMPLEX_MARKER_FILTERS: Required<ComplexMarkerFilters> = {
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
  bcRatMin: null,
  bcRatMax: null,
  vlRatMin: null,
  vlRatMax: null,
};

export function useMapMarkers(viewport: MapViewport) {
  const [markerFilters, setMarkerFilters] = useState<Required<ComplexMarkerFilters>>(
    EMPTY_COMPLEX_MARKER_FILTERS,
  );
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<RequestFailure | null>(null);
  const [markerRetrySeq, setMarkerRetrySeq] = useState(0);
  const markerRequestSeq = useRef(0);
  const markerRequestPending = useRef(true);

  useEffect(() => {
    const requestSeq = markerRequestSeq.current + 1;
    markerRequestSeq.current = requestSeq;
    const controller = new AbortController();
    let ignore = false;

    setMarkerState('loading');
    markerRequestPending.current = true;
    setMarkerError(null);

    fetchMapMarkers(
      { bounds: viewport.bounds, filters: markerFilters, level: viewport.level },
      controller.signal,
    )
      .then((nextMarkers) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }
        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.markers.length === 0 ? 'empty' : 'ready');
        markerRequestPending.current = false;
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }
        const failure = toRequestFailure(error, {
          service: 'property-data',
          operation: 'map-markers',
        }, controller.signal);
        if (isCancelledFailure(failure)) return;
        setMarkerState('error');
        setMarkerError(failure);
        markerRequestPending.current = false;
      });

    return () => {
      ignore = true;
      controller.abort();
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
