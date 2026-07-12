import type { FormEventHandler, Ref } from 'react';

import {
  DetailSidebar,
} from '../complex-detail/DetailSidebar';
import type {
  ComplexDetail,
} from '../complex-detail/api/fetchComplexDetail';
import type {
  ParcelComplexSummary,
} from '../complex-detail/api/fetchParcelComplexes';
import type {
  ParcelTrades,
  TradeItem,
} from '../complex-detail/api/fetchParcelTrades';
import type {
  TradeTrendPoint,
} from '../complex-detail/api/fetchTradeTrend';
import type {
  RegionComplexSummary,
  RegionDetail,
  RegionSummary,
} from '../region/api/fetchRegions';
import type {
  ComplexSuggestion,
} from '../search/api/fetchComplexSuggestions';
import type {
  ComplexSearchResult,
} from '../search/api/fetchComplexSearchResults';
import { RequestStateNotice } from '../../shared/RequestStateNotice';
import { CheckIcon, ChevronDownIcon, CloseIcon, SearchIcon } from '../../shared/icons';

type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';
type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';
type SidebarMode = 'region' | 'search' | 'detail';

type ComplexSelection = {
  parcelId: number | null;
  complexId: number | null;
};

type RegionTrailItem = {
  id: number;
  name: string;
};

type ExplorationPanelProps = {
  complexDetail: ComplexDetail | null;
  complexSuggestions: ComplexSuggestion[];
  detailError: string | null;
  detailState: DetailRequestState;
  isOpen: boolean;
  parcelComplexes: ParcelComplexSummary[];
  parcelTrades: ParcelTrades | null;
  regionComplexes: RegionComplexSummary[];
  regionDetail: RegionDetail | null;
  regionError: string | null;
  regionState: PanelRequestState;
  regionTrail: RegionTrailItem[];
  rootRegions: RegionSummary[];
  searchError: string | null;
  searchResults: ComplexSearchResult[];
  searchState: PanelRequestState;
  selectedComplex: ComplexSelection | null;
  sidebarMode: SidebarMode;
  tradeRows: TradeItem[];
  tradeTrend: TradeTrendPoint[];
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
  onRetryRegion: () => void;
  onRetrySearch: () => void;
  onSearchInputChange: (value: string) => void;
  onSearchResultSelect: (result: ComplexSearchResult) => void;
  onSearchSubmit: FormEventHandler<HTMLFormElement>;
  onSuggestionSelect: (suggestion: ComplexSuggestion) => void;
  searchInputRef?: Ref<HTMLInputElement>;
};

export function ExplorationPanel({
  complexDetail,
  complexSuggestions,
  detailError,
  detailState,
  isOpen,
  parcelComplexes,
  parcelTrades,
  regionComplexes,
  regionDetail,
  regionError,
  regionState,
  regionTrail,
  rootRegions,
  searchError,
  searchResults,
  searchState,
  selectedComplex,
  sidebarMode,
  tradeRows,
  tradeTrend,
  onCloseDetail,
  onDismissDetail,
  onCloseExploration,
  onComplexSelect,
  onLoadMoreTrades,
  onLoadRootRegions,
  onRegionComplexSelect,
  onRegionSelect,
  onRegionTrailSelect,
  onRetryDetail,
  onRetryRegion,
  onRetrySearch,
  onSearchInputChange,
  onSearchResultSelect,
  onSearchSubmit,
  onSuggestionSelect,
  searchInputRef,
}: ExplorationPanelProps) {
  const showRegionComplexes = regionDetail?.children.length === 0 && regionComplexes.length > 0;

  return (
    <section
      id="exploration-panel"
      aria-label="탐색 패널"
      aria-hidden={!isOpen}
      className="exploration-panel"
      data-collapsed={isOpen ? 'false' : 'true'}
      data-sidebar-mode={sidebarMode}
      data-ui-layer="exploration-panel"
      hidden={!isOpen}
    >
      <div className="exploration-panel-header" hidden={sidebarMode === 'detail'}>
        <p>탐색</p>
        <button type="button" aria-label="검색 패널 닫기" className="exploration-mobile-close" onClick={onCloseExploration}>
          <CloseIcon aria-hidden="true" />
        </button>
      </div>

      <form
        aria-label="단지 검색"
        className="search-panel exploration-search-panel"
        hidden={sidebarMode === 'detail'}
        onSubmit={onSearchSubmit}
      >
        <label className="exploration-search-field">
          <span>단지</span>
          <SearchIcon aria-hidden="true" />
          <input
            ref={searchInputRef}
            aria-label="단지 검색"
            name="q"
            onInput={(event) => {
              onSearchInputChange(event.currentTarget.value);
            }}
            placeholder="아파트명을 검색해보세요."
            type="search"
          />
        </label>
        <button type="submit" aria-label="단지 검색 실행" className="exploration-search-submit">
          검색
        </button>
      </form>

      {selectedComplex == null ? null : (
        <DetailSidebar
          complexDetail={complexDetail}
          detailError={detailError}
          detailState={detailState}
          onBack={onCloseDetail}
          onClose={onDismissDetail}
          onComplexSelect={onComplexSelect}
          onRetryDetail={onRetryDetail}
          onLoadMoreTrades={onLoadMoreTrades}
          parcelComplexes={parcelComplexes}
          parcelTrades={parcelTrades}
          tradeTrend={tradeTrend}
          tradeRows={tradeRows}
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
          {searchResults.length > 0 ? <span>{searchResults.length.toLocaleString()}개</span> : null}
        </div>
        <RequestStateNotice
          state={searchState}
          loadingMessage="단지를 검색하는 중"
          emptyMessage="검색 결과가 없습니다"
          errorMessage="검색 결과를 불러오지 못했어요"
          technicalError={searchError}
          onRetry={onRetrySearch}
        />

        {searchResults.length > 0 ? (
          <ul aria-label="검색 결과" className="panel-list panel-list-strong">
            {searchResults.map((result) => (
              <li key={result.complexId}>
                <button
                  type="button"
                  aria-label={`검색 결과 선택 ${result.complexName}`}
                  onClick={() => {
                    onSearchResultSelect(result);
                  }}
                >
                  <span className="panel-list-title">{result.complexName}</span>
                  <span className="panel-list-meta">{formatAddress(result.address)}</span>
                </button>
              </li>
            ))}
          </ul>
        ) : null}

        {complexSuggestions.length > 0 ? (
          <>
          <div className="panel-section-header panel-subsection-header"><p>제안</p><span>{complexSuggestions.length.toLocaleString()}개</span></div>
          <ul aria-label="검색 제안" className="panel-list">
            {complexSuggestions.map((suggestion) => (
              <li key={suggestion.complexId}>
                <button
                  type="button"
                  aria-label={`검색 제안 선택 ${suggestion.complexName}`}
                  onClick={() => {
                    onSuggestionSelect(suggestion);
                  }}
                >
                  <span className="panel-list-title">{suggestion.complexName}</span>
                  <span className="panel-list-meta">{formatAddress(suggestion.address)}</span>
                </button>
              </li>
            ))}
          </ul></>
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
        </div>
        <nav aria-label="지역 단계" className="region-breadcrumb">
          <button
            type="button"
            aria-current={regionTrail.length === 0 ? 'page' : undefined}
            aria-label="지역 처음으로"
            className="region-breadcrumb-link region-breadcrumb-root"
            onClick={onLoadRootRegions}
          >
            시도 선택
          </button>
          {regionTrail.map((region, index) => (
            <span className="region-breadcrumb-step" key={region.id}>
              <ChevronDownIcon aria-hidden="true" />
              <button
                type="button"
                aria-current={index === regionTrail.length - 1 ? 'page' : undefined}
                aria-label={`지역 단계 이동 ${region.name}`}
                className="region-breadcrumb-link"
                onClick={() => onRegionTrailSelect(region, index)}
              >
                {region.name}
              </button>
            </span>
          ))}
        </nav>
        <RequestStateNotice
          state={regionState}
          loadingMessage="지역을 불러오는 중"
          emptyMessage="표시할 지역이 없습니다"
          errorMessage="지역 정보를 불러오지 못했어요"
          technicalError={regionError}
          onRetry={onRetryRegion}
        />

        {rootRegions.length > 0 ? (
          <ul aria-label="지역 탐색" className="panel-list region-grid-list">
            {rootRegions.map((region) => (
              <li key={region.id}>
                <button
                  type="button"
                  aria-label={`지역 이동 ${region.name}`}
                  aria-pressed={regionTrail.some((item) => item.id === region.id)}
                  onClick={() => {
                    onRegionSelect(region);
                  }}
                >
                  <span className="region-tile-label">{region.name}</span>
                  {regionTrail.some((item) => item.id === region.id) ? <CheckIcon aria-hidden="true" /> : null}
                </button>
              </li>
            ))}
          </ul>
        ) : null}

        {showRegionComplexes ? (
          <section aria-label="선택한 읍면동 단지" className="region-complex-section">
            <div className="panel-section-header region-complex-heading">
              <p>단지</p>
              <span aria-label={`${regionComplexes.length.toLocaleString()}개 단지`}>
                {regionComplexes.length.toLocaleString()}
              </span>
            </div>
            <ul aria-label="지역 단지 목록" className="panel-list region-complex-list">
              {regionComplexes.map((complex) => {
                const approvalYear = complex.useDate?.match(/^\d{4}/)?.[0];
                return (
                  <li key={complex.complexId}>
                    <button
                      type="button"
                      className="region-complex-card"
                      aria-label={`지역 단지 선택 ${complex.complexName}`}
                      onClick={() => {
                        onRegionComplexSelect(complex);
                      }}
                    >
                      <span className="region-complex-main">
                        <span className="region-complex-name">{complex.complexName}</span>
                        <span className="region-complex-context">
                          <span className="region-complex-address">{formatAddress(complex.address)}</span>
                          {approvalYear == null ? null : (
                            <span className="region-complex-approval">· {approvalYear}년 승인</span>
                          )}
                        </span>
                      </span>
                      {complex.unitCnt == null && complex.dongCnt == null ? null : (
                        <span className="region-complex-stats" aria-label="단지 규모">
                          {complex.unitCnt == null ? null : (
                            <strong className="region-complex-unit">{complex.unitCnt.toLocaleString()}세대</strong>
                          )}
                          {complex.dongCnt == null ? null : (
                            <span className="region-complex-dong">{complex.dongCnt.toLocaleString()}동</span>
                          )}
                        </span>
                      )}
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ) : null}
      </section>
    </section>
  );
}

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
}
