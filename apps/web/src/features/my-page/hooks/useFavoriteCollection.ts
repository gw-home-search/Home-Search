import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useAuth } from '../../auth/AuthProvider';
import { fetchComplexDetailByComplexId, type ComplexDetail } from '../../complex-detail/api/fetchComplexDetail';
import { createFavoriteClient, FavoriteClientError } from '../../favorites/api/favoriteClient';
import {
  getCachedFavorite,
  primeCachedFavorite,
  setCachedFavorite,
  subscribeFavoriteStore,
  syncFavoriteOwner,
} from '../../favorites/favoriteStore';
import type { UserFeedbackId } from '../../../shared/feedback/feedbackCatalog';

export type FavoriteCollectionItem = {
  complexId: number;
  savedAt: string;
  detail: ComplexDetail | null;
  detailPhase: 'loading' | 'ready' | 'error';
  mutationPhase: 'idle' | 'removing';
  mutationError: UserFeedbackId | null;
};

type FavoriteCollectionState =
  | { phase: 'loading'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number }
  | { phase: 'ready'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number }
  | { phase: 'error'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number };

const DETAIL_CONCURRENCY = 4;

export function useFavoriteCollection(size: number, incremental = false) {
  const { authenticatedRequest, currentUser, status } = useAuth();
  const client = useMemo(() => createFavoriteClient(authenticatedRequest), [authenticatedRequest]);
  const [state, setState] = useState<FavoriteCollectionState>({
    phase: 'loading', items: [], totalElements: 0, totalPages: 0,
  });
  const [reloadSequence, setReloadSequence] = useState(0);
  const [liveMessage, setLiveMessage] = useState('');
  const [requestedPage, setRequestedPage] = useState(0);
  const [lastLoadedPage, setLastLoadedPage] = useState(-1);
  const [loadingMore, setLoadingMore] = useState(false);
  const [loadMoreError, setLoadMoreError] = useState(false);
  const [refreshFeedback, setRefreshFeedback] = useState<UserFeedbackId | null>(null);
  const requestSequence = useRef(0);
  const itemCount = useRef(0);
  const detailControllers = useRef(new Map<number, AbortController>());
  const mutationControllers = useRef(new Map<number, AbortController>());

  useEffect(() => {
    itemCount.current = state.items.length;
  }, [state.items.length]);

  useEffect(() => {
    syncFavoriteOwner(status === 'authenticated' && currentUser != null ? currentUser.userId : null);
  }, [currentUser, status]);

  useEffect(() => {
    if (status !== 'authenticated' || currentUser == null) return undefined;
    return subscribeFavoriteStore((change) => {
      if (change.ownerUserId !== currentUser.userId) return;
      if (!change.favorite) {
        setState((current) => {
          const hasItem = current.items.some((item) => item.complexId === change.complexId);
          return {
            ...current,
            items: hasItem
              ? current.items.filter((item) => item.complexId !== change.complexId)
              : current.items,
            totalElements: Math.max(0, current.totalElements - 1),
          };
        });
        if (incremental) {
          setRequestedPage(Math.max(0, lastLoadedPage));
          setReloadSequence((value) => value + 1);
        }
        return;
      }
      setLiveMessage('');
      setRequestedPage(0);
      setReloadSequence((value) => value + 1);
    });
  }, [currentUser, incremental, lastLoadedPage, status]);

  useEffect(() => {
    if (status !== 'authenticated' || currentUser == null) return;
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    const controller = new AbortController();
    const initialPage = requestedPage === 0;
    setLoadMoreError(false);
    if (initialPage) setRefreshFeedback(null);
    if (initialPage) {
      setLastLoadedPage(-1);
      setState((current) => current.items.length > 0
        ? current
        : { phase: 'loading', items: [], totalElements: 0, totalPages: 0 });
    } else {
      setLoadingMore(true);
    }

    void client.list(requestedPage, size, controller.signal)
      .then(async (result) => {
        const baseItems: FavoriteCollectionItem[] = result.content.map((item) => ({
          ...item,
          detail: null,
          detailPhase: 'loading',
          mutationPhase: 'idle',
          mutationError: null,
        }));
        const enrichedItems = await enrichFavoriteDetails(baseItems, controller.signal);
        if (controller.signal.aborted || requestSequence.current !== sequence) return;
        const items = enrichedItems.filter((item) => getCachedFavorite(item.complexId) !== false);
        const locallyRemovedCount = enrichedItems.length - items.length;
        items.forEach((item) => primeCachedFavorite(item.complexId, true));
        setState((current) => ({
          phase: 'ready',
          items: initialPage ? items : mergeFavoriteItems(current.items, items),
          totalElements: Math.max(items.length, result.totalElements - locallyRemovedCount),
          totalPages: result.totalPages,
        }));
        setLastLoadedPage(result.page);
        setLoadingMore(false);
        setRefreshFeedback(null);
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSequence.current !== sequence) return;
        if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
        if (initialPage) {
          const preservesExistingItems = itemCount.current > 0;
          setState((current) => preservesExistingItems
            ? { ...current, phase: 'ready' }
            : { phase: 'error', items: [], totalElements: 0, totalPages: 0 });
          if (preservesExistingItems) setRefreshFeedback('FAVORITES_REFRESH_UNAVAILABLE');
        }
        else setLoadMoreError(true);
        setLoadingMore(false);
      });

    return () => controller.abort();
  }, [client, currentUser, reloadSequence, requestedPage, size, status]);

  useEffect(() => () => {
    detailControllers.current.forEach((controller) => controller.abort());
    detailControllers.current.clear();
    mutationControllers.current.forEach((controller) => controller.abort());
    mutationControllers.current.clear();
  }, []);

  const retry = useCallback(() => {
    setLiveMessage('');
    setRequestedPage(0);
    setReloadSequence((value) => value + 1);
  }, []);

  const loadMore = useCallback(() => {
    if (!incremental || loadingMore || loadMoreError || state.items.length >= state.totalElements) return;
    setLiveMessage('');
    setRequestedPage(lastLoadedPage + 1);
  }, [incremental, lastLoadedPage, loadMoreError, loadingMore, state.items.length, state.totalElements]);

  const retryLoadMore = useCallback(() => {
    if (!loadMoreError) return;
    setLiveMessage('');
    setReloadSequence((value) => value + 1);
  }, [loadMoreError]);

  const retryDetail = useCallback(async (complexId: number) => {
    detailControllers.current.get(complexId)?.abort();
    const controller = new AbortController();
    detailControllers.current.set(complexId, controller);
    setState((current) => ({
      ...current,
      items: updateItem(current.items, complexId, (item) => ({ ...item, detailPhase: 'loading' })),
    }));
    try {
      const detail = await fetchComplexDetailByComplexId(complexId, controller.signal);
      if (controller.signal.aborted) return;
      setState((current) => ({
        ...current,
        items: updateItem(current.items, complexId, (item) => ({ ...item, detail, detailPhase: 'ready' })),
      }));
    } catch {
      if (controller.signal.aborted) return;
      setState((current) => ({
        ...current,
        items: updateItem(current.items, complexId, (item) => ({ ...item, detail: null, detailPhase: 'error' })),
      }));
    } finally {
      if (detailControllers.current.get(complexId) === controller) detailControllers.current.delete(complexId);
    }
  }, []);

  const remove = useCallback(async (complexId: number) => {
    mutationControllers.current.get(complexId)?.abort();
    const controller = new AbortController();
    mutationControllers.current.set(complexId, controller);
    setLiveMessage('');
    setState((current) => ({
      ...current,
      items: updateItem(current.items, complexId, (item) => ({
        ...item, mutationPhase: 'removing', mutationError: null,
      })),
    }));
    try {
      await client.remove(complexId, controller.signal);
      setCachedFavorite(complexId, false);
      setLiveMessage('관심 단지에서 해제했습니다.');
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
      setState((current) => ({
        ...current,
        items: updateItem(current.items, complexId, (item) => ({
          ...item,
          mutationPhase: 'idle',
          mutationError: 'FAVORITE_REMOVE_FAILED',
        })),
      }));
    } finally {
      if (mutationControllers.current.get(complexId) === controller) mutationControllers.current.delete(complexId);
    }
  }, [client]);

  return {
    hasMore: incremental && state.items.length < state.totalElements,
    liveMessage,
    loadingMore,
    loadMore,
    loadMoreError,
    refreshFeedback,
    remove,
    retry,
    retryDetail,
    retryLoadMore,
    state,
  };
}

async function enrichFavoriteDetails(
  items: FavoriteCollectionItem[],
  signal: AbortSignal,
): Promise<FavoriteCollectionItem[]> {
  const result = [...items];
  let nextIndex = 0;
  const workerCount = Math.min(DETAIL_CONCURRENCY, items.length);

  async function worker() {
    while (!signal.aborted) {
      const index = nextIndex;
      nextIndex += 1;
      if (index >= items.length) return;
      const item = items[index];
      try {
        const detail = await fetchComplexDetailByComplexId(item.complexId, signal);
        result[index] = { ...item, detail, detailPhase: 'ready' };
      } catch {
        if (signal.aborted) return;
        result[index] = { ...item, detail: null, detailPhase: 'error' };
      }
    }
  }

  await Promise.all(Array.from({ length: workerCount }, () => worker()));
  return result;
}

function updateItem(
  items: FavoriteCollectionItem[],
  complexId: number,
  update: (item: FavoriteCollectionItem) => FavoriteCollectionItem,
): FavoriteCollectionItem[] {
  return items.map((item) => item.complexId === complexId ? update(item) : item);
}

function mergeFavoriteItems(
  current: FavoriteCollectionItem[],
  next: FavoriteCollectionItem[],
): FavoriteCollectionItem[] {
  const nextIds = new Set(next.map((item) => item.complexId));
  return [...current.filter((item) => !nextIds.has(item.complexId)), ...next];
}
