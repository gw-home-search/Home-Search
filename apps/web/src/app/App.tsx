import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
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
  CoordinateOverrideAdminPage,
  CoordinateReasonGuidePage,
} from '../features/admin/CoordinateOverrideAdminPage';
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

export function App({
  initialMapLevel,
  initialRegionLoad = true,
  kakaoMapAppKey,
}: AppProps) {
  if (isCoordinateAdminPath() && !isAdminSurfaceEnabled()) {
    return <NotFoundPage />;
  }

  if (isCoordinateReasonGuidePath()) {
    return <CoordinateReasonGuidePage />;
  }

  if (isCoordinateAdminPath()) {
    return <CoordinateOverrideAdminPage />;
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
      setParcelComplexes([]);
      setDetailState('idle');
      setDetailError(null);
      return undefined;
    }

    const requestSeq = detailRequestSeq.current + 1;
    detailRequestSeq.current = requestSeq;
    let ignore = false;

    setDetailState('loading');
    setDetailError(null);

    const detailRequest = selectedComplex.parcelId == null && selectedComplex.complexId != null
      ? fetchComplexDetailByComplexId(selectedComplex.complexId)
      : fetchComplexDetail(requiredParcelId(selectedComplex), selectedComplex.complexId);
    const tradeRequest = selectedComplex.parcelId == null && selectedComplex.complexId != null
      ? fetchComplexTrades(selectedComplex.complexId)
      : fetchParcelTrades(requiredParcelId(selectedComplex), selectedComplex.complexId);

    Promise.all([detailRequest, tradeRequest])
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
          <p className="map-status">{mapRuntimeStatusLabel(mapRuntimeState)}</p>
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
          {mapRuntimeState === 'ready' || markers == null ? null : (
            <FallbackMarkerLayer
              bounds={viewport.bounds}
              markers={markers}
              onComplexMarkerSelect={handleComplexMarkerSelect}
              onRegionMarkerSelect={handleRegionMarkerSelect}
            />
          )}

          <form
            key={filterFormKey}
            aria-label="마커 필터"
            className="filter-panel"
            data-filter-state={activeFilterCount > 0 ? 'active' : 'idle'}
            data-map-overlay="filters"
            data-ui-layer="filter-controls"
            onSubmit={handleFilterSubmit}
          >
            <fieldset className="filter-group">
              <legend>면적</legend>
              <div className="filter-range">
                <label>
                  <span>최소</span>
                  <input
                    aria-label="최소 평형"
                    name="pyeongMin"
                    placeholder="평"
                    type="number"
                  />
                </label>
                <label>
                  <span>최대</span>
                  <input
                    aria-label="최대 평형"
                    name="pyeongMax"
                    placeholder="평"
                    type="number"
                  />
                </label>
              </div>
            </fieldset>
            <fieldset className="filter-group">
              <legend>가격</legend>
              <div className="filter-range">
                <label>
                  <span>최소</span>
                  <input
                    aria-label="최소 가격 억"
                    name="priceEokMin"
                    placeholder="억"
                    step="0.1"
                    type="number"
                  />
                </label>
                <label>
                  <span>최대</span>
                  <input
                    aria-label="최대 가격 억"
                    name="priceEokMax"
                    placeholder="억"
                    step="0.1"
                    type="number"
                  />
                </label>
              </div>
            </fieldset>
            <fieldset className="filter-group">
              <legend>연식</legend>
              <div className="filter-range">
                <label>
                  <span>최소</span>
                  <input
                    aria-label="최소 연식"
                    name="ageMin"
                    placeholder="년"
                    type="number"
                  />
                </label>
                <label>
                  <span>최대</span>
                  <input
                    aria-label="최대 연식"
                    name="ageMax"
                    placeholder="년"
                    type="number"
                  />
                </label>
              </div>
            </fieldset>
            <fieldset className="filter-group">
              <legend>세대수</legend>
              <div className="filter-range">
                <label>
                  <span>최소</span>
                  <input
                    aria-label="최소 세대수"
                    name="unitMin"
                    placeholder="세대"
                    type="number"
                  />
                </label>
                <label>
                  <span>최대</span>
                  <input
                    aria-label="최대 세대수"
                    name="unitMax"
                    placeholder="세대"
                    type="number"
                  />
                </label>
              </div>
            </fieldset>
            <div className="filter-actions">
              <p className="filter-status" aria-live="polite">
                {activeFilterCount > 0 ? `필터 ${activeFilterCount}개 적용` : '필터 없음'}
              </p>
              <button type="submit" aria-label="마커 필터 적용">
                적용
              </button>
              <button type="button" aria-label="마커 필터 초기화" onClick={handleFilterReset}>
                초기화
              </button>
            </div>
          </form>

          <div aria-label="지도 조작" className="map-controls">
            <button type="button" aria-label="지도 확대" onClick={handleZoomIn}>
              +
            </button>
            <button type="button" aria-label="지도 축소" onClick={handleZoomOut}>
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
              <button type="button" aria-label="마커 다시 불러오기" onClick={handleRetryMarkers}>
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
                      handleComplexMarkerSelect(marker);
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
                      handleRegionMarkerSelect(marker);
                    }}
                  >
                    <span className="marker-list-price">{marker.name}</span>
                    <span className="marker-list-subtitle">
                      {regionMarkerUnitOrActionLabel(marker, viewport.level)}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
        </section>

        <section
          id="exploration-panel"
          aria-label="탐색 패널"
          aria-hidden={!isExplorationOpen}
          className="exploration-panel"
          data-collapsed={isExplorationOpen ? 'false' : 'true'}
          data-sidebar-mode={sidebarMode}
          data-ui-layer="exploration-panel"
          hidden={!isExplorationOpen}
        >
          <div className="exploration-panel-header" hidden={sidebarMode === 'detail'}>
            <p>탐색</p>
            <span>{explorationSummaryLabel(searchResults.length, regionComplexes.length)}</span>
          </div>

          <form
            aria-label="단지 검색"
            className="search-panel exploration-search-panel"
            hidden={sidebarMode === 'detail'}
            onSubmit={handleSearchSubmit}
          >
            <label>
              <span>단지</span>
              <input
                aria-label="단지 검색"
                name="q"
                onInput={(event) => {
                  handleSearchInputChange(event.currentTarget.value);
                }}
                placeholder="아파트명을 검색해보세요."
                type="search"
              />
            </label>
            <button type="submit" aria-label="단지 검색 실행">
              검색
            </button>
          </form>

          {selectedComplex == null ? null : (
            <DetailSidebar
              complexDetail={complexDetail}
              detailError={detailError}
              detailState={detailState}
              onBack={handleCloseDetailDrawer}
              onComplexSelect={handleComplexSummarySelect}
              onRetryDetail={handleRetryDetail}
              parcelComplexes={parcelComplexes}
              parcelTrades={parcelTrades}
              selection={selectedComplex}
            />
          )}

          <section
            id="exploration-panel-search"
            aria-label="검색 결과 패널"
            className="panel-section"
            data-api-flow="search"
            hidden={sidebarMode !== 'search'}
          >
            <div className="panel-section-header">
              <p>검색 결과</p>
              <span>{panelRequestLabel(searchState)}</span>
            </div>

            <DataCountStrip
              items={[
                ['제안', complexSuggestions.length],
                ['결과', searchResults.length],
              ]}
            />

            {searchState === 'loading' ? (
              <p className="panel-message" role="status" aria-live="polite">
                단지 검색 중
              </p>
            ) : null}

            {searchState === 'empty' ? (
              <p className="panel-message" role="status" aria-live="polite">
                검색 결과가 없습니다
              </p>
            ) : null}

            {searchState === 'error' ? (
              <p className="panel-message panel-message-error" role="alert">
                검색을 사용할 수 없습니다.
                {searchError ? ` ${searchError}` : null}
              </p>
            ) : null}

            {searchResults.length > 0 ? (
              <ul aria-label="검색 결과" className="panel-list panel-list-strong">
                {searchResults.map((result) => (
                  <li key={result.complexId}>
                    <button
                      type="button"
                      aria-label={`검색 결과 선택 ${result.complexName}`}
                      onClick={() => {
                        handleSearchResultSelect(result);
                      }}
                    >
                      <span>{result.complexName}</span>
                      <span>{formatAddress(result.address)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}

            {complexSuggestions.length > 0 ? (
              <ul aria-label="검색 제안" className="panel-list">
                {complexSuggestions.map((suggestion) => (
                  <li key={suggestion.complexId}>
                    <button
                      type="button"
                      aria-label={`검색 제안 선택 ${suggestion.complexName}`}
                      onClick={() => {
                        handleSuggestionSelect(suggestion);
                      }}
                    >
                      <span>{suggestion.complexName}</span>
                      <span>{formatAddress(suggestion.address)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>

          <section
            id="exploration-panel-region"
            aria-label="지역 탐색 패널"
            className="panel-section region-panel"
            data-api-flow="region"
            hidden={sidebarMode !== 'region'}
          >
            <div className="panel-section-header">
              <p>지역</p>
              {regionDetail ? <span>{regionDetail.name}</span> : <span>전체</span>}
            </div>
            <nav aria-label="지역 단계" className="region-breadcrumb">
              <button type="button" aria-label="지역 처음으로" onClick={handleLoadRootRegions}>
                시도 선택
              </button>
              {regionTrail.map((region) => (
                <span key={region.id}>{region.name}</span>
              ))}
            </nav>
            <div className="region-step-summary">
              <p>{regionStepLabel(regionTrail.length)}</p>
              <button type="button" aria-label="상위 지역 불러오기" onClick={handleLoadRootRegions}>
                처음부터
              </button>
            </div>
            <DataCountStrip
              items={[
                ['하위 지역', rootRegions.length],
                ['단지', regionComplexes.length],
              ]}
            />

            {regionState === 'loading' ? (
              <p className="panel-message" role="status" aria-live="polite">
                지역 불러오는 중
              </p>
            ) : null}

            {regionState === 'empty' ? (
              <p className="panel-message" role="status" aria-live="polite">
                지역이 없습니다
              </p>
            ) : null}

            {regionState === 'error' ? (
              <p className="panel-message panel-message-error" role="alert">
                지역 탐색을 사용할 수 없습니다.
                {regionError ? ` ${regionError}` : null}
              </p>
            ) : null}

            {rootRegions.length > 0 ? (
              <ul aria-label="지역 탐색" className="panel-list region-grid-list">
                {rootRegions.map((region) => (
                  <li key={region.id}>
                    <button
                      type="button"
                      aria-label={`지역 이동 ${region.name}`}
                      onClick={() => {
                        handleRegionSelect(region);
                      }}
                    >
                      {region.name}
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}

            {regionComplexes.length > 0 ? (
              <ul aria-label="지역 단지 목록" className="panel-list">
                {regionComplexes.map((complex) => (
                  <li key={complex.complexId}>
                    <button
                      type="button"
                      aria-label={`지역 단지 선택 ${complex.complexName}`}
                      onClick={() => {
                        handleRegionComplexSelect(complex);
                      }}
                    >
                      <span>{complex.complexName}</span>
                      <span>{formatAddress(complex.address)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            ) : null}
          </section>
        </section>
      </div>

    </main>
  );
}

function DetailSidebar({
  complexDetail,
  detailError,
  detailState,
  onBack,
  onComplexSelect,
  onRetryDetail,
  parcelComplexes,
  parcelTrades,
  selection,
}: {
  complexDetail: ComplexDetail | null;
  detailError: string | null;
  detailState: DetailRequestState;
  onBack: () => void;
  onComplexSelect: (complex: ParcelComplexSummary | RegionComplexSummary) => void;
  onRetryDetail: () => void;
  parcelComplexes: ParcelComplexSummary[];
  parcelTrades: ParcelTrades | null;
  selection: ComplexSelection;
}) {
  return (
    <section aria-label="단지 상세 패널" className="detail-sidebar" data-ui-layer="detail-sidebar">
      <div className="detail-drawer-header">
        <button
          type="button"
          aria-label="상세에서 뒤로가기"
          className="detail-back-button"
          onClick={onBack}
        >
          ←
        </button>
        <div>
          <p className="detail-drawer-kicker">{detailDrawerKicker(selection)}</p>
          <p className="detail-drawer-state">{detailRequestLabel(detailState)}</p>
        </div>
      </div>

      <DataStatusList
        ariaLabel="상세 API 데이터 요약"
        flow="detail"
        items={[
          ['상세', detailRequestLabel(detailState)],
          ['실거래', parcelTrades == null ? '대기' : `${parcelTrades.trades.length.toLocaleString()}건`],
          ['같은 필지', detailState === 'ready' ? `${parcelComplexes.length.toLocaleString()}개` : '대기'],
        ]}
      />

      {detailState === 'loading' ? (
        <p className="detail-message" role="status" aria-live="polite">
          상세 정보 불러오는 중
        </p>
      ) : null}

      {detailState === 'error' ? (
        <p className="detail-message detail-message-error" role="alert">
          상세 정보를 불러오지 못했습니다.
          {detailError ? ` ${detailError}` : null}
          {' '}
          <button type="button" aria-label="상세 정보 다시 불러오기" onClick={onRetryDetail}>
            다시 시도
          </button>
        </p>
      ) : null}

      {detailState === 'ready' && complexDetail ? (
        <>
          <section className="detail-identity" data-detail-section="identity">
            <h2>{complexDetail.name}</h2>
            <p className="detail-address">{formatAddress(complexDetail.address)}</p>
            <dl className="detail-key-stats">
              {detailMetric('최근 거래', latestTradeAmountLabel(parcelTrades?.trades ?? []))}
              {detailMetric('실거래', `${(parcelTrades?.trades.length ?? 0).toLocaleString()}건`)}
              {detailMetric('세대수', formatNumber(complexDetail.unitCnt, '세대'))}
            </dl>
            {parcelComplexes.length > 0 ? (
              <section aria-label="같은 필지 단지 선택" className="detail-complex-switcher">
                <div className="detail-section-heading">
                  <h3>같은 필지 단지</h3>
                  <span>{parcelComplexes.length.toLocaleString()}개</span>
                </div>
                <ul>
                  {parcelComplexes.map((complex) => (
                    <li key={complex.complexId}>
                      <button
                        type="button"
                        aria-label={`같은 필지 단지 선택 ${complex.complexName}`}
                        aria-current={complex.complexId === complexDetail.complexId ? 'true' : undefined}
                        onClick={() => {
                          onComplexSelect(complex);
                        }}
                      >
                        <span>{complex.complexName}</span>
                        <span>{complexSummaryMeta(complex)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <dl className="detail-metrics">
              {detailMetric('거래명', complexDetail.tradeName)}
              {detailMetric('동수', formatNumber(complexDetail.dongCnt, '개동'))}
              {detailMetric('사용승인일', complexDetail.useDate)}
              {detailMetric('대지면적', formatNumber(complexDetail.platArea, '㎡'))}
              {detailMetric('건축면적', formatNumber(complexDetail.archArea, '㎡'))}
              {detailMetric('연면적', formatNumber(complexDetail.totArea, '㎡'))}
              {detailMetric('건폐율', formatNumber(complexDetail.bcRat, '%'))}
              {detailMetric('용적률', formatNumber(complexDetail.vlRat, '%'))}
            </dl>
          </section>
          <TradeAmountChart trades={parcelTrades?.trades ?? []} />
          <TradeList trades={parcelTrades?.trades ?? []} />
        </>
      ) : null}
    </section>
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

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return '최근 거래 없음';
  }

  return `${amount.toLocaleString()}만원`;
}

function DataCountStrip({ items }: { items: Array<[string, number]> }) {
  return (
    <dl className="data-count-strip">
      {items.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          {' '}
          <dd>{value.toLocaleString()}</dd>
        </div>
      ))}
    </dl>
  );
}

function DataStatusList({
  ariaLabel,
  flow,
  items,
}: {
  ariaLabel: string;
  flow: string;
  items: Array<[string, string]>;
}) {
  return (
    <ul aria-label={ariaLabel} className="data-status-list" data-api-flow={flow}>
      {items.map(([label, value]) => (
        <li key={label}>
          <span>{label}</span>
          {' '}
          <strong>{value}</strong>
        </li>
      ))}
    </ul>
  );
}

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
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

function detailDrawerKicker(selection: ComplexSelection): string {
  if (selection.parcelId == null) {
    return `단지 ${selection.complexId}`;
  }

  return selection.complexId == null
    ? `필지 ${selection.parcelId}`
    : `단지 ${selection.complexId} / 필지 ${selection.parcelId}`;
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

  return `거래 ${trades.trades.length.toLocaleString()}건`;
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

function panelRequestLabel(state: PanelRequestState): string {
  switch (state) {
    case 'idle':
      return '대기';
    case 'loading':
      return '불러오는 중';
    case 'ready':
      return '완료';
    case 'empty':
      return '결과 없음';
    case 'error':
      return '오류';
  }
}

function explorationSummaryLabel(searchCount: number, regionComplexCount: number): string {
  if (searchCount > 0 && regionComplexCount > 0) {
    return `검색 ${searchCount.toLocaleString()} / 지역 ${regionComplexCount.toLocaleString()}`;
  }

  if (searchCount > 0) {
    return `검색 ${searchCount.toLocaleString()}`;
  }

  if (regionComplexCount > 0) {
    return `지역 ${regionComplexCount.toLocaleString()}`;
  }

  return '지역 탐색';
}

function latestTradeAmountLabel(trades: TradeItem[]): string {
  const latestTrade = trades.slice().sort(compareTradesNewestFirst)[0];
  return latestTrade == null ? '최근 거래 없음' : formatAmount(latestTrade.dealAmount);
}

function complexSummaryMeta(complex: ParcelComplexSummary | RegionComplexSummary): string {
  const values = [
    formatNumber(complex.unitCnt, '세대'),
    complex.useDate,
    formatAddress(complex.address),
  ].filter((value): value is string => value != null);

  return values.length === 0 ? '요약 정보 없음' : values.join(' · ');
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

function regionStepLabel(depth: number): string {
  if (depth === 0) {
    return '시도 선택';
  }

  if (depth === 1) {
    return '시군구 선택';
  }

  if (depth === 2) {
    return '읍면동 선택';
  }

  return '단지 선택';
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
    <section className="trade-chart" aria-label="거래가 차트">
      <div className="trade-section-header">
        <h3>실거래가 흐름</h3>
        {points.length > 0 ? <p>최근 {points.length}건</p> : null}
      </div>

      {points.length === 0 ? (
        <p className="trade-chart-empty">표시할 거래가 없습니다</p>
      ) : (
        <>
          <div
            className="trade-chart-plot"
            role="img"
            aria-label={`${points[0]?.dealDate}부터 ${
              points[points.length - 1]?.dealDate
            }까지 최근 거래가`}
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
    <section className="trade-list" aria-label="거래 목록" data-detail-section="trade-history">
      <h3>거래 내역</h3>
      {trades.length === 0 ? (
        <p>거래 내역이 없습니다</p>
      ) : (
        <table>
          <caption className="sr-only">선택한 단지 또는 필지의 실거래 목록</caption>
          <thead>
            <tr>
              <th scope="col">일자</th>
              <th scope="col">금액</th>
              <th scope="col">면적</th>
              <th scope="col">층</th>
            </tr>
          </thead>
          <tbody>
            {trades.map((trade) => (
              <tr key={trade.tradeId}>
                <td>{trade.dealDate}</td>
                <td data-trade-cell="amount">{formatAmount(trade.dealAmount)}</td>
                <td data-trade-cell="area">{trade.exclArea.toLocaleString()}㎡</td>
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
  const floor = trade.floor == null ? '층 정보 없음' : `${trade.floor}층`;
  return trade.aptDong == null ? floor : `${trade.aptDong} / ${floor}`;
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

function isCoordinateReasonGuidePath(): boolean {
  return window.location.pathname === '/admin/coordinates/reasons';
}

function isAdminSurfaceEnabled(): boolean {
  return import.meta.env.VITE_APP_SURFACE === 'admin'
    || import.meta.env.VITE_ENABLE_ADMIN_SURFACE === 'true';
}
