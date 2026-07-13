import { useCallback, useEffect, useState } from 'react';

import { fetchNearbyPlaces, type NearbyPlaces } from './api/fetchNearbyPlaces';

export type NearbyPlaceRequestState = 'idle' | 'loading' | 'ready' | 'empty' | 'error';

export function useNearbyPlaces(complexId: number | null, enabled: boolean) {
  const [data, setData] = useState<NearbyPlaces | null>(null);
  const [state, setState] = useState<NearbyPlaceRequestState>('idle');
  const [error, setError] = useState<string | null>(null);
  const [retrySeq, setRetrySeq] = useState(0);

  useEffect(() => {
    if (!enabled || complexId == null) {
      setData(null);
      setState('idle');
      setError(null);
      return undefined;
    }

    const controller = new AbortController();
    setData(null);
    setState('loading');
    setError(null);
    fetchNearbyPlaces(complexId, { signal: controller.signal })
      .then((result) => {
        if (controller.signal.aborted) return;
        setData(result);
        setState(result.categories.every((category) => category.matchedCount === 0) ? 'empty' : 'ready');
      })
      .catch((reason: unknown) => {
        if (controller.signal.aborted) return;
        setData(null);
        setState('error');
        setError(reason instanceof Error ? reason.message : '주변 상권 정보를 불러오지 못했습니다.');
      });

    return () => controller.abort();
  }, [complexId, enabled, retrySeq]);

  const retry = useCallback(() => setRetrySeq((current) => current + 1), []);
  return { data, error, retry, state };
}
