import { RequestStateNotice } from '../../shared/RequestStateNotice';
import type { ComplexSuggestion } from '../search/api/fetchComplexSuggestions';
import type { ComplexSearchResult } from '../search/api/fetchComplexSearchResults';
import { ComplexList } from './ComplexList';

type PanelRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

export function SearchResultsSection({
  complexSuggestions,
  hidden,
  onResultSelect,
  onRetry,
  onSuggestionSelect,
  searchError,
  searchResults,
  searchState,
}: {
  complexSuggestions: ComplexSuggestion[];
  hidden: boolean;
  onResultSelect: (result: ComplexSearchResult) => void;
  onRetry: () => void;
  onSuggestionSelect: (suggestion: ComplexSuggestion) => void;
  searchError: string | null;
  searchResults: ComplexSearchResult[];
  searchState: PanelRequestState;
}) {
  return (
    <section id="exploration-panel-search" aria-label="검색 결과 패널" className="panel-section" data-api-flow="search" hidden={hidden}>
      <div className="panel-section-header">
        <p>검색 결과</p>
        {searchResults.length > 0 ? <span>{searchResults.length.toLocaleString()}개</span> : null}
      </div>
      <RequestStateNotice state={searchState} loadingMessage="단지를 검색하는 중" emptyMessage="검색 결과가 없습니다" errorMessage="검색 결과를 불러오지 못했어요" technicalError={searchError} onRetry={onRetry} />
      {searchResults.length > 0 ? (
        <ComplexList ariaLabel="검색 결과" items={searchResults.map((result) => ({
          id: result.complexId,
          ariaLabel: `검색 결과 선택 ${result.complexName}`,
          name: result.complexName,
          address: formatAddress(result.address),
          onSelect: () => onResultSelect(result),
        }))} />
      ) : null}
      {complexSuggestions.length > 0 ? (
        <>
          <div className="panel-section-header panel-subsection-header"><p>제안</p><span>{complexSuggestions.length.toLocaleString()}개</span></div>
          <ComplexList ariaLabel="검색 제안" items={complexSuggestions.map((suggestion) => ({
            id: suggestion.complexId,
            ariaLabel: `검색 제안 선택 ${suggestion.complexName}`,
            name: suggestion.complexName,
            address: formatAddress(suggestion.address),
            onSelect: () => onSuggestionSelect(suggestion),
          }))} />
        </>
      ) : null}
    </section>
  );
}

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
}
