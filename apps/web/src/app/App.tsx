import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
} from 'react';

import {
  fetchComplexDetail,
  fetchComplexDetailByComplexId,
  type ComplexDetail,
} from '../features/complex-detail/api/fetchComplexDetail';
import {
  fetchParcelComplexes,
  type ParcelComplexSummary,
} from '../features/complex-detail/api/fetchParcelComplexes';
import {
  fetchComplexTrades,
  fetchParcelTrades,
  type ParcelTrades,
  type TradeItem,
} from '../features/complex-detail/api/fetchParcelTrades';
import {
  fetchComplexTradeTrend,
  fetchParcelTradeTrend,
  type TradeTrendPoint,
} from '../features/complex-detail/api/fetchTradeTrend';
import {
  CoordinateOverrideAdminPage,
  CoordinateReasonGuidePage,
} from '../features/admin/CoordinateOverrideAdminPage';
import { MetadataAdminPage } from '../features/admin/MetadataAdminPage';
import {
  fetchMapMarkers,
  type ComplexMarkerFilters,
  type MapBoundsRequest,
  type MapMarkersResult,
} from '../features/map/api/fetchMapMarkers';
import { FilterPanel } from '../features/filters/FilterPanel';
import { ExplorationPanel } from '../features/exploration/ExplorationPanel';
import {
  KakaoMapSurface,
  type KakaoMapRuntimeState,
} from '../features/map/KakaoMapSurface';
import { MapOverlayPanels } from '../features/map/MapOverlayPanels';
import {
  fetchRegionComplexes,
  fetchRegionDetail,
  fetchRootRegions,
  type RegionComplexSummary,
  type RegionDetail,
  type RegionSummary,
} from '../features/region/api/fetchRegions';
import {
  fetchComplexSuggestions,
  type ComplexSuggestion,
} from '../features/search/api/fetchComplexSuggestions';
import {
  fetchComplexSearchResults,
  type ComplexSearchResult,
} from '../features/search/api/fetchComplexSearchResults';
import './App.css';

type MarkerRequestState = 'loading' | 'ready' | 'empty' | 'error';
type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';
type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';
type SidebarMode = 'region' | 'search' | 'detail';

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

type ComplexSelection = {
  parcelId: number | null;
  complexId: number | null;
};

type ComplexMapMarker = Extract<MapMarkersResult, { kind: 'complex' }>['markers'][number];
type RegionMapMarker = Extract<MapMarkersResult, { kind: 'region' }>['markers'][number];

type RegionTrailItem = {
  id: number;
  name: string;
};

type AppProps = {
  initialMapLevel?: number;
  initialRegionLoad?: boolean;
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
const SEARCH_DEBOUNCE_MILLIS = 300;
const TRADE_PAGE_SIZE = 25;

export function App({
  initialMapLevel,
  initialRegionLoad = true,
  kakaoMapAppKey,
}: AppProps) {
  if ((isCoordinateAdminPath() || isMetadataAdminPath()) && !isAdminSurfaceEnabled()) {
    return <NotFoundPage />;
  }

  if (isCoordinateReasonGuidePath()) {
    return <CoordinateReasonGuidePage />;
  }

  if (isCoordinateAdminPath()) {
    return <CoordinateOverrideAdminPage />;
  }

  if (isMetadataAdminPath()) {
    return <MetadataAdminPage />;
  }

  return (
    <MapApp
      initialMapLevel={initialMapLevel}
      initialRegionLoad={initialRegionLoad}
      kakaoMapAppKey={kakaoMapAppKey}
    />
  );
}

function NotFoundPage() {
  return (
    <main className="admin-shell">
      <header className="admin-header">
        <div>
          <h1>페이지를 찾을 수 없습니다</h1>
          <p>요청한 주소가 없거나 현재 화면에서 사용할 수 없습니다.</p>
        </div>
        <nav className="admin-header-actions" aria-label="페이지 이동">
          <a href="/" aria-label="지도로 돌아가기">지도로 돌아가기</a>
        </nav>
      </header>
    </main>
  );
}

function MapApp({
  initialMapLevel = 10,
  initialRegionLoad = true,
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
  const [selectedComplex, setSelectedComplex] = useState<ComplexSelection | null>(
    initialComplexSelectionFromUrl,
  );
  const [complexDetail, setComplexDetail] = useState<ComplexDetail | null>(null);
  const [parcelTrades, setParcelTrades] = useState<ParcelTrades | null>(null);
  const [tradeTrend, setTradeTrend] = useState<TradeTrendPoint[]>([]);
  const [tradePage, setTradePage] = useState(0);
  const [tradeRows, setTradeRows] = useState<TradeItem[]>([]);
  const [parcelComplexes, setParcelComplexes] = useState<ParcelComplexSummary[]>([]);
  const [detailState, setDetailState] = useState<DetailRequestState>('idle');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailRetrySeq, setDetailRetrySeq] = useState(0);
  const [searchResults, setSearchResults] = useState<ComplexSearchResult[]>([]);
  const [complexSuggestions, setComplexSuggestions] = useState<ComplexSuggestion[]>([]);
  const [searchState, setSearchState] = useState<PanelRequestState>('idle');
  const [searchError, setSearchError] = useState<string | null>(null);
  const [rootRegions, setRootRegions] = useState<RegionSummary[]>([]);
  const [regionDetail, setRegionDetail] = useState<RegionDetail | null>(null);
  const [regionComplexes, setRegionComplexes] = useState<RegionComplexSummary[]>([]);
  const [regionState, setRegionState] = useState<PanelRequestState>('idle');
  const [regionError, setRegionError] = useState<string | null>(null);
  const [regionTrail, setRegionTrail] = useState<RegionTrailItem[]>([]);
  const [isExplorationOpen, setIsExplorationOpen] = useState(true);
  const [filterFormKey, setFilterFormKey] = useState(0);
  const markerRequestSeq = useRef(0);
  const detailRequestSeq = useRef(0);
  const tradePageRequestSeq = useRef(0);
  const parcelComplexRequestSeq = useRef(0);
  const searchRequestSeq = useRef(0);
  const suggestionRequestSeq = useRef(0);
  const searchDebounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const regionRequestSeq = useRef(0);
  const initialRegionLoadStarted = useRef(false);
  const activeFilterCount = countActiveFilters(markerFilters);
  const isSearchPanelActive =
    searchState !== 'idle' || searchResults.length > 0 || complexSuggestions.length > 0;
  const sidebarMode: SidebarMode = selectedComplex == null
    ? isSearchPanelActive ? 'search' : 'region'
    : 'detail';

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
        setMarkerError(error instanceof Error ? error.message : '알 수 없는 마커 오류');
      });

    return () => {
      ignore = true;
    };
  }, [markerFilters, markerRetrySeq, viewport]);

  useEffect(() => {
    if (selectedComplex == null) {
      setComplexDetail(null);
      setParcelTrades(null);
      setTradeTrend([]);
      setTradePage(0);
      setTradeRows([]);
      setParcelComplexes([]);
      setDetailState('idle');
      setDetailError(null);
      return undefined;
    }

    const requestSeq = detailRequestSeq.current + 1;
    detailRequestSeq.current = requestSeq;
    tradePageRequestSeq.current += 1;
    let ignore = false;

    setDetailState('loading');
    setDetailError(null);

    const isComplexScoped = selectedComplex.parcelId == null && selectedComplex.complexId != null;
    const detailRequest = isComplexScoped
      ? fetchComplexDetailByComplexId(selectedComplex.complexId as number)
      : fetchComplexDetail(requiredParcelId(selectedComplex), selectedComplex.complexId);
    const tradeRequest = isComplexScoped
      ? fetchComplexTrades(selectedComplex.complexId as number)
      : fetchParcelTrades(requiredParcelId(selectedComplex), selectedComplex.complexId);
    const trendRequest = (isComplexScoped
      ? fetchComplexTradeTrend(selectedComplex.complexId as number)
      : fetchParcelTradeTrend(requiredParcelId(selectedComplex), selectedComplex.complexId)
    ).catch((): TradeTrendPoint[] => []);

    Promise.all([detailRequest, tradeRequest, trendRequest])
      .then(([nextDetail, nextTrades, nextTrend]) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }

        setComplexDetail(nextDetail);
        setParcelTrades(nextTrades);
        setTradeTrend(nextTrend);
        setTradePage(nextTrades.page);
        setTradeRows(nextTrades.trades);
        setDetailState('ready');
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }

        setComplexDetail(null);
        setParcelTrades(null);
        setTradeTrend([]);
        setTradePage(0);
        setTradeRows([]);
        setDetailState('error');
        setDetailError(error instanceof Error ? error.message : '알 수 없는 상세 정보 오류');
      });

    return () => {
      ignore = true;
    };
  }, [selectedComplex, detailRetrySeq]);

  useEffect(() => {
    if (complexDetail == null || detailState !== 'ready') {
      setParcelComplexes([]);
      return undefined;
    }

    const requestSeq = parcelComplexRequestSeq.current + 1;
    parcelComplexRequestSeq.current = requestSeq;
    let ignore = false;

    fetchParcelComplexes(complexDetail.parcelId)
      .then((nextComplexes) => {
        if (ignore || requestSeq !== parcelComplexRequestSeq.current) {
          return;
        }
        setParcelComplexes(nextComplexes);
      })
      .catch(() => {
        if (ignore || requestSeq !== parcelComplexRequestSeq.current) {
          return;
        }
        setParcelComplexes([]);
      });

    return () => {
      ignore = true;
    };
  }, [complexDetail, detailState]);

  useEffect(() => {
    if (!initialRegionLoad || initialRegionLoadStarted.current) {
      return;
    }

    initialRegionLoadStarted.current = true;
    loadRootRegions();
  }, [initialRegionLoad]);

  useEffect(() => () => {
    clearSearchDebounceTimer();
  }, []);

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

  const handleComplexMarkerSelect = useCallback((marker: ComplexMapMarker) => {
    setSelectedComplex({
      parcelId: marker.parcelId,
      complexId: marker.complexId,
    });
  }, []);

  function handleCloseDetailDrawer() {
    setSelectedComplex(null);
  }

  function handleRetryDetail() {
    setDetailRetrySeq((current) => current + 1);
  }

  function handleLoadMoreTrades() {
    if (selectedComplex == null) {
      return;
    }

    const nextPage = tradePage + 1;
    const requestSeq = tradePageRequestSeq.current + 1;
    tradePageRequestSeq.current = requestSeq;

    const request = selectedComplex.parcelId == null && selectedComplex.complexId != null
      ? fetchComplexTrades(selectedComplex.complexId, { page: nextPage, size: TRADE_PAGE_SIZE })
      : fetchParcelTrades(
        requiredParcelId(selectedComplex),
        selectedComplex.complexId,
        { page: nextPage, size: TRADE_PAGE_SIZE },
      );

    request
      .then((next) => {
        if (requestSeq !== tradePageRequestSeq.current) {
          return;
        }

        setTradePage(next.page);
        setTradeRows((current) => [...current, ...next.trades]);
      })
      .catch(() => {
        // Keep the loaded rows rendered when a page fetch fails.
      });
  }

  function clearSearchDebounceTimer() {
    if (searchDebounceTimer.current == null) {
      return;
    }

    clearTimeout(searchDebounceTimer.current);
    searchDebounceTimer.current = null;
  }

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const query = stringFormValue(new FormData(event.currentTarget), 'q').trim();
    clearSearchDebounceTimer();
    runComplexSearch(query);
  }

  function runComplexSearch(query: string) {
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
        setSearchError(error instanceof Error ? error.message : '알 수 없는 검색 오류');
      });
  }

  function handleSearchInputChange(value: string) {
    clearSearchDebounceTimer();

    const requestSeq = suggestionRequestSeq.current + 1;
    suggestionRequestSeq.current = requestSeq;
    const query = value.trim();

    if (query.length === 0) {
      setComplexSuggestions([]);
      setSearchResults([]);
      setSearchState('idle');
      setSearchError(null);
      searchRequestSeq.current += 1;
      return;
    }

    setSearchState('loading');
    setSearchError(null);

    fetchComplexSuggestions(query)
      .then((nextSuggestions) => {
        if (requestSeq !== suggestionRequestSeq.current) {
          return;
        }
        setComplexSuggestions(nextSuggestions);
      })
      .catch(() => {
        if (requestSeq !== suggestionRequestSeq.current) {
          return;
        }
        setComplexSuggestions([]);
      });

    searchDebounceTimer.current = setTimeout(() => {
      searchDebounceTimer.current = null;
      runComplexSearch(query);
    }, SEARCH_DEBOUNCE_MILLIS);
  }

  function handleSearchResultSelect(result: ComplexSearchResult) {
    clearSearchDebounceTimer();
    setSelectedComplex({
      parcelId: result.parcelId,
      complexId: result.complexId,
    });
    if (hasDisplayCoordinate(result)) {
      focusMap(result.latitude, result.longitude, 4, SEARCH_FOCUS_DELTA);
    }
  }

  function handleSuggestionSelect(suggestion: ComplexSuggestion) {
    clearSearchDebounceTimer();
    setSelectedComplex({
      parcelId: suggestion.parcelId,
      complexId: suggestion.complexId,
    });
    setComplexSuggestions([]);
  }

  function handleComplexSummarySelect(complex: ParcelComplexSummary | RegionComplexSummary) {
    clearSearchDebounceTimer();
    setSelectedComplex({
      parcelId: complex.parcelId,
      complexId: complex.complexId,
    });
  }

  function handleRegionComplexSelect(complex: RegionComplexSummary) {
    handleComplexSummarySelect(complex);
    if (hasDisplayCoordinate(complex)) {
      focusMap(complex.latitude, complex.longitude, 4, SEARCH_FOCUS_DELTA);
    }
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

  function handleFilterReset() {
    setMarkerFilters(EMPTY_COMPLEX_MARKER_FILTERS);
    setFilterFormKey((current) => current + 1);
  }

  function handleLoadRootRegions() {
    loadRootRegions();
  }

  function loadRootRegions() {
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;

    setRegionState('loading');
    setRegionError(null);
    setRegionDetail(null);
    setRegionComplexes([]);
    setRegionTrail([]);

    fetchRootRegions()
      .then((nextRegions) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRootRegions(nextRegions);
        setRegionComplexes([]);
        setRegionState(nextRegions.length === 0 ? 'empty' : 'ready');
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRootRegions([]);
        setRegionDetail(null);
        setRegionComplexes([]);
        setRegionTrail([]);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : '알 수 없는 지역 오류');
      });
  }

  function handleRegionSelect(region: RegionTrailItem) {
    const requestSeq = regionRequestSeq.current + 1;
    regionRequestSeq.current = requestSeq;
    const nextTrail = [...regionTrail, region];
    const nextMapLevel = regionFocusLevel(nextTrail.length);

    setRegionState('loading');
    setRegionError(null);

    Promise.all([
      fetchRegionDetail(region.id),
      fetchRegionComplexes(region.id, { limit: 20, offset: 0 }),
    ])
      .then(([nextDetail, nextComplexes]) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRegionDetail(nextDetail);
        setRegionComplexes(nextComplexes);
        setRootRegions(nextDetail.children);
        setRegionTrail([
          ...regionTrail,
          {
            id: nextDetail.id,
            name: nextDetail.name,
          },
        ]);
        setRegionState('ready');
        focusMap(
          nextDetail.latitude,
          nextDetail.longitude,
          nextMapLevel,
          mapFocusDeltaForLevel(nextMapLevel),
        );
      })
      .catch((error: unknown) => {
        if (requestSeq !== regionRequestSeq.current) {
          return;
        }

        setRegionDetail(null);
        setRegionComplexes([]);
        setRegionState('error');
        setRegionError(error instanceof Error ? error.message : '알 수 없는 지역 상세 오류');
      });
  }

  const handleRegionMarkerSelect = useCallback((marker: RegionMapMarker) => {
    const nextLevel = nextRegionMarkerLevel(viewport.level);

    setIsExplorationOpen(true);
    focusMap(marker.lat, marker.lng, nextLevel, mapFocusDeltaForLevel(nextLevel));
  }, [viewport.level]);

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
    <main
      className="app-shell"
      data-detail-open={selectedComplex == null ? 'false' : 'true'}
      data-ui-surface="map-first"
    >
      <header aria-label="상단 앱 바" className="app-bar">
        <div className="app-brand">
          <h1>Home Search</h1>
          <span>{selectedComplex == null ? '지도 탐색' : '단지 상세'}</span>
        </div>
        <div className="app-status" aria-label="실데이터 상태 요약">
          <span>{mapModeLabel(viewport.level)}</span>
          <span>{markerSummaryLabel(markerState, markers)}</span>
          <span>{detailHeaderStatusLabel(selectedComplex, detailState, parcelTrades)}</span>
        </div>
        <button
          type="button"
          aria-controls="exploration-panel"
          aria-expanded={isExplorationOpen}
          aria-label={isExplorationOpen ? '탐색 패널 접기' : '탐색 패널 열기'}
          className="exploration-toggle"
          onClick={() => {
            setIsExplorationOpen((current) => !current);
          }}
        >
          {isExplorationOpen ? '접기' : '탐색'}
        </button>
      </header>

      <div className="map-workspace" data-layout-region="map-workspace">
        <section aria-label="지도 화면" className="map-surface">
          <KakaoMapSurface
            appKey={kakaoMapAppKey}
            focusTarget={mapFocusTarget}
            initialLevel={initialMapLevel}
            level={viewport.level}
            markers={markers}
            onComplexMarkerSelect={handleComplexMarkerSelect}
            onRegionMarkerSelect={handleRegionMarkerSelect}
            onRuntimeErrorChange={setMapRuntimeError}
            onRuntimeStateChange={setMapRuntimeState}
            onViewportChange={handleViewportChange}
          />

          <FilterPanel
            activeFilterCount={activeFilterCount}
            formKey={filterFormKey}
            onReset={handleFilterReset}
            onSubmit={handleFilterSubmit}
          />
          <MapOverlayPanels
            bounds={viewport.bounds}
            level={viewport.level}
            mapRuntimeError={mapRuntimeError}
            mapRuntimeState={mapRuntimeState}
            markerError={markerError}
            markerState={markerState}
            markers={markers}
            onComplexMarkerSelect={handleComplexMarkerSelect}
            onRegionMarkerSelect={handleRegionMarkerSelect}
            onRetryMarkers={handleRetryMarkers}
            onZoomIn={handleZoomIn}
            onZoomOut={handleZoomOut}
          />
        </section>

        <ExplorationPanel
          complexDetail={complexDetail}
          complexSuggestions={complexSuggestions}
          detailError={detailError}
          detailState={detailState}
          isOpen={isExplorationOpen}
          onCloseDetail={handleCloseDetailDrawer}
          onComplexSelect={handleComplexSummarySelect}
          onLoadMoreTrades={handleLoadMoreTrades}
          onLoadRootRegions={handleLoadRootRegions}
          onRegionComplexSelect={handleRegionComplexSelect}
          onRegionSelect={handleRegionSelect}
          onRetryDetail={handleRetryDetail}
          onSearchInputChange={handleSearchInputChange}
          onSearchResultSelect={handleSearchResultSelect}
          onSearchSubmit={handleSearchSubmit}
          onSuggestionSelect={handleSuggestionSelect}
          parcelComplexes={parcelComplexes}
          parcelTrades={parcelTrades}
          regionComplexes={regionComplexes}
          regionDetail={regionDetail}
          regionError={regionError}
          regionState={regionState}
          regionTrail={regionTrail}
          rootRegions={rootRegions}
          searchError={searchError}
          searchResults={searchResults}
          searchState={searchState}
          selectedComplex={selectedComplex}
          sidebarMode={sidebarMode}
          tradeRows={tradeRows}
          tradeTrend={tradeTrend}
        />
      </div>

    </main>
  );
}

function detailHeaderStatusLabel(
  selection: ComplexSelection | null,
  state: DetailRequestState,
  trades: ParcelTrades | null,
): string {
  if (selection == null) {
    return '상세 미선택';
  }

  if (state !== 'ready' || trades == null) {
    return `상세 ${detailRequestLabel(state)}`;
  }

  return `거래 ${trades.totalElements.toLocaleString()}건`;
}

function detailRequestLabel(state: DetailRequestState): string {
  switch (state) {
    case 'idle':
      return '대기';
    case 'loading':
      return '불러오는 중';
    case 'ready':
      return '완료';
    case 'error':
      return '오류';
  }
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

function regionFocusLevel(depth: number): number {
  if (depth <= 1) {
    return 9;
  }

  if (depth === 2) {
    return 6;
  }

  return 4;
}

function nextRegionMarkerLevel(level: number): number {
  return Math.max(1, level - 2);
}

function mapFocusDeltaForLevel(level: number): number {
  if (level >= 8) {
    return 0.2;
  }

  if (level >= 6) {
    return 0.08;
  }

  return SEARCH_FOCUS_DELTA;
}

type DisplayCoordinateCandidate = {
  latitude: number | null;
  longitude: number | null;
};

function hasDisplayCoordinate<T extends DisplayCoordinateCandidate>(
  result: T,
): result is T & { latitude: number; longitude: number } {
  return result.latitude != null && result.longitude != null;
}

function requiredParcelId(selection: ComplexSelection): number {
  if (selection.parcelId == null) {
    throw new Error('parcelId is required for parcel-scoped detail request');
  }
  return selection.parcelId;
}

function initialComplexSelectionFromUrl(): ComplexSelection | null {
  const complexId = Number(new URLSearchParams(window.location.search).get('complexId'));
  if (!Number.isSafeInteger(complexId) || complexId <= 0) {
    return null;
  }
  return {
    parcelId: null,
    complexId,
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

function countActiveFilters(filters: ComplexMarkerFilters): number {
  return Object.values(filters).filter((value) => value != null).length;
}

function mapModeLabel(level: number): string {
  return level <= 4 ? '단지 보기' : '지역 보기';
}

function markerSummaryLabel(
  state: MarkerRequestState,
  markers: MapMarkersResult | null,
): string {
  if (state === 'loading') {
    return '불러오는 중';
  }

  if (state === 'error') {
    return '마커 오류';
  }

  if (state === 'empty' || !markers) {
    return '마커 0개';
  }

  return `마커 ${markers.markers.length.toLocaleString()}개`;
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

function isCoordinateAdminPath(): boolean {
  return window.location.pathname.startsWith('/admin/coordinates');
}

function isMetadataAdminPath(): boolean {
  return window.location.pathname.startsWith('/admin/metadata');
}

function isCoordinateReasonGuidePath(): boolean {
  return window.location.pathname === '/admin/coordinates/reasons';
}

function isAdminSurfaceEnabled(): boolean {
  return import.meta.env.VITE_APP_SURFACE === 'admin'
    || import.meta.env.VITE_ENABLE_ADMIN_SURFACE === 'true';
}
