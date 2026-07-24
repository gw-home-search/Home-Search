import { useCallback, useEffect, useRef, useState } from 'react';

import type { MapViewport } from '../../app/mapAppTypes';
import { MAX_COMPLEX_MARKER_LEVEL } from '../map/api/fetchMapMarkers';
import {
  fetchViewportNearbyPlaces,
  type ViewportNearbyPlaces,
} from './api/fetchViewportNearbyPlaces';
import { NEARBY_PLACE_CATEGORIES, type NearbyPlaceCategory } from './api/fetchNearbyPlaces';
import type { UserFeedbackId } from '../../shared/feedback/feedbackCatalog';

export type ViewportNearbyPlaceState = 'idle' | 'zoom-required' | 'loading' | 'ready' | 'partial' | 'empty' | 'error';

const CACHE_TTL_MS = 5 * 60 * 1_000;
const CACHE_MAX_ENTRIES = 24;
const DEBOUNCE_MS = 400;

type CacheEntry = { data: ViewportNearbyPlaces; expiresAt: number };
type InFlightEntry = {
  category: NearbyPlaceCategory;
  controller: AbortController;
  promise: Promise<ViewportNearbyPlaces>;
  viewportKey: string;
};

export function useViewportNearbyPlaces(
  viewport: MapViewport,
  categories: readonly NearbyPlaceCategory[],
  enabled: boolean,
) {
  const [data, setData] = useState<ViewportNearbyPlaces[]>([]);
  const [state, setState] = useState<ViewportNearbyPlaceState>('idle');
  const [error, setError] = useState<UserFeedbackId | null>(null);
  const [retrySeq, setRetrySeq] = useState(0);
  const dataRef = useRef<ViewportNearbyPlaces[]>([]);
  const cacheRef = useRef(new Map<string, CacheEntry>());
  const inFlightRef = useRef(new Map<string, InFlightEntry>());
  const sequenceRef = useRef(0);
  const selectedCategories = normalizeCategories(categories);
  const selectedCategoryKey = selectedCategories.join(',');
  const viewportKey = viewportCacheKey(viewport);

  useEffect(() => () => {
    inFlightRef.current.forEach((entry) => entry.controller.abort());
    inFlightRef.current.clear();
  }, [enabled, viewportKey]);

  useEffect(() => {
    const sequence = ++sequenceRef.current;
    if (!enabled || selectedCategories.length === 0) {
      updateData(dataRef, setData, []);
      setState('idle');
      setError(null);
      return undefined;
    }
    if (viewport.level > MAX_COMPLEX_MARKER_LEVEL) {
      updateData(dataRef, setData, []);
      setState('zoom-required');
      setError(null);
      return undefined;
    }

    const retainedData = dataRef.current.filter((item) =>
      selectedCategories.includes(item.category.category));
    if (retainedData.length !== dataRef.current.length) {
      updateData(dataRef, setData, retainedData);
    }

    inFlightRef.current.forEach((entry, key) => {
      if (entry.viewportKey === viewportKey && !selectedCategories.includes(entry.category)) {
        entry.controller.abort();
        inFlightRef.current.delete(key);
      }
    });

    const cachedByCategory = new Map<NearbyPlaceCategory, ViewportNearbyPlaces>();
    const previousByCategory = new Map(
      dataRef.current
        .filter((item) => selectedCategories.includes(item.category.category))
        .map((item) => [item.category.category, item]),
    );
    const missingCategories: NearbyPlaceCategory[] = [];
    selectedCategories.forEach((category) => {
      const key = cacheKey(viewport, category);
      const cached = cacheRef.current.get(key);
      if (cached && cached.expiresAt > Date.now()) {
        cacheRef.current.delete(key);
        cacheRef.current.set(key, cached);
        cachedByCategory.set(category, cached.data);
      } else {
        if (cached) cacheRef.current.delete(key);
        missingCategories.push(category);
      }
    });
    if (missingCategories.length === 0) {
      const cachedData = selectedCategories.map((category) => cachedByCategory.get(category)!);
      updateData(dataRef, setData, cachedData);
      setState(hasPlaces(cachedData) ? 'ready' : 'empty');
      setError(null);
      return undefined;
    }

    const timer = window.setTimeout(() => {
      updateData(
        dataRef,
        setData,
        selectedCategories.flatMap((category) => cachedByCategory.get(category) ?? previousByCategory.get(category) ?? []),
      );
      setState('loading');
      setError(null);
      Promise.allSettled(missingCategories.map((category) => loadCategory(
        cacheRef.current,
        inFlightRef.current,
        viewport,
        category,
      )))
        .then((settled) => {
          if (sequence !== sequenceRef.current) return;
          let failureCount = 0;
          settled.forEach((result, index) => {
            const category = missingCategories[index];
            if (result.status === 'fulfilled') {
              cachedByCategory.set(category, result.value);
            } else {
              failureCount += 1;
            }
          });
          const nextData = selectedCategories.flatMap(
            (category) => cachedByCategory.get(category) ?? previousByCategory.get(category) ?? [],
          );
          updateData(dataRef, setData, nextData);
          if (failureCount > 0) {
            setState(nextData.length > 0 ? 'partial' : 'error');
            setError(nextData.length > 0 ? 'NEARBY_PARTIAL' : 'NEARBY_UNAVAILABLE');
            return;
          }
          setState(hasPlaces(nextData) ? 'ready' : 'empty');
        });
    }, DEBOUNCE_MS);

    return () => {
      window.clearTimeout(timer);
    };
  }, [enabled, retrySeq, selectedCategoryKey, viewportKey]);

  return {
    data,
    error,
    retry: useCallback(() => setRetrySeq((current) => current + 1), []),
    state,
  };
}

function normalizeCategories(categories: readonly NearbyPlaceCategory[]): NearbyPlaceCategory[] {
  const supported = new Set<NearbyPlaceCategory>(NEARBY_PLACE_CATEGORIES);
  return [...new Set(categories)].filter((category) => supported.has(category)).slice(0, 3);
}

function updateData(
  dataRef: { current: ViewportNearbyPlaces[] },
  setData: (data: ViewportNearbyPlaces[]) => void,
  data: ViewportNearbyPlaces[],
) {
  dataRef.current = data;
  setData(data);
}

function hasPlaces(data: ViewportNearbyPlaces[]): boolean {
  return data.some((item) => item.category.places.length > 0);
}

function cacheKey(viewport: MapViewport, category: NearbyPlaceCategory): string {
  return `${viewportCacheKey(viewport)}:${category}`;
}

function viewportCacheKey(viewport: MapViewport): string {
  const { bounds } = viewport;
  return [
    viewport.level,
    bounds.swLat.toFixed(5),
    bounds.swLng.toFixed(5),
    bounds.neLat.toFixed(5),
    bounds.neLng.toFixed(5),
  ].join(':');
}

function remember(cache: Map<string, CacheEntry>, key: string, data: ViewportNearbyPlaces) {
  cache.set(key, { data, expiresAt: Date.now() + CACHE_TTL_MS });
  while (cache.size > CACHE_MAX_ENTRIES) {
    const oldest = cache.keys().next().value as string | undefined;
    if (oldest === undefined) break;
    cache.delete(oldest);
  }
}

function loadCategory(
  cache: Map<string, CacheEntry>,
  inFlight: Map<string, InFlightEntry>,
  viewport: MapViewport,
  category: NearbyPlaceCategory,
): Promise<ViewportNearbyPlaces> {
  const key = cacheKey(viewport, category);
  const cached = cache.get(key);
  if (cached && cached.expiresAt > Date.now()) return Promise.resolve(cached.data);
  const pending = inFlight.get(key);
  if (pending) return pending.promise;

  const controller = new AbortController();
  const promise = fetchViewportNearbyPlaces({ ...viewport, category }, controller.signal)
    .then((result) => {
      remember(cache, key, result);
      return result;
    })
    .finally(() => {
      if (inFlight.get(key)?.promise === promise) inFlight.delete(key);
    });
  const entry: InFlightEntry = { category, controller, promise, viewportKey: viewportCacheKey(viewport) };
  inFlight.set(key, entry);
  return promise;
}
