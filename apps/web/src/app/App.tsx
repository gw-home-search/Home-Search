import { useEffect, useState } from 'react';

import {
  fetchComplexMarkers,
  type ComplexMarker,
  type ComplexMarkersRequest,
} from '../features/map/api/fetchComplexMarkers';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';

const INITIAL_MARKER_REQUEST: ComplexMarkersRequest = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
};

export function App() {
  const [markers, setMarkers] = useState<ComplexMarker[]>([]);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;

    setMarkerState('loading');
    setMarkerError(null);

    fetchComplexMarkers(INITIAL_MARKER_REQUEST)
      .then((nextMarkers) => {
        if (ignore) {
          return;
        }

        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (ignore) {
          return;
        }

        setMarkers([]);
        setMarkerState('error');
        setMarkerError(error instanceof Error ? error.message : 'Unknown marker error');
      });

    return () => {
      ignore = true;
    };
  }, []);

  return (
    <main>
      <h1>Home Search</h1>

      <section aria-label="Map surface">
        <p>Map ready</p>

        {markers.length > 0 ? (
          <ul aria-label="Complex markers">
            {markers.map((marker) => (
              <li key={marker.parcelId} data-marker-id={marker.parcelId}>
                {formatAmount(marker.latestDealAmount)} - {marker.unitCntSum} units
              </li>
            ))}
          </ul>
        ) : null}
      </section>

      {markerState === 'loading' ? (
        <p role="status" aria-live="polite">
          Loading markers
        </p>
      ) : null}

      {markerState === 'empty' ? (
        <p role="status" aria-live="polite">
          No markers in this area
        </p>
      ) : null}

      {markerState === 'error' ? (
        <p role="alert">
          Marker data unavailable. Map remains usable.
          {markerError ? ` ${markerError}` : null}
        </p>
      ) : null}
    </main>
  );
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return 'No recent trade';
  }

  return `${amount.toLocaleString()} 10k KRW`;
}
