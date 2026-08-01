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
import {
  isCancelledFailure,
  toRequestFailure,
  type RequestFailure,
} from '../../../shared/http/requestFailure';

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
  const [suggestionState, setSuggestionState] = useState<PanelRequestState>('idle');
  const [queryGuidance, setQueryGuidance] = useState<string | null>(null);
  const [searchError, setSearchError] = useState<RequestFailure | null>(null);
  const searchRequestSeq = useRef(0);
  const suggestionRequestSeq = useRef(0);
  const suggestionDebounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const searchAbortController = useRef<AbortController | null>(null);
  const suggestionAbortController = useRef<AbortController | null>(null);
  const lastSearchQuery = useRef('');

  useEffect(() => () => {
    clearSuggestionDebounceTimer();
    searchAbortController.current?.abort();
    suggestionAbortController.current?.abort();
  }, []);

  function clearSuggestionDebounceTimer() {
    if (suggestionDebounceTimer.current != null) {
      clearTimeout(suggestionDebounceTimer.current);
      suggestionDebounceTimer.current = null;
    }
  }

  function runComplexSearch(query: string) {
    searchAbortController.current?.abort();
    const requestSeq = searchRequestSeq.current + 1;
    searchRequestSeq.current = requestSeq;
    setSearchError(null);

    if (codePointLength(query) < 2) {
      setSearchResults([]);
      setSearchState('idle');
      setQueryGuidance(query.length === 0 ? null : '두 글자 이상 입력해 주세요');
      return;
    }

    const controller = new AbortController();
    searchAbortController.current = controller;
    lastSearchQuery.current = query;
    setQueryGuidance(null);
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
        const failure = toRequestFailure(error, {
          service: 'property-data',
          operation: 'complex-search',
        }, controller.signal);
        if (isCancelledFailure(failure)) return;
        setSearchState('error');
        setSearchError(failure);
      });
  }

  function handleSearchSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const value = new FormData(event.currentTarget).get('q');
    const query = typeof value === 'string' ? value.trim() : '';
    clearSuggestionDebounceTimer();
    suggestionRequestSeq.current += 1;
    suggestionAbortController.current?.abort();
    setComplexSuggestions([]);
    setSuggestionState('idle');
    runComplexSearch(query);
  }

  function handleSearchInputChange(value: string) {
    clearSuggestionDebounceTimer();
    const requestSeq = suggestionRequestSeq.current + 1;
    suggestionRequestSeq.current = requestSeq;
    suggestionAbortController.current?.abort();
    searchRequestSeq.current += 1;
    searchAbortController.current?.abort();
    const query = value.trim();

    setComplexSuggestions([]);
    setSuggestionState('idle');
    setSearchResults([]);
    setSearchState('idle');
    setSearchError(null);

    if (query.length === 0) {
      setQueryGuidance(null);
      return;
    }

    if (codePointLength(query) < 2) {
      setQueryGuidance('두 글자 이상 입력해 주세요');
      return;
    }

    setQueryGuidance(null);
    setSuggestionState('loading');
    suggestionDebounceTimer.current = setTimeout(() => {
      suggestionDebounceTimer.current = null;
      const suggestionController = new AbortController();
      suggestionAbortController.current = suggestionController;
      fetchComplexSuggestions(query, suggestionController.signal)
        .then((nextSuggestions) => {
          if (requestSeq === suggestionRequestSeq.current) {
            setComplexSuggestions(nextSuggestions);
            setSuggestionState(nextSuggestions.length === 0 ? 'empty' : 'ready');
          }
        })
        .catch(() => {
          if (requestSeq === suggestionRequestSeq.current) {
            setComplexSuggestions([]);
            setSuggestionState('empty');
          }
        });
    }, SEARCH_DEBOUNCE_MILLIS);
  }

  function handleSearchResultSelect(result: ComplexSearchResult) {
    clearSuggestionDebounceTimer();
    selectComplex({ parcelId: result.parcelId, complexId: result.complexId });
    if (hasDisplayCoordinate(result)) {
      focusMap(result.latitude, result.longitude, 4, SEARCH_FOCUS_DELTA);
    }
  }

  function handleSuggestionSelect(suggestion: ComplexSuggestion) {
    clearSuggestionDebounceTimer();
    selectComplex({ parcelId: suggestion.parcelId, complexId: suggestion.complexId });
    setComplexSuggestions([]);
    setSuggestionState('idle');
  }

  return {
    complexSuggestions,
    handleSearchInputChange,
    handleSearchResultSelect,
    handleSearchSubmit,
    handleSuggestionSelect,
    isSearchPanelActive:
      queryGuidance != null
      || suggestionState !== 'idle'
      || searchState !== 'idle'
      || searchResults.length > 0
      || complexSuggestions.length > 0,
    queryGuidance,
    retrySearch: () => runComplexSearch(lastSearchQuery.current),
    searchError,
    searchResults,
    searchState,
    suggestionState,
  };
}

function codePointLength(value: string): number {
  return Array.from(value).length;
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
