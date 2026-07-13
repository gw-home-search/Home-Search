import { type KeyboardEvent as ReactKeyboardEvent, useEffect, useRef } from 'react';

import type {
  NearbyPlaceCategory,
  NearbyPlaces,
} from './api/fetchNearbyPlaces';
import {
  NEARBY_PLACE_CATEGORIES,
  NEARBY_PLACE_CATEGORY_LABELS,
} from './api/fetchNearbyPlaces';
import type { NearbyPlaceRequestState } from './useNearbyPlaces';

type NearbyPlacePanelProps = {
  data: NearbyPlaces | null;
  error: string | null;
  selectedCategory: NearbyPlaceCategory;
  selectedPlaceId: string | null;
  state: NearbyPlaceRequestState;
  onCategoryChange: (category: NearbyPlaceCategory) => void;
  onClose: () => void;
  onPlaceSelect: (placeId: string) => void;
  onRetry: () => void;
};

export function NearbyPlacePanel({
  data,
  error,
  selectedCategory,
  selectedPlaceId,
  state,
  onCategoryChange,
  onClose,
  onPlaceSelect,
  onRetry,
}: NearbyPlacePanelProps) {
  const listRef = useRef<HTMLDivElement>(null);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const category = data?.categories.find((item) => item.category === selectedCategory) ?? null;

  const handleTabKeyDown = (event: ReactKeyboardEvent<HTMLButtonElement>, index: number) => {
    let nextIndex: number | null = null;
    if (event.key === 'ArrowRight') nextIndex = (index + 1) % NEARBY_PLACE_CATEGORIES.length;
    if (event.key === 'ArrowLeft') nextIndex = (index - 1 + NEARBY_PLACE_CATEGORIES.length) % NEARBY_PLACE_CATEGORIES.length;
    if (event.key === 'Home') nextIndex = 0;
    if (event.key === 'End') nextIndex = NEARBY_PLACE_CATEGORIES.length - 1;
    if (nextIndex == null) return;
    event.preventDefault();
    onCategoryChange(NEARBY_PLACE_CATEGORIES[nextIndex]);
    tabRefs.current[nextIndex]?.focus();
  };

  useEffect(() => {
    if (!selectedPlaceId) return;
    const selectedRow = Array.from(
      listRef.current?.querySelectorAll<HTMLElement>('[data-place-id]') ?? [],
    ).find((row) => row.dataset.placeId === selectedPlaceId);
    selectedRow?.scrollIntoView?.({ block: 'nearest' });
  }, [selectedPlaceId]);

  return (
    <section aria-label="주변 상권·생활시설" className="nearby-place-panel">
      <header className="nearby-place-header">
        <div>
          <strong>주변 상권·생활시설</strong>
          <span>반경 {data?.radiusMeters ?? 800}m · Kakao 장소 검색 기준</span>
        </div>
        <button type="button" aria-label="주변 상권 닫기" onClick={onClose}>×</button>
      </header>

      <div aria-label="상권 카테고리" className="nearby-place-tabs" role="tablist">
        {NEARBY_PLACE_CATEGORIES.map((categoryKey, index) => {
          const result = data?.categories.find((item) => item.category === categoryKey);
          const label = result?.label ?? NEARBY_PLACE_CATEGORY_LABELS[categoryKey];
          return (
            <button
              type="button"
              aria-controls={`nearby-place-panel-${categoryKey}`}
              aria-label={result ? `${label} ${result.matchedCount}개` : label}
              aria-selected={selectedCategory === categoryKey}
              id={`nearby-place-tab-${categoryKey}`}
              key={categoryKey}
              role="tab"
              tabIndex={selectedCategory === categoryKey ? 0 : -1}
              onClick={() => onCategoryChange(categoryKey)}
              onKeyDown={(event) => handleTabKeyDown(event, index)}
              ref={(element) => { tabRefs.current[index] = element; }}
            >
              {label}{result ? <> <span>{result.matchedCount}</span></> : null}
            </button>
          );
        })}
      </div>

      <div
        aria-labelledby={`nearby-place-tab-${selectedCategory}`}
        aria-live="polite"
        className="nearby-place-body"
        id={`nearby-place-panel-${selectedCategory}`}
        ref={listRef}
        role="tabpanel"
      >
        {state === 'loading' ? <p className="nearby-place-status">주변 장소를 불러오는 중입니다.</p> : null}
        {state === 'error' ? (
          <div className="nearby-place-status">
            <p>{error ?? '주변 상권 정보를 불러오지 못했습니다.'}</p>
            <button type="button" onClick={onRetry}>다시 시도</button>
          </div>
        ) : null}
        {state === 'empty' ? <p className="nearby-place-status">반경 안에서 검색된 장소가 없습니다.</p> : null}
        {state === 'ready' && category?.places.length === 0 ? (
          <p className="nearby-place-status">이 카테고리의 검색 결과가 없습니다.</p>
        ) : null}
        {state === 'ready' ? category?.places.map((place) => (
          <button
            type="button"
            aria-pressed={selectedPlaceId === place.placeId}
            className="nearby-place-row"
            data-place-id={place.placeId}
            key={place.placeId}
            onClick={() => onPlaceSelect(place.placeId)}
          >
            <span className="nearby-place-row-main">
              <strong>{place.name}</strong>
              <span>{place.roadAddress ?? place.address ?? '주소 정보 없음'}</span>
            </span>
            <span className="nearby-place-row-meta">
              <strong>{formatDistance(place.distanceMeters)}</strong>
              {place.phone ? <span>{place.phone}</span> : null}
            </span>
          </button>
        )) : null}
      </div>

      <footer className="nearby-place-footnote">
        검색 개수는 사업자 등록 전체가 아닌 Kakao 장소 검색 결과입니다.
      </footer>
    </section>
  );
}

function formatDistance(distanceMeters: number): string {
  return distanceMeters < 1_000
    ? `${Math.round(distanceMeters)}m`
    : `${(distanceMeters / 1_000).toFixed(2)}km`;
}
