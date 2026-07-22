import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import { useComplexDetail } from '../features/complex-detail/hooks/useComplexDetail';
import { ExplorationPanel } from '../features/exploration/ExplorationPanel';
import { FilterPanel } from '../features/filters/FilterPanel';
import { MapWorkspace } from '../features/map/MapWorkspace';
import { useMapMarkers } from '../features/map/hooks/useMapMarkers';
import { useMapViewport } from '../features/map/hooks/useMapViewport';
import { useRegionExplorer } from '../features/region/hooks/useRegionExplorer';
import {
  SEARCH_FOCUS_DELTA,
  useComplexSearch,
} from '../features/search/hooks/useComplexSearch';
import type { MapUiCommand, RegionMapMarker, SidebarMode } from './mapAppTypes';
import { declutterComplexMarkers } from '../features/map/markerViewModel';
import { AppHeader } from './AppHeader';
import { useFavoriteComplex } from '../features/favorites/hooks/useFavoriteComplex';
import type { IndexedDbChatConversationStore } from '../features/chat/storage/chatConversationStore';
import type { ChatAction } from '../features/chat/actionContract';
import type { ChatUiContext } from '../features/chat/conversationContract';
import { MyPagePanel } from '../features/my-page/MyPageRoutes';

export type MapAppProps = {
  initialMapLevel?: number;
  initialRegionLoad?: boolean;
  kakaoMapAppKey?: string;
  chatConversationStore?: IndexedDbChatConversationStore;
};

export function MapApp({
  initialMapLevel = 12,
  initialRegionLoad = true,
  kakaoMapAppKey = getConfiguredKakaoMapAppKey(),
  chatConversationStore,
}: MapAppProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const isMyPageRoute = isMyPagePath(location.pathname);
  const [isExplorationOpen, setIsExplorationOpen] = useState(() => window.innerWidth > 720);
  const [isChatOpen, setIsChatOpen] = useState(false);
  const [mapUiCommand, setMapUiCommand] = useState<MapUiCommand | null>(null);
  const consumedMapActionIds = useRef(new Set<string>());
  const explorationButtonRef = useRef<HTMLButtonElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);
  const favoriteReturnFocusRef = useRef<HTMLElement | null>(null);
  const favoriteFallbackFocusRef = useRef<HTMLElement | null>(null);
  const favoriteFocusIdRef = useRef<number | null>(null);
  const shouldRestoreFavoriteFocusRef = useRef(false);
  const previousPathnameRef = useRef<string | null>(null);

  useEffect(() => {
    function syncExplorationWithViewport() {
      if (isChatOpen && window.innerWidth <= 1279) setIsExplorationOpen(false);
      else if (window.innerWidth > 720) setIsExplorationOpen(true);
    }
    window.addEventListener('resize', syncExplorationWithViewport);
    return () => window.removeEventListener('resize', syncExplorationWithViewport);
  }, [isChatOpen]);
  const viewport = useMapViewport(initialMapLevel);
  const markerData = useMapMarkers(viewport.viewport);
  const detail = useComplexDetail();
  const closeComplexDetail = detail.closeDetail;
  const selectFavoriteComplex = detail.selectComplex;
  useEffect(() => {
    const previousPathname = previousPathnameRef.current;
    previousPathnameRef.current = location.pathname;
    if (
      isMyPageRoute
      && detail.selectedComplex != null
      && (previousPathname == null || previousPathname !== location.pathname)
    ) closeComplexDetail();
  }, [closeComplexDetail, detail.selectedComplex, isMyPageRoute, location.pathname]);
  const favorite = useFavoriteComplex(detail.complexDetail?.complexId);
  useEffect(() => {
    if (detail.selectedComplex != null) setIsExplorationOpen(true);
  }, [detail.selectedComplex]);
  const search = useComplexSearch({
    focusMap: viewport.focusMap,
    selectComplex: detail.selectComplex,
  });
  const region = useRegionExplorer({
    focusMap: viewport.focusMap,
    initialRegionLoad,
    onComplexSelect: detail.selectComplexSummary,
  });
  const handleMapRegionSelect = region.handleMapRegionSelect;
  const sidebarMode: SidebarMode = detail.selectedComplex == null
    ? search.isSearchPanelActive ? 'search' : 'region'
    : 'detail';
  const workspacePanelMode = detail.selectedComplex != null
    ? 'detail'
    : isMyPageRoute ? 'my-page' : sidebarMode;
  const isWorkspacePanelOpen = isMyPageRoute
    ? !(isChatOpen && window.innerWidth <= 1279)
    : isExplorationOpen;
  const visibleMarkerData = useMemo(() => {
    if (markerData.markers?.kind !== 'complex') return { markers: markerData.markers, hiddenCount: 0 };
    const result = declutterComplexMarkers(markerData.markers.markers, detail.selectedComplex, viewport.viewport.level);
    return { markers: { ...markerData.markers, markers: result.markers }, hiddenCount: result.hiddenCount };
  }, [detail.selectedComplex, markerData.markers, viewport.viewport.level]);
  const chatUiContext = useMemo<ChatUiContext>(() => ({
    mapViewport: viewport.viewport,
    ...(detail.selectedComplex?.complexId != null && detail.selectedComplex.parcelId != null
      ? {
        selectedComplex: {
          complexId: detail.selectedComplex.complexId,
          parcelId: detail.selectedComplex.parcelId,
        },
      }
      : {}),
  }), [detail.selectedComplex, viewport.viewport]);

  const handleRegionMarkerSelect = useCallback((marker: RegionMapMarker) => {
    setIsExplorationOpen(true);
    handleMapRegionSelect(marker, viewport.viewport.level);
  }, [handleMapRegionSelect, viewport.viewport.level]);

  const closeMobileExploration = useCallback(() => {
    if (isMyPageRoute) navigate('/');
    setIsExplorationOpen(false);
    queueMicrotask(() => explorationButtonRef.current?.focus());
  }, [isMyPageRoute, navigate]);

  const openMobileExploration = useCallback(() => {
    if (isMyPageRoute) navigate('/');
    setIsExplorationOpen(true);
    queueMicrotask(() => searchInputRef.current?.focus());
  }, [isMyPageRoute, navigate]);

  const dismissMobileDetail = useCallback(() => {
    closeComplexDetail();
    if (isMyPageRoute) navigate('/');
    setIsExplorationOpen(false);
    queueMicrotask(() => explorationButtonRef.current?.focus());
  }, [closeComplexDetail, isMyPageRoute, navigate]);

  const closeDetail = useCallback(() => {
    if (isMyPageRoute) shouldRestoreFavoriteFocusRef.current = true;
    closeComplexDetail();
  }, [closeComplexDetail, isMyPageRoute]);

  useEffect(() => {
    if (detail.selectedComplex != null || !shouldRestoreFavoriteFocusRef.current) return;
    shouldRestoreFavoriteFocusRef.current = false;
    queueMicrotask(() => {
      const previousTrigger = favoriteReturnFocusRef.current;
      if (previousTrigger?.isConnected) {
        previousTrigger.focus();
        return;
      }
      favoriteFallbackFocusRef.current?.focus();
    });
  }, [detail.selectedComplex]);

  const handleFavoriteSelect = useCallback((complexId: number, trigger: HTMLElement) => {
    favoriteReturnFocusRef.current = trigger;
    favoriteFallbackFocusRef.current = trigger.closest('.my-page-panel')
      ?.querySelector<HTMLElement>('.my-page-route-nav a[aria-current="page"]') ?? null;
    favoriteFocusIdRef.current = complexId;
    selectFavoriteComplex({ parcelId: null, complexId });
    setIsExplorationOpen(true);
  }, [selectFavoriteComplex]);

  const focusMap = viewport.focusMap;
  useEffect(() => {
    const focusId = favoriteFocusIdRef.current;
    const complex = detail.complexDetail;
    if (
      focusId == null
      || complex?.complexId !== focusId
      || complex.latitude == null
      || complex.longitude == null
    ) return;
    favoriteFocusIdRef.current = null;
    focusMap(complex.latitude, complex.longitude, 4, SEARCH_FOCUS_DELTA);
  }, [detail.complexDetail, focusMap]);

  const closeMyPage = useCallback(() => {
    navigate('/');
    if (window.innerWidth <= 720) setIsExplorationOpen(false);
    queueMicrotask(() => explorationButtonRef.current?.focus());
  }, [navigate]);

  const handleChatOpenChange = useCallback((isOpen: boolean) => {
    setIsChatOpen(isOpen);
    if (isOpen && window.innerWidth <= 1279) setIsExplorationOpen(false);
    else if (!isOpen && window.innerWidth > 720) setIsExplorationOpen(true);
  }, []);

  const handleChatUiAction = useCallback((action: ChatAction) => {
    if (consumedMapActionIds.current.has(action.actionId)) return false;
    consumedMapActionIds.current.add(action.actionId);
    focusMap(
      action.center.lat,
      action.center.lng,
      action.level,
      SEARCH_FOCUS_DELTA,
    );
    setMapUiCommand({
      type: 'showNearbyCategory',
      actionId: action.actionId,
      category: action.category,
    });
    return true;
  }, [focusMap]);

  const handleMapUiCommandConsumed = useCallback((actionId: string) => {
    setMapUiCommand((current) => current?.actionId === actionId ? null : current);
  }, []);

  return (
    <main
      className="app-shell"
      data-chat-open={isChatOpen ? 'true' : 'false'}
      data-detail-open={detail.selectedComplex == null ? 'false' : 'true'}
      data-ui-surface="map-first"
    >
      <AppHeader
        chatConversationStore={chatConversationStore}
        chatUiContext={chatUiContext}
        onChatOpenChange={handleChatOpenChange}
        onUiAction={handleChatUiAction}
      />

      <div
        className="map-workspace"
        data-exploration-open={isWorkspacePanelOpen ? 'true' : 'false'}
        data-layout-region="map-workspace"
        data-sidebar-mode={workspacePanelMode}
      >
        <ExplorationPanel
          complexDetail={detail.complexDetail}
          complexSuggestions={search.complexSuggestions}
          detailError={detail.detailError}
          detailState={detail.detailState}
          favoriteError={favorite.favoriteError}
          favoriteState={favorite.favoriteState}
          favoriteLiveMessage={favorite.liveMessage}
          isOpen={isWorkspacePanelOpen && (!isMyPageRoute || detail.selectedComplex != null)}
          onCloseExploration={closeMobileExploration}
          onCloseDetail={closeDetail}
          onDismissDetail={dismissMobileDetail}
          onComplexSelect={detail.selectComplexSummary}
          onLoadMoreTrades={detail.loadMoreTrades}
          onLoadRootRegions={region.loadRootRegions}
          onRegionComplexSelect={region.handleRegionComplexSelect}
          onRegionSelect={region.handleRegionSelect}
          onRegionTrailSelect={region.handleRegionTrailSelect}
          onRetryDetail={detail.retryDetail}
          onRetryTrades={detail.retryTrades}
          onRetryTrend={detail.retryTrend}
          onFavoriteToggle={favorite.onFavoriteToggle}
          onRetryFavorite={favorite.onRetryFavorite}
          onRetryRegion={region.retryRegion}
          onRetrySearch={search.retrySearch}
          onSearchInputChange={search.handleSearchInputChange}
          onSearchResultSelect={search.handleSearchResultSelect}
          onSearchSubmit={search.handleSearchSubmit}
          onSuggestionSelect={search.handleSuggestionSelect}
          searchInputRef={searchInputRef}
          parcelComplexes={detail.parcelComplexes}
          parcelTrades={detail.parcelTrades}
          regionComplexes={region.regionComplexes}
          regionDetail={region.regionDetail}
          regionError={region.regionError}
          regionState={region.regionState}
          regionTrail={region.regionTrail}
          rootRegions={region.rootRegions}
          searchError={search.searchError}
          searchResults={search.searchResults}
          searchState={search.searchState}
          selectedComplex={detail.selectedComplex}
          sidebarMode={sidebarMode}
          tradeRows={detail.tradeRows}
          tradeError={detail.tradeError}
          tradeState={detail.tradeState}
          tradeTrend={detail.tradeTrend}
          trendError={detail.trendError}
          trendState={detail.trendState}
        />

        {isMyPageRoute ? (
          <MyPagePanel
            hidden={!isWorkspacePanelOpen || detail.selectedComplex != null}
            onClose={closeMyPage}
            onExplore={closeMyPage}
            onFavoriteSelect={handleFavoriteSelect}
          />
        ) : null}

        <div className="map-column" data-layout-region="map-column">
          <FilterPanel
            activeFilterCount={markerData.activeFilterCount}
            explorationButtonRef={explorationButtonRef}
            filters={markerData.markerFilters}
            onChange={markerData.setMarkerFilters}
            onOpenExploration={openMobileExploration}
            onReset={markerData.resetMarkerFilters}
          />
          <MapWorkspace
            appKey={kakaoMapAppKey}
            focusTarget={viewport.mapFocusTarget}
            initialLevel={initialMapLevel}
            markerError={markerData.markerError}
            markerState={markerData.markerState}
            markers={visibleMarkerData.markers}
            hiddenMarkerCount={visibleMarkerData.hiddenCount}
            selectedComplex={detail.selectedComplex}
            viewport={viewport.viewport}
            uiCommand={mapUiCommand}
            onComplexMarkerSelect={detail.selectComplexMarker}
            activeFilterCount={markerData.activeFilterCount}
            onFilterReset={markerData.resetMarkerFilters}
            onRegionMarkerSelect={handleRegionMarkerSelect}
            onRetryMarkers={markerData.retryMarkers}
            onViewportChange={viewport.handleViewportChange}
            onZoomIn={viewport.handleZoomIn}
            onZoomOut={viewport.handleZoomOut}
            onUiCommandConsumed={handleMapUiCommandConsumed}
          />
        </div>
      </div>
    </main>
  );
}

function isMyPagePath(pathname: string): boolean {
  return pathname === '/my' || pathname.startsWith('/my/');
}

function getConfiguredKakaoMapAppKey(): string {
  return import.meta.env.VITE_KAKAO_MAP_APP_KEY ?? '';
}
