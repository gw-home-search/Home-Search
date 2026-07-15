import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useComplexDetail } from '../features/complex-detail/hooks/useComplexDetail';
import { ExplorationPanel } from '../features/exploration/ExplorationPanel';
import { FilterPanel } from '../features/filters/FilterPanel';
import { MapWorkspace } from '../features/map/MapWorkspace';
import { useMapMarkers } from '../features/map/hooks/useMapMarkers';
import { useMapViewport } from '../features/map/hooks/useMapViewport';
import { useRegionExplorer } from '../features/region/hooks/useRegionExplorer';
import { useComplexSearch } from '../features/search/hooks/useComplexSearch';
import type { RegionMapMarker, SidebarMode } from './mapAppTypes';
import { declutterComplexMarkers } from '../features/map/markerViewModel';
import { AppHeader } from './AppHeader';
import { useFavoriteComplex } from '../features/favorites/hooks/useFavoriteComplex';

export type MapAppProps = {
  initialMapLevel?: number;
  initialRegionLoad?: boolean;
  kakaoMapAppKey?: string;
};

export function MapApp({
  initialMapLevel = 12,
  initialRegionLoad = true,
  kakaoMapAppKey = getConfiguredKakaoMapAppKey(),
}: MapAppProps) {
  const [isExplorationOpen, setIsExplorationOpen] = useState(() => window.innerWidth > 720);
  const explorationButtonRef = useRef<HTMLButtonElement>(null);
  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    function keepDesktopRailOpen() {
      if (window.innerWidth > 720) setIsExplorationOpen(true);
    }
    window.addEventListener('resize', keepDesktopRailOpen);
    return () => window.removeEventListener('resize', keepDesktopRailOpen);
  }, []);
  const viewport = useMapViewport(initialMapLevel);
  const markerData = useMapMarkers(viewport.viewport);
  const detail = useComplexDetail();
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
  const sidebarMode: SidebarMode = detail.selectedComplex == null
    ? search.isSearchPanelActive ? 'search' : 'region'
    : 'detail';
  const visibleMarkerData = useMemo(() => {
    if (markerData.markers?.kind !== 'complex') return { markers: markerData.markers, hiddenCount: 0 };
    const result = declutterComplexMarkers(markerData.markers.markers, detail.selectedComplex, viewport.viewport.level);
    return { markers: { ...markerData.markers, markers: result.markers }, hiddenCount: result.hiddenCount };
  }, [detail.selectedComplex, markerData.markers, viewport.viewport.level]);

  const handleRegionMarkerSelect = useCallback((marker: RegionMapMarker) => {
    setIsExplorationOpen(true);
    region.handleMapRegionSelect(marker, viewport.viewport.level);
  }, [region.handleMapRegionSelect, viewport.viewport.level]);

  const closeMobileExploration = useCallback(() => {
    setIsExplorationOpen(false);
    queueMicrotask(() => explorationButtonRef.current?.focus());
  }, []);

  const openMobileExploration = useCallback(() => {
    setIsExplorationOpen(true);
    queueMicrotask(() => searchInputRef.current?.focus());
  }, []);

  const dismissMobileDetail = useCallback(() => {
    detail.closeDetail();
    setIsExplorationOpen(false);
    queueMicrotask(() => explorationButtonRef.current?.focus());
  }, [detail.closeDetail]);

  return (
    <main
      className="app-shell"
      data-detail-open={detail.selectedComplex == null ? 'false' : 'true'}
      data-ui-surface="map-first"
    >
      <AppHeader />

      <div
        className="map-workspace"
        data-exploration-open={isExplorationOpen ? 'true' : 'false'}
        data-layout-region="map-workspace"
        data-sidebar-mode={sidebarMode}
      >
        <ExplorationPanel
          complexDetail={detail.complexDetail}
          complexSuggestions={search.complexSuggestions}
          detailError={detail.detailError}
          detailState={detail.detailState}
          favoriteError={favorite.favoriteError}
          favoriteState={favorite.favoriteState}
          favoriteLiveMessage={favorite.liveMessage}
          isOpen={isExplorationOpen}
          onCloseExploration={closeMobileExploration}
          onCloseDetail={detail.closeDetail}
          onDismissDetail={dismissMobileDetail}
          onComplexSelect={detail.selectComplexSummary}
          onLoadMoreTrades={detail.loadMoreTrades}
          onLoadRootRegions={region.loadRootRegions}
          onRegionComplexSelect={region.handleRegionComplexSelect}
          onRegionSelect={region.handleRegionSelect}
          onRegionTrailSelect={region.handleRegionTrailSelect}
          onRetryDetail={detail.retryDetail}
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
          tradeTrend={detail.tradeTrend}
        />

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
            onComplexMarkerSelect={detail.selectComplexMarker}
            activeFilterCount={markerData.activeFilterCount}
            onFilterReset={markerData.resetMarkerFilters}
            onRegionMarkerSelect={handleRegionMarkerSelect}
            onRetryMarkers={markerData.retryMarkers}
            onViewportChange={viewport.handleViewportChange}
            onZoomIn={viewport.handleZoomIn}
            onZoomOut={viewport.handleZoomOut}
          />
        </div>
      </div>
    </main>
  );
}

function getConfiguredKakaoMapAppKey(): string {
  return import.meta.env.VITE_KAKAO_MAP_APP_KEY ?? '';
}
