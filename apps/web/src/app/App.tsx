import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';

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
  type ComplexMarkerFilters,
  type MapBoundsRequest,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';
import {
  KakaoMapSurface,
  type KakaoMapRuntimeState,
} from '../features/map/KakaoMapSurface';
import {
  fetchRegionDetail,
  fetchRootRegions,
  type RegionDetail,
  type RegionSummary,
} from '../features/region/api/fetchRegions';
import {
  fetchComplexSearchResults,
  type ComplexSearchResult,
} from '../features/search/api/fetchComplexSearchResults';
import './App.css';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';
type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

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

type MapFocusTarget = {
  lat: number;
  lng: number;
  level: number;
  seq: number;
};

type AppProps = {
  initialMapLevel?: number;
  kakaoMapAppKey?: string;
};

const EMPTY_COMPLEX_MARKER_FILTERS: Required<ComplexMarkerFilters> = {
  pyeongMin: null,
  pyeongMax: null,
  priceEokMin: null,
  priceEokMax: null,
  ageMin: null,
  ageMax: null,
  unitMin: null,
  unitMax: null,
};

const SEARCH_FOCUS_DELTA = 0.01;
const REGION_FOCUS_DELTA = 0.05;

export function App({
  initialMapLevel = 4,
  kakaoMapAppKey = getConfiguredKakaoMapAppKey(),
}: AppProps) {
  const [viewport, setViewport] = useState<MapViewport>(() => ({
    bounds: INITIAL_MARKER_BOUNDS,
    level: initialMapLevel,
  }));
  const [markerFilters, setMarkerFilters] = useState<ComplexMarkerFilters>(
    EMPTY_COMPLEX_MARKER_FILTERS,
  );
  const [markers, setMarkers] = useState<MapMarkersResult | null>(null);
  const [markerState, setMarkerState] = useState<MarkerRequestState>('loading');
  const [markerError, setMarkerError] = useState<string | null>(null);
  const [mapRuntimeState, setMapRuntimeState] = useState<KakaoMapRuntimeState>('loading');
  const [mapRuntimeError, setMapRuntimeError] = useState<string | null>(null);
  const [mapFocusTarget, setMapFocusTarget] = useState<MapFocusTarget | null>(null);
  const [markerRetrySeq, setMarkerRetrySeq] = useState(0);
  const [selectedParcelId, setSelectedParcelId] = useState<number | null>(null);
  const [complexDetail, setComplexDetail] = useState<ComplexDetail | null>(null);
  const [parcelTrades, setParcelTrades] = useState<ParcelTrades | null>(null);
  const [detailState, setDetailState] = useState<DetailRequestState>('idle');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailRetrySeq, setDetailRetrySeq] = useState(0);
  const [searchResults, setSearchResults] = useState<ComplexSearchResult[]>([]);
  const [searchState, setSearchState] = useState<PanelRequestState>('idle');
  const [searchError, setSearchError] = useState<string | null>(null);
  const [rootRegions, setRootRegions] = useState<RegionSummary[]>([]);
  const [regionDetail, setRegionDetail] = useState<RegionDetail | null>(null);
  const [regionState, setRegionState] = useState<PanelRequestState>('idle');
  const [regionError, setRegionError] = useState<string | null>(null);
  const [isExplorationOpen, setIsExplorationOpen] = useState(true);
  const markerRequestSeq = useRef(0);
  const detailRequestSeq = useRef(0);
  const searchRequestSeq = useRef(0);
  const regionRequestSeq = useRef(0);

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
      filters: markerFilters,
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
  }, [markerFilters, markerRetrySeq, viewport]);

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

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const query = stringFormValue(new FormData(event.currentTarget), 'q').trim();
    const requestSeq = searchRequestSeq.current + 1;
    searchRequestSeq.current = requestSeq;
    setSearchError(null);

    if (query.length === 0) {
      setSearchResults([]);
      setSearchState('idle');
      return;
    }

    setSearchState('loading');
    fetchComplexSearchResults(query)
      .then((nextResults) => {
        if (requestSeq !== searchRequestSeq.current) {
          return;
        }

        setSearchResults(nextResults);
        setSearchState(nextResults.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (requestSeq !== searchRequestSeq.current) {
          return;
        }

        setSearchResults([]);
        setSearchState('error');
        setSearchError(error instanceof Error ? error.message : 'Unknown search error');
      });
  }

  function handleSearchResultSelect(result: ComplexSearchResult) {
    setSelectedParcelId(result.parcelId);
    focusMap(result.latitude, result.longitude, 4, SEARCH_FOCUS_DELTA);
  }

  function handleFilterSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formData = new FormData(event.currentTarget);

    setMarkerFilters({
      pyeongMin: numberFormValue(formData, 'pyeongMin'),
      pyeongMax: numberFormValue(formData, 'pyeongMax'),
      priceEokMin: numberFormValue(formData, 'priceEokMin'),
      priceEokMax: numberFormValue(formData, 'priceEokMax'),
      ageMin: numberFormValue(formData, 'ageMin'),
      ageMax: numberFormValue(formData, 'ageMax'),
      unitMin: numberFormValue(formData, 'unitMin'),
      unitMax: numberFormValue(formData, 'unitMax'),
    });
  }

  function handleLoadRootRegions() {
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;

    setRegionState('loading');
    setRegionError(null);

    fetchRootRegions()
      .then((nextRegions) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRootRegions(nextRegions);
        setRegionState(nextRegions.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRootRegions([]);
        setRegionDetail(null);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : 'Unknown region error');
      });
  }

  function handleRegionSelect(regionId: number) {
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;

    setRegionState('loading');
    setRegionError(null);

    fetchRegionDetail(regionId)
      .then((nextDetail) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRegionDetail(nextDetail);
        setRootRegions(nextDetail.children);
        setRegionState('ready');
        focusMap(nextDetail.latitude, nextDetail.longitude, 7, REGION_FOCUS_DELTA);
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRegionDetail(null);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : 'Unknown region detail error');
      });
  }

  function focusMap(lat: number, lng: number, level: number, delta: number) {
    setViewport(viewportAroundPoint(lat, lng, level, delta));
    setMapFocusTarget((current) => ({
      lat,
      lng,
      level,
      seq: (current?.seq ?? 0) + 1,
    }));
  }

  return (
    <main className="app-shell">
      <header aria-label="Application bar" className="app-bar">
        <div className="app-brand">
          <h1>Home Search</h1>
          <span>V1 map</span>
        </div>
        <button
          type="button"
          aria-controls="exploration-panel"
          aria-expanded={isExplorationOpen}
          aria-label="Toggle exploration panel"
          className="exploration-toggle"
          onClick={() => {
            setIsExplorationOpen((current) => !current);
          }}
        >
          Explore
        </button>
      </header>

      <div className="map-workspace" data-layout-region="map-workspace">
        <section aria-label="Map surface" className="map-surface">
          <p className="map-status">{mapRuntimeStatusLabel(mapRuntimeState)}</p>
          <KakaoMapSurface
            appKey={kakaoMapAppKey}
            focusTarget={mapFocusTarget}
            initialLevel={initialMapLevel}
            markers={markers}
            onComplexMarkerSelect={handleComplexMarkerSelect}
            onRuntimeErrorChange={setMapRuntimeError}
            onRuntimeStateChange={setMapRuntimeState}
            onViewportChange={handleViewportChange}
          />

          <form
            aria-label="Marker filters"
            className="filter-panel"
            data-map-overlay="filters"
            onSubmit={handleFilterSubmit}
          >
            <input
              aria-label="Minimum pyeong"
              name="pyeongMin"
              placeholder="Pyeong min"
              type="number"
            />
            <input
              aria-label="Maximum pyeong"
              name="pyeongMax"
              placeholder="Pyeong max"
              type="number"
            />
            <input
              aria-label="Minimum price eok"
              name="priceEokMin"
              placeholder="Price min"
              step="0.1"
              type="number"
            />
            <input
              aria-label="Maximum price eok"
              name="priceEokMax"
              placeholder="Price max"
              step="0.1"
              type="number"
            />
            <input
              aria-label="Minimum building age"
              name="ageMin"
              placeholder="Age min"
              type="number"
            />
            <input
              aria-label="Maximum building age"
              name="ageMax"
              placeholder="Age max"
              type="number"
            />
            <input
              aria-label="Minimum unit count"
              name="unitMin"
              placeholder="Units min"
              type="number"
            />
            <input
              aria-label="Maximum unit count"
              name="unitMax"
              placeholder="Units max"
              type="number"
            />
            <button type="submit" aria-label="Apply marker filters">
              Apply
            </button>
          </form>

          <div aria-label="Map controls" className="map-controls">
            <button type="button" aria-label="Zoom in" onClick={handleZoomIn}>
              +
            </button>
            <button type="button" aria-label="Zoom out" onClick={handleZoomOut}>
              -
            </button>
          </div>

          {markerState === 'loading' ? (
            <p className="map-feedback" role="status" aria-live="polite">
              Loading markers
            </p>
          ) : null}

          {markerState === 'empty' ? (
            <p className="map-feedback" role="status" aria-live="polite">
              No markers in this area
            </p>
          ) : null}

          {markerState === 'error' ? (
            <p className="map-feedback map-feedback-error" role="alert">
              Marker data unavailable. Map remains usable.
              {markerError ? ` ${markerError}` : null}
              {' '}
              <button type="button" aria-label="Retry marker load" onClick={handleRetryMarkers}>
                Retry
              </button>
            </p>
          ) : null}

          {mapRuntimeError && markerState !== 'error' ? (
            <p className="map-feedback map-feedback-error" role="alert">
              {mapRuntimeError}
            </p>
          ) : null}

          {markers?.kind === 'complex' && markers.markers.length > 0 ? (
            <ul aria-label="Complex markers" className="marker-preview-list">
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
            <ul aria-label="Region markers" className="marker-preview-list">
              {markers.markers.map((marker) => (
                <li key={marker.id} data-marker-id={marker.id}>
                  {marker.name}
                </li>
              ))}
            </ul>
          ) : null}
        </section>

        <section
          id="exploration-panel"
          aria-label="Exploration panel"
          aria-hidden={!isExplorationOpen}
          className="exploration-panel"
          data-collapsed={isExplorationOpen ? 'false' : 'true'}
          hidden={!isExplorationOpen}
        >
          <form aria-label="Complex search" className="search-panel" onSubmit={handleSearchSubmit}>
            <input
              aria-label="Search complexes"
              name="q"
              placeholder="Complex name"
              type="search"
            />
            <button type="submit" aria-label="Run complex search">
              Search
            </button>
          </form>

          {searchState === 'loading' ? (
            <p role="status" aria-live="polite">
              Searching complexes
            </p>
          ) : null}

          {searchState === 'empty' ? (
            <p role="status" aria-live="polite">
              No search results
            </p>
          ) : null}

          {searchState === 'error' ? (
            <p role="alert">
              Search unavailable.
              {searchError ? ` ${searchError}` : null}
            </p>
          ) : null}

          {searchResults.length > 0 ? (
            <ul aria-label="Search results" className="panel-list">
              {searchResults.map((result) => (
                <li key={result.complexId}>
                  <button
                    type="button"
                    aria-label={`Select search result ${result.complexName}`}
                    onClick={() => {
                      handleSearchResultSelect(result);
                    }}
                  >
                    <span>{result.complexName}</span>
                    <span>{result.address}</span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}

          <div className="region-panel">
            <button type="button" aria-label="Load root regions" onClick={handleLoadRootRegions}>
              Regions
            </button>

            {regionState === 'loading' ? (
              <p role="status" aria-live="polite">
                Loading regions
              </p>
            ) : null}

            {regionState === 'empty' ? (
              <p role="status" aria-live="polite">
                No regions
              </p>
            ) : null}

            {regionState === 'error' ? (
              <p role="alert">
                Region navigation unavailable.
                {regionError ? ` ${regionError}` : null}
              </p>
            ) : null}

            {regionDetail ? <p className="selected-region">{regionDetail.name}</p> : null}

            {rootRegions.length > 0 ? (
              <ul aria-label="Region navigation" className="panel-list">
                {rootRegions.map((region) => (
                  <li key={region.id}>
                    <button
                      type="button"
                      aria-label={`Open region ${region.name}`}
                      onClick={() => {
                        handleRegionSelect(region.id);
                      }}
                    >
                      {region.name}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </div>
        </section>
      </div>

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
              <TradeAmountChart trades={parcelTrades?.trades ?? []} />
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

function viewportAroundPoint(lat: number, lng: number, level: number, delta: number): MapViewport {
  return {
    bounds: {
      swLat: lat - delta,
      swLng: lng - delta,
      neLat: lat + delta,
      neLng: lng + delta,
    },
    level,
  };
}

function stringFormValue(formData: FormData, field: string): string {
  const value = formData.get(field);
  return typeof value === 'string' ? value : '';
}

function numberFormValue(formData: FormData, field: string): number | null {
  const value = stringFormValue(formData, field).trim();
  if (value.length === 0) {
    return null;
  }

  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
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

function TradeAmountChart({ trades }: { trades: TradeItem[] }) {
  const points = tradeChartPoints(trades);

  return (
    <section className="trade-chart" aria-label="Trade amount chart">
      <div className="trade-section-header">
        <h3>Trade amount trend</h3>
        {points.length > 0 ? <p>Recent {points.length}</p> : null}
      </div>

      {points.length === 0 ? (
        <p className="trade-chart-empty">No trade amounts to chart</p>
      ) : (
        <>
          <div
            className="trade-chart-plot"
            role="img"
            aria-label={`Recent trade amounts from ${points[0]?.dealDate} to ${
              points[points.length - 1]?.dealDate
            }`}
          >
            {points.map((point) => (
              <div
                key={`${point.tradeId}-${point.dealDate}`}
                className="trade-chart-point"
                data-chart-point=""
                data-chart-date={point.dealDate}
              >
                <span
                  className="trade-chart-bar"
                  aria-label={`${point.dealDate} ${formatAmount(point.dealAmount)}`}
                  style={{ height: `${point.heightPercent}%` }}
                />
                <span className="trade-chart-date">{point.shortDate}</span>
              </div>
            ))}
          </div>
          <ol className="trade-chart-summary">
            {points.map((point) => (
              <li key={`${point.tradeId}-${point.dealDate}-summary`}>
                <span>{point.dealDate}</span>
                <strong>{formatAmount(point.dealAmount)}</strong>
              </li>
            ))}
          </ol>
        </>
      )}
    </section>
  );
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

type TradeChartPoint = {
  tradeId: number;
  dealDate: string;
  dealAmount: number;
  shortDate: string;
  heightPercent: number;
};

function tradeChartPoints(trades: TradeItem[]): TradeChartPoint[] {
  const recentTrades = trades
    .filter((trade) => Number.isFinite(trade.dealAmount))
    .slice()
    .sort(compareTradesNewestFirst)
    .slice(0, 6)
    .sort(compareTradesOldestFirst);

  if (recentTrades.length === 0) {
    return [];
  }

  const amounts = recentTrades.map((trade) => trade.dealAmount);
  const minAmount = Math.min(...amounts);
  const maxAmount = Math.max(...amounts);
  const range = maxAmount - minAmount;

  return recentTrades.map((trade) => ({
    tradeId: trade.tradeId,
    dealDate: trade.dealDate,
    dealAmount: trade.dealAmount,
    shortDate: shortTradeDate(trade.dealDate),
    heightPercent: range === 0 ? 54 : 32 + ((trade.dealAmount - minAmount) / range) * 58,
  }));
}

function compareTradesNewestFirst(first: TradeItem, second: TradeItem): number {
  return second.dealDate.localeCompare(first.dealDate) || second.tradeId - first.tradeId;
}

function compareTradesOldestFirst(first: TradeItem, second: TradeItem): number {
  return first.dealDate.localeCompare(second.dealDate) || first.tradeId - second.tradeId;
}

function shortTradeDate(dealDate: string): string {
  const [, month, day] = dealDate.split('-');
  return month && day ? `${month}/${day}` : dealDate;
}

function formatTradeFloor(trade: TradeItem): string {
  const floor = trade.floor == null ? 'Unknown floor' : `${trade.floor}F`;
  return trade.aptDong == null ? floor : `${trade.aptDong} / ${floor}`;
}

function mapRuntimeStatusLabel(state: KakaoMapRuntimeState): string {
  switch (state) {
    case 'loading':
      return 'Loading map runtime';
    case 'ready':
      return 'Map ready';
    case 'error':
      return 'Map fallback active';
  }
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
