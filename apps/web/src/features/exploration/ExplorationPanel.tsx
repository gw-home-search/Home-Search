import type { FormEventHandler } from 'react';

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
  onComplexSelect: (complex: ParcelComplexSummary | RegionComplexSummary) => void;
  onLoadMoreTrades: () => void;
  onLoadRootRegions: () => void;
  onRegionComplexSelect: (complex: RegionComplexSummary) => void;
  onRegionSelect: (region: RegionTrailItem) => void;
  onRetryDetail: () => void;
  onSearchInputChange: (value: string) => void;
  onSearchResultSelect: (result: ComplexSearchResult) => void;
  onSearchSubmit: FormEventHandler<HTMLFormElement>;
  onSuggestionSelect: (suggestion: ComplexSuggestion) => void;
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
  onComplexSelect,
  onLoadMoreTrades,
  onLoadRootRegions,
  onRegionComplexSelect,
  onRegionSelect,
  onRetryDetail,
  onSearchInputChange,
  onSearchResultSelect,
  onSearchSubmit,
  onSuggestionSelect,
}: ExplorationPanelProps) {
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
        <span>{explorationSummaryLabel(searchResults.length, regionComplexes.length)}</span>
      </div>

      <form
        aria-label="단지 검색"
        className="search-panel exploration-search-panel"
        hidden={sidebarMode === 'detail'}
        onSubmit={onSearchSubmit}
      >
        <label>
          <span>단지</span>
          <input
            aria-label="단지 검색"
            name="q"
            onInput={(event) => {
              onSearchInputChange(event.currentTarget.value);
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
          onBack={onCloseDetail}
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
                    onSearchResultSelect(result);
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
                    onSuggestionSelect(suggestion);
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
          <button type="button" aria-label="지역 처음으로" onClick={onLoadRootRegions}>
            시도 선택
          </button>
          {regionTrail.map((region) => (
            <span key={region.id}>{region.name}</span>
          ))}
        </nav>
        <div className="region-step-summary">
          <p>{regionStepLabel(regionTrail.length)}</p>
          <button type="button" aria-label="상위 지역 불러오기" onClick={onLoadRootRegions}>
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
                    onRegionSelect(region);
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
                    onRegionComplexSelect(complex);
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
  );
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

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
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
