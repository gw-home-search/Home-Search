import { useEffect, useRef, useState, type FormEvent } from 'react';

import type { ComplexSelection, PanelRequestState } from '../../../app/mapAppTypes';
import {
  fetchComplexSuggestions,
  type ComplexSuggestion,
} from '../api/fetchComplexSuggestions';
import {
  fetchComplexSearchResults,
  type ComplexSearchResult,
} from '../api/fetchComplexSearchResults';

const SEARCH_DEBOUNCE_MILLIS = 300;
export const SEARCH_FOCUS_DELTA = 0.01;

export function useComplexSearch({
  focusMap,
  selectComplex,
}: {
  focusMap: (lat: number, lng: number, level: number, delta: number) => void;
  selectComplex: (selection: ComplexSelection) => void;
}) {
  const [searchResults, setSearchResults] = useState<ComplexSearchResult[]>([]);
  const [complexSuggestions, setComplexSuggestions] = useState<ComplexSuggestion[]>([]);
  const [searchState, setSearchState] = useState<PanelRequestState>('idle');
  const [searchError, setSearchError] = useState<string | null>(null);
  const searchRequestSeq = useRef(0);
  const suggestionRequestSeq = useRef(0);
  const searchDebounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchAbortController = useRef<AbortController | null>(null);
  const suggestionAbortController = useRef<AbortController | null>(null);
  const lastSearchQuery = useRef('');

  useEffect(() => () => {
    clearSearchDebounceTimer();
    searchAbortController.current?.abort();
    suggestionAbortController.current?.abort();
  }, []);

  function clearSearchDebounceTimer() {
    if (searchDebounceTimer.current != null) {
      clearTimeout(searchDebounceTimer.current);
      searchDebounceTimer.current = null;
    }
  }

  function runComplexSearch(query: string) {
    searchAbortController.current?.abort();
    const requestSeq = searchRequestSeq.current + 1;
    searchRequestSeq.current = requestSeq;
    setSearchError(null);

    if (query.length === 0) {
      setSearchResults([]);
      setSearchState('idle');
      return;
    }

    const controller = new AbortController();
    searchAbortController.current = controller;
    lastSearchQuery.current = query;
    setSearchState('loading');
    fetchComplexSearchResults(query, controller.signal)
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

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = new FormData(event.currentTarget).get('q');
    const query = typeof value === 'string' ? value.trim() : '';
    clearSearchDebounceTimer();
    runComplexSearch(query);
  }

  function handleSearchInputChange(value: string) {
    clearSearchDebounceTimer();
    const requestSeq = suggestionRequestSeq.current + 1;
    suggestionRequestSeq.current = requestSeq;
    suggestionAbortController.current?.abort();
    const query = value.trim();

    if (query.length === 0) {
      setComplexSuggestions([]);
      setSearchResults([]);
      setSearchState('idle');
      setSearchError(null);
      searchRequestSeq.current += 1;
      searchAbortController.current?.abort();
      return;
    }

    const suggestionController = new AbortController();
    suggestionAbortController.current = suggestionController;
    setSearchState('loading');
    setSearchError(null);
    fetchComplexSuggestions(query, suggestionController.signal)
      .then((nextSuggestions) => {
        if (requestSeq === suggestionRequestSeq.current) {
          setComplexSuggestions(nextSuggestions);
        }
      })
      .catch(() => {
        if (requestSeq === suggestionRequestSeq.current) {
          setComplexSuggestions([]);
        }
      });

    searchDebounceTimer.current = setTimeout(() => {
      searchDebounceTimer.current = null;
      runComplexSearch(query);
    }, SEARCH_DEBOUNCE_MILLIS);
  }

  function handleSearchResultSelect(result: ComplexSearchResult) {
    clearSearchDebounceTimer();
    selectComplex({ parcelId: result.parcelId, complexId: result.complexId });
    if (hasDisplayCoordinate(result)) {
      focusMap(result.latitude, result.longitude, 4, SEARCH_FOCUS_DELTA);
    }
  }

  function handleSuggestionSelect(suggestion: ComplexSuggestion) {
    clearSearchDebounceTimer();
    selectComplex({ parcelId: suggestion.parcelId, complexId: suggestion.complexId });
    setComplexSuggestions([]);
  }

  return {
    complexSuggestions,
    handleSearchInputChange,
    handleSearchResultSelect,
    handleSearchSubmit,
    handleSuggestionSelect,
    isSearchPanelActive:
      searchState !== 'idle' || searchResults.length > 0 || complexSuggestions.length > 0,
    retrySearch: () => runComplexSearch(lastSearchQuery.current),
    searchError,
    searchResults,
    searchState,
  };
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
