import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useAuth } from '../../auth/AuthProvider';
import { fetchComplexDetailByComplexId, type ComplexDetail } from '../../complex-detail/api/fetchComplexDetail';
import { createFavoriteClient, FavoriteClientError } from '../../favorites/api/favoriteClient';
import { setCachedFavorite, syncFavoriteOwner } from '../../favorites/favoriteStore';

export type FavoriteCollectionItem = {
  complexId: number;
  savedAt: string;
  detail: ComplexDetail | null;
  detailPhase: 'loading' | 'ready' | 'error';
  mutationPhase: 'idle' | 'removing';
  mutationError: string | null;
};

type FavoriteCollectionState =
  | { phase: 'loading'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number }
  | { phase: 'ready'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number }
  | { phase: 'error'; items: FavoriteCollectionItem[]; totalElements: number; totalPages: number };

const DETAIL_CONCURRENCY = 4;

export function useFavoriteCollection(page: number, size: number) {
  const { authenticatedRequest, currentUser, status } = useAuth();
  const client = useMemo(() => createFavoriteClient(authenticatedRequest), [authenticatedRequest]);
  const [state, setState] = useState<FavoriteCollectionState>({
    phase: 'loading', items: [], totalElements: 0, totalPages: 0,
  });
  const [reloadSequence, setReloadSequence] = useState(0);
  const [liveMessage, setLiveMessage] = useState('');
  const requestSequence = useRef(0);
  const detailControllers = useRef(new Map<number, AbortController>());
  const mutationControllers = useRef(new Map<number, AbortController>());

  useEffect(() => {
    syncFavoriteOwner(status === 'authenticated' && currentUser != null ? currentUser.userId : null);
  }, [currentUser, status]);

  useEffect(() => {
    if (status !== 'authenticated' || currentUser == null) return;
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    const controller = new AbortController();
    setLiveMessage('');
    setState({ phase: 'loading', items: [], totalElements: 0, totalPages: 0 });

    void client.list(page, size, controller.signal)
      .then(async (result) => {
        const baseItems: FavoriteCollectionItem[] = result.content.map((item) => ({
          ...item,
          detail: null,
          detailPhase: 'loading',
          mutationPhase: 'idle',
          mutationError: null,
        }));
        if (controller.signal.aborted || requestSequence.current !== sequence) return;
        setState({
          phase: 'ready',
          items: baseItems,
          totalElements: result.totalElements,
          totalPages: result.totalPages,
        });
        const items = await enrichFavoriteDetails(baseItems, controller.signal);
        if (controller.signal.aborted || requestSequence.current !== sequence) return;
        setState({
          phase: 'ready',
          items,
          totalElements: result.totalElements,
          totalPages: result.totalPages,
        });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSequence.current !== sequence) return;
        if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
        setState({ phase: 'error', items: [], totalElements: 0, totalPages: 0 });
      });

    return () => controller.abort();
  }, [client, currentUser, page, reloadSequence, size, status]);

  useEffect(() => () => {
    detailControllers.current.forEach((controller) => controller.abort());
    detailControllers.current.clear();
    mutationControllers.current.forEach((controller) => controller.abort());
    mutationControllers.current.clear();
  }, []);

  const retry = useCallback(() => setReloadSequence((value) => value + 1), []);

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
      setState((current) => ({
        ...current,
        items: current.items.filter((item) => item.complexId !== complexId),
        totalElements: Math.max(0, current.totalElements - 1),
      }));
      setLiveMessage('관심 단지에서 해제했습니다.');
    } catch (error) {
      if (controller.signal.aborted) return;
      if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
      setState((current) => ({
        ...current,
        items: updateItem(current.items, complexId, (item) => ({
          ...item,
          mutationPhase: 'idle',
          mutationError: '관심 단지를 해제하지 못했습니다. 다시 시도해주세요.',
        })),
      }));
    } finally {
      if (mutationControllers.current.get(complexId) === controller) mutationControllers.current.delete(complexId);
    }
  }, [client]);

  return { liveMessage, remove, retry, retryDetail, state };
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
