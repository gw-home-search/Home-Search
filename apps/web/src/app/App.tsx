import { useCallback, useEffect, useRef, useState } from 'react';

import {
  fetchComplexDetail,
  type ComplexDetail,
} from '../features/complex-detail/api/fetchComplexDetail';
import {
  fetchParcelTrades,
  type ParcelTrades,
  type TradeItem,
} from '../features/complex-detail/api/fetchParcelTrades';
import {
  fetchMapMarkers,
  type MapBoundsRequest,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';
import { KakaoMapSurface } from '../features/map/KakaoMapSurface';
import './App.css';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';

const INITIAL_MARKER_BOUNDS: MapBoundsRequest = {
  swLat: 37.45,
  swLng: 126.85,
  neLat: 37.7,
  neLng: 127.2,
};

type MapViewport = {
  bounds: MapBoundsRequest;
  level: number;
};

type AppProps = {
  initialMapLevel?: number;
  kakaoMapAppKey?: string;
};

export function App({
  initialMapLevel = 4,
  kakaoMapAppKey = getConfiguredKakaoMapAppKey(),
}: AppProps) {
  const [viewport, setViewport] = useState<MapViewport>(() => ({
    bounds: INITIAL_MARKER_BOUNDS,
    level: initialMapLevel,
  }));
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);
  const [mapRuntimeError, setMapRuntimeError] = useState<string | null>(null);
  const [markerRetrySeq, setMarkerRetrySeq] = useState(0);
  const [selectedParcelId, setSelectedParcelId] = useState<number | null>(null);
  const [complexDetail, setComplexDetail] = useState<ComplexDetail | null>(null);
  const [parcelTrades, setParcelTrades] = useState<ParcelTrades | null>(null);
  const [detailState, setDetailState] = useState<DetailRequestState>('idle');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailRetrySeq, setDetailRetrySeq] = useState(0);
  const markerRequestSeq = useRef(0);
  const detailRequestSeq = useRef(0);

  useEffect(() => {
    setViewport((current) => {
      if (current.level === initialMapLevel) {
        return current;
      }

      return { ...current, level: initialMapLevel };
    });
  }, [initialMapLevel]);

  useEffect(() => {
    const requestSeq = markerRequestSeq.current + 1;
    markerRequestSeq.current = requestSeq;
    let ignore = false;

    setMarkerState('loading');
    setMarkerError(null);

    fetchMapMarkers({
      bounds: viewport.bounds,
      level: viewport.level,
    })
      .then((nextMarkers) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }

        setMarkers(nextMarkers);
        setMarkerState(nextMarkers.markers.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== markerRequestSeq.current) {
          return;
        }

        setMarkers(null);
        setMarkerState('error');
        setMarkerError(error instanceof Error ? error.message : 'Unknown marker error');
      });

    return () => {
      ignore = true;
    };
  }, [viewport, markerRetrySeq]);

  useEffect(() => {
    if (selectedParcelId == null) {
      setComplexDetail(null);
      setParcelTrades(null);
      setDetailState('idle');
      setDetailError(null);
      return undefined;
    }

    const requestSeq = detailRequestSeq.current + 1;
    detailRequestSeq.current = requestSeq;
    let ignore = false;

    setDetailState('loading');
    setDetailError(null);

    Promise.all([fetchComplexDetail(selectedParcelId), fetchParcelTrades(selectedParcelId)])
      .then(([nextDetail, nextTrades]) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }

        setComplexDetail(nextDetail);
        setParcelTrades(nextTrades);
        setDetailState('ready');
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }

        setComplexDetail(null);
        setParcelTrades(null);
        setDetailState('error');
        setDetailError(error instanceof Error ? error.message : 'Unknown detail error');
      });

    return () => {
      ignore = true;
    };
  }, [selectedParcelId, detailRetrySeq]);

  const handleViewportChange = useCallback((nextViewport: MapViewport) => {
    setViewport((current) => {
      if (sameViewport(current, nextViewport)) {
        return current;
      }

      return nextViewport;
    });
  }, []);

  function handleZoomIn() {
    setViewport((current) => ({
      ...current,
      level: Math.max(1, current.level - 1),
    }));
  }

  function handleZoomOut() {
    setViewport((current) => ({
      ...current,
      level: current.level + 1,
    }));
  }

  function handleRetryMarkers() {
    setMarkerRetrySeq((current) => current + 1);
  }

  const handleComplexMarkerSelect = useCallback((parcelId: number) => {
    setSelectedParcelId(parcelId);
  }, []);

  function handleCloseDetailDrawer() {
    setSelectedParcelId(null);
  }

  function handleRetryDetail() {
    setDetailRetrySeq((current) => current + 1);
  }

  return (
    <main className="app-shell">
      <h1>Home Search</h1>

      <section aria-label="Map surface" className="map-surface">
        <p className="map-status">Map ready</p>
        <KakaoMapSurface
          appKey={kakaoMapAppKey}
          initialLevel={initialMapLevel}
          markers={markers}
          onComplexMarkerSelect={handleComplexMarkerSelect}
          onRuntimeErrorChange={setMapRuntimeError}
          onViewportChange={handleViewportChange}
        />
        <div aria-label="Map controls" className="map-controls">
          <button type="button" aria-label="Zoom in" onClick={handleZoomIn}>
            +
          </button>
          <button type="button" aria-label="Zoom out" onClick={handleZoomOut}>
            -
          </button>
        </div>

        {markers?.kind === 'complex' && markers.markers.length > 0 ? (
          <ul aria-label="Complex markers">
            {markers.markers.map((marker) => (
              <li key={marker.parcelId}>
                <button
                  type="button"
                  aria-label={`Open detail for parcel ${marker.parcelId}`}
                  className="marker-list-button"
                  data-marker-id={marker.parcelId}
                  onClick={() => {
                    handleComplexMarkerSelect(marker.parcelId);
                  }}
                >
                  {formatAmount(marker.latestDealAmount)} - {marker.unitCntSum} units
                </button>
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
          {' '}
          <button type="button" aria-label="Retry marker load" onClick={handleRetryMarkers}>
            Retry
          </button>
        </p>
      ) : null}

      {mapRuntimeError && markerState !== 'error' ? <p role="alert">{mapRuntimeError}</p> : null}

      {selectedParcelId == null ? null : (
        <aside aria-label="Complex detail drawer" className="detail-drawer">
          <div className="detail-drawer-header">
            <p className="detail-drawer-kicker">Parcel {selectedParcelId}</p>
            <button
              type="button"
              aria-label="Close detail drawer"
              className="detail-drawer-close"
              onClick={handleCloseDetailDrawer}
            >
              Close
            </button>
          </div>

          {detailState === 'loading' ? (
            <p role="status" aria-live="polite">
              Loading detail
            </p>
          ) : null}

          {detailState === 'error' ? (
            <p role="alert">
              Detail data unavailable.
              {detailError ? ` ${detailError}` : null}
              {' '}
              <button type="button" aria-label="Retry detail load" onClick={handleRetryDetail}>
                Retry
              </button>
            </p>
          ) : null}

          {detailState === 'ready' && complexDetail ? (
            <>
              <h2>{complexDetail.name}</h2>
              <p className="detail-address">{complexDetail.address}</p>
              <dl className="detail-metrics">
                {detailMetric('Trade name', complexDetail.tradeName)}
                {detailMetric('Households', formatNumber(complexDetail.unitCnt, ' units'))}
                {detailMetric('Buildings', formatNumber(complexDetail.dongCnt, ' buildings'))}
                {detailMetric('Use date', complexDetail.useDate)}
                {detailMetric('Platform area', formatNumber(complexDetail.platArea, ' sqm'))}
                {detailMetric('Architecture area', formatNumber(complexDetail.archArea, ' sqm'))}
                {detailMetric('Total area', formatNumber(complexDetail.totArea, ' sqm'))}
                {detailMetric('Building coverage', formatNumber(complexDetail.bcRat, '%'))}
                {detailMetric('Floor area ratio', formatNumber(complexDetail.vlRat, '%'))}
              </dl>
              <TradeList trades={parcelTrades?.trades ?? []} />
            </>
          ) : null}
        </aside>
      )}
    </main>
  );
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return 'No recent trade';
  }

  return `${amount.toLocaleString()} 10k KRW`;
}

function detailMetric(label: string, value: string | null) {
  if (value == null) {
    return null;
  }

  return (
    <div className="detail-metric" key={label}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function formatNumber(value: number | null, suffix: string): string | null {
  if (value == null) {
    return null;
  }

  return `${value.toLocaleString()}${suffix}`;
}

function TradeList({ trades }: { trades: TradeItem[] }) {
  return (
    <section className="trade-list" aria-label="Trade list">
      <h3>Trades</h3>
      {trades.length === 0 ? (
        <p>No trades yet</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Amount</th>
              <th scope="col">Area</th>
              <th scope="col">Floor</th>
            </tr>
          </thead>
          <tbody>
            {trades.map((trade) => (
              <tr key={trade.tradeId}>
                <td>{trade.dealDate}</td>
                <td>{formatAmount(trade.dealAmount)}</td>
                <td>{trade.exclArea.toLocaleString()} sqm</td>
                <td>{formatTradeFloor(trade)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function formatTradeFloor(trade: TradeItem): string {
  const floor = trade.floor == null ? 'Unknown floor' : `${trade.floor}F`;
  return trade.aptDong == null ? floor : `${trade.aptDong} / ${floor}`;
}

function sameViewport(first: MapViewport, second: MapViewport): boolean {
  return (
    first.level === second.level &&
    first.bounds.swLat === second.bounds.swLat &&
    first.bounds.swLng === second.bounds.swLng &&
    first.bounds.neLat === second.bounds.neLat &&
    first.bounds.neLng === second.bounds.neLng
  );
}

function getConfiguredKakaoMapAppKey(): string {
  return import.meta.env.VITE_KAKAO_MAP_APP_KEY ?? '';
}
