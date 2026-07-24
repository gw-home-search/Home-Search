import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { useAuth } from '../../auth/AuthProvider';
import { createFavoriteClient, FavoriteClientError } from '../api/favoriteClient';
import type { FavoriteState } from '../favoriteTypes';
import { getCachedFavorite, setCachedFavorite, syncFavoriteOwner } from '../favoriteStore';
import type { UserFeedbackId } from '../../../shared/feedback/feedbackCatalog';

export function useFavoriteComplex(complexId: number | null | undefined) {
  const { authenticatedRequest, currentUser, openDialog, status } = useAuth();
  const client = useMemo(() => createFavoriteClient(authenticatedRequest), [authenticatedRequest]);
  const [favoriteState, setFavoriteState] = useState<FavoriteState>({ phase: 'auth-checking', favorite: null });
  const [favoriteError, setFavoriteError] = useState<UserFeedbackId | null>(null);
  const [liveMessage, setLiveMessage] = useState('');
  const [retrySequence, setRetrySequence] = useState(0);
  const requestSequence = useRef(0);
  const activeController = useRef<AbortController | null>(null);
  const selectionRef = useRef<number | null>(null);
  const failedMutation = useRef<'save' | 'remove' | null>(null);
  selectionRef.current = isComplexId(complexId) ? complexId : null;

  useEffect(() => {
    if (status !== 'authenticated' || currentUser == null) {
      syncFavoriteOwner(null);
      return;
    }
    syncFavoriteOwner(currentUser.userId);
  }, [currentUser, status]);

  useEffect(() => {
    const nextSequence = requestSequence.current + 1;
    requestSequence.current = nextSequence;
    activeController.current?.abort();
    activeController.current = null;
    failedMutation.current = null;
    setFavoriteError(null);
    setLiveMessage('');

    if (status === 'checking') {
      setFavoriteState({ phase: 'auth-checking', favorite: null });
      return;
    }
    if (status === 'anonymous') {
      setFavoriteState({ phase: 'anonymous', favorite: false });
      return;
    }
    if (status === 'unavailable') {
      setFavoriteState({ phase: 'unavailable', favorite: false });
      return;
    }
    if (!isComplexId(complexId)) {
      setFavoriteState({ phase: 'auth-checking', favorite: null });
      return;
    }
    const cached = getCachedFavorite(complexId);
    if (cached != null) {
      setFavoriteState({ phase: 'ready', favorite: cached });
      return;
    }

    const controller = new AbortController();
    activeController.current = controller;
    setFavoriteState({ phase: 'checking', favorite: null });
    client.get(complexId, controller.signal)
      .then((result) => {
        if (controller.signal.aborted || requestSequence.current !== nextSequence || selectionRef.current !== complexId) return;
        setCachedFavorite(complexId, result.favorite);
        setFavoriteState({ phase: 'ready', favorite: result.favorite });
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSequence.current !== nextSequence || selectionRef.current !== complexId) return;
        if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
        setFavoriteState({ phase: 'error', favorite: null });
        setFavoriteError('FAVORITE_STATUS_UNAVAILABLE');
      });
    return () => controller.abort();
  }, [client, complexId, retrySequence, status]);

  const toggleFavorite = useCallback(async (trigger?: HTMLElement) => {
    if (status !== 'authenticated') {
      openDialog(trigger);
      return;
    }
    const selectedId = selectionRef.current;
    const currentFavorite = favoriteState.favorite;
    if (selectedId == null || currentFavorite == null || favoriteState.phase === 'saving' || favoriteState.phase === 'removing') return;
    const action = currentFavorite ? 'remove' : 'save';
    const sequence = requestSequence.current;
    const controller = new AbortController();
    activeController.current?.abort();
    activeController.current = controller;
    failedMutation.current = null;
    setFavoriteError(null);
    setLiveMessage('');
    setFavoriteState({ phase: action === 'save' ? 'saving' : 'removing', favorite: !currentFavorite });
    try {
      if (action === 'save') await client.save(selectedId, controller.signal);
      else await client.remove(selectedId, controller.signal);
      if (controller.signal.aborted || requestSequence.current !== sequence || selectionRef.current !== selectedId) return;
      const nextFavorite = action === 'save';
      setCachedFavorite(selectedId, nextFavorite);
      setFavoriteState({ phase: 'ready', favorite: nextFavorite });
      setLiveMessage(nextFavorite ? '관심 단지에 저장했습니다.' : '관심 단지에서 해제했습니다.');
    } catch (error) {
      if (controller.signal.aborted || requestSequence.current !== sequence || selectionRef.current !== selectedId) return;
      if (error instanceof FavoriteClientError && error.kind === 'session-expired') return;
      failedMutation.current = action;
      setFavoriteState({ phase: 'error', favorite: currentFavorite });
      setFavoriteError(error instanceof FavoriteClientError && error.kind === 'limit'
        ? 'FAVORITE_LIMIT_REACHED'
        : action === 'save' ? 'FAVORITE_SAVE_FAILED' : 'FAVORITE_REMOVE_FAILED');
    }
  }, [client, favoriteState, openDialog, status]);

  const retryFavorite = useCallback(() => {
    if (failedMutation.current != null) void toggleFavorite();
    else setRetrySequence((current) => current + 1);
  }, [toggleFavorite]);

  return {
    favoriteError,
    favoriteState,
    liveMessage,
    onFavoriteToggle: toggleFavorite,
    onRetryFavorite: retryFavorite,
  };
}

function isComplexId(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
}
