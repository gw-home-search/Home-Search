import { useEffect, useState } from 'react';

import {
  fetchMapMarkers,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';

const INITIAL_MARKER_BOUNDS = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

type AppProps = {
  initialMapLevel?: number;
};

export function App({ initialMapLevel = 4 }: AppProps) {
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);

  useEffect(() => {
    let ignore = false;

    setMarkerState('loading');
    setMarkerError(null);

    fetchMapMarkers({
      bounds: INITIAL_MARKER_BOUNDS,
      level: initialMapLevel,
    })
      .then((nextMarkers) => {
        if (ignore) {
          return;
        }

        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.markers.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (ignore) {
          return;
        }

        setMarkers(null);
        setMarkerState('error');
        setMarkerError(error instanceof Error ? error.message : 'Unknown marker error');
      });

    return () => {
      ignore = true;
    };
  }, [initialMapLevel]);

  return (
    <main>
      <h1>Home Search</h1>

      <section aria-label="Map surface">
        <p>Map ready</p>

        {markers?.kind === 'complex' && markers.markers.length > 0 ? (
          <ul aria-label="Complex markers">
            {markers.markers.map((marker) => (
              <li key={marker.parcelId} data-marker-id={marker.parcelId}>
                {formatAmount(marker.latestDealAmount)} - {marker.unitCntSum} units
              </li>
            ))}
          </ul>
        ) : null}

        {markers?.kind === 'region' && markers.markers.length > 0 ? (
          <ul aria-label="Region markers">
            {markers.markers.map((marker) => (
              <li key={marker.id} data-marker-id={marker.id}>
                {marker.name}
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
