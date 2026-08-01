import type { FormEventHandler, ReactNode, Ref } from 'react';

import { FeatureErrorBoundary } from '../../shared/FeatureErrorBoundary';
import type { UserFeedbackId } from '../../shared/feedback/feedbackCatalog';
import type { RequestFailure } from '../../shared/http/requestFailure';
import { DetailSidebar } from '../complex-detail/DetailSidebar';
import type { ComplexDetail } from '../complex-detail/api/fetchComplexDetail';
import type { ParcelComplexSummary } from '../complex-detail/api/fetchParcelComplexes';
import type { ParcelTrades, TradeItem } from '../complex-detail/api/fetchParcelTrades';
import type { TradeTrendPoint } from '../complex-detail/api/fetchTradeTrend';
import type { TradeAreas } from '../complex-detail/api/fetchTradeAreas';
import type { FavoriteState } from '../favorites/favoriteTypes';
import type {
  RegionComplexSummary,
  RegionDetail,
  RegionSummary,
} from '../region/api/fetchRegions';
import type { ComplexSearchResult } from '../search/api/fetchComplexSearchResults';
import type { ComplexSuggestion } from '../search/api/fetchComplexSuggestions';
import { RegionExplorerSection } from './RegionExplorerSection';
import { SearchResultsSection } from './SearchResultsSection';
import { SearchToolbar } from './SearchToolbar';

type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';
type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';
type SidebarMode = 'region' | 'search' | 'insight' | 'news' | 'detail';
type ComplexSelection = { parcelId: number | null; complexId: number | null };
type RegionTrailItem = { id: number; name: string };

type ExplorationPanelProps = {
  complexDetail: ComplexDetail | null;
  complexSuggestions: ComplexSuggestion[];
  detailError: RequestFailure | null;
  detailState: DetailRequestState;
  favoriteError: UserFeedbackId | null;
  favoriteLiveMessage: string;
  favoriteState: FavoriteState;
  insightContent: ReactNode;
  newsContent: ReactNode;
  isOpen: boolean;
  modeNavigation: ReactNode;
  parcelComplexes: ParcelComplexSummary[];
  parcelTrades: ParcelTrades | null;
  regionComplexes: RegionComplexSummary[];
  regionDetail: RegionDetail | null;
  regionError: RequestFailure | null;
  regionState: PanelRequestState;
  regionTrail: RegionTrailItem[];
  rootRegions: RegionSummary[];
  queryGuidance: string | null;
  searchError: RequestFailure | null;
  searchResults: ComplexSearchResult[];
  searchState: PanelRequestState;
  suggestionState: PanelRequestState;
  selectedComplex: ComplexSelection | null;
  sidebarMode: SidebarMode;
  tradeRows: TradeItem[];
  tradeError: RequestFailure | null;
  tradeMoreState: 'idle' | 'loading' | 'error';
  tradeState: DetailRequestState;
  tradeTrend: TradeTrendPoint[];
  tradeAreas: TradeAreas | null;
  selectedExclArea: number | null;
  areaError: RequestFailure | null;
  areaState: DetailRequestState;
  trendError: RequestFailure | null;
  trendState: DetailRequestState;
  onCloseDetail: () => void;
  onDismissDetail: () => void;
  onCloseExploration: () => void;
  onComplexSelect: (complex: ParcelComplexSummary | RegionComplexSummary) => void;
  onLoadMoreTrades: () => void;
  onLoadRootRegions: () => void;
  onRegionComplexSelect: (complex: RegionComplexSummary) => void;
  onRegionSelect: (region: RegionTrailItem) => void;
  onRegionTrailSelect: (region: RegionTrailItem, index: number) => void;
  onRetryDetail: () => void;
  onRetryTrades: () => void;
  onRetryTrend: () => void;
  onRetryTradeAreas: () => void;
  onExclAreaChange: (exclArea: number) => void;
  onFavoriteToggle: (trigger?: HTMLElement) => void;
  onRetryFavorite: () => void;
  onRetryRegion: () => void;
  onRetrySearch: () => void;
  onSearchInputChange: (value: string) => void;
  onSearchResultSelect: (result: ComplexSearchResult) => void;
  onSearchSubmit: FormEventHandler<HTMLFormElement>;
  onSuggestionSelect: (suggestion: ComplexSuggestion) => void;
  searchInputRef?: Ref<HTMLInputElement>;
};

export function ExplorationPanel(props: ExplorationPanelProps) {
  const { sidebarMode } = props;

  return (
    <section
      id="exploration-panel"
      aria-label="탐색 패널"
      aria-hidden={!props.isOpen}
      className="exploration-panel"
      data-collapsed={props.isOpen ? 'false' : 'true'}
      data-sidebar-mode={sidebarMode}
      data-ui-layer="exploration-panel"
      hidden={!props.isOpen}
    >
      <SearchToolbar
        hidden={sidebarMode === 'detail'}
        inputRef={props.searchInputRef}
        onClose={props.onCloseExploration}
        onInputChange={props.onSearchInputChange}
        onSubmit={props.onSearchSubmit}
      />
      <div hidden={sidebarMode === 'detail'}>{props.modeNavigation}</div>
      {props.selectedComplex == null ? null : (
        <FeatureErrorBoundary feature="complex-detail">
          <DetailSidebar
            complexDetail={props.complexDetail}
            detailError={props.detailError}
            detailState={props.detailState}
            favoriteError={props.favoriteError}
            favoriteLiveMessage={props.favoriteLiveMessage}
            favoriteState={props.favoriteState}
            onBack={props.onCloseDetail}
            onClose={props.onDismissDetail}
            onComplexSelect={props.onComplexSelect}
            onRetryDetail={props.onRetryDetail}
            onRetryTrades={props.onRetryTrades}
            onRetryTrend={props.onRetryTrend}
            onFavoriteToggle={props.onFavoriteToggle}
            onRetryFavorite={props.onRetryFavorite}
            onLoadMoreTrades={props.onLoadMoreTrades}
            parcelComplexes={props.parcelComplexes}
            parcelTrades={props.parcelTrades}
            tradeTrend={props.tradeTrend}
            tradeAreas={props.tradeAreas}
            selectedExclArea={props.selectedExclArea}
            areaError={props.areaError}
            areaState={props.areaState}
            tradeRows={props.tradeRows}
            tradeError={props.tradeError}
            tradeMoreState={props.tradeMoreState}
            tradeState={props.tradeState}
            trendError={props.trendError}
            trendState={props.trendState}
            onRetryTradeAreas={props.onRetryTradeAreas}
            onExclAreaChange={props.onExclAreaChange}
            selection={props.selectedComplex}
          />
        </FeatureErrorBoundary>
      )}
      <SearchResultsSection
        complexSuggestions={props.complexSuggestions}
        hidden={sidebarMode !== 'search'}
        onResultSelect={props.onSearchResultSelect}
        onRetry={props.onRetrySearch}
        onSuggestionSelect={props.onSuggestionSelect}
        queryGuidance={props.queryGuidance}
        searchError={props.searchError}
        searchResults={props.searchResults}
        searchState={props.searchState}
        suggestionState={props.suggestionState}
      />
      <RegionExplorerSection
        hidden={sidebarMode !== 'region'}
        onComplexSelect={props.onRegionComplexSelect}
        onLoadRootRegions={props.onLoadRootRegions}
        onRegionSelect={props.onRegionSelect}
        onRegionTrailSelect={props.onRegionTrailSelect}
        onRetry={props.onRetryRegion}
        regionComplexes={props.regionComplexes}
        regionDetail={props.regionDetail}
        regionError={props.regionError}
        regionState={props.regionState}
        regionTrail={props.regionTrail}
        rootRegions={props.rootRegions}
      />
      {props.insightContent}
      {props.newsContent}
    </section>
  );
}
