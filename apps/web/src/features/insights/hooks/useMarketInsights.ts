import { useEffect, useRef, useState } from 'react';

import {
  fetchMarketInsights,
  type InsightScopeType,
  type MarketInsights,
} from '../api/fetchMarketInsights';

type InsightRequestState = 'idle' | 'loading' | 'ready' | 'error';
type CacheEntry = { data: MarketInsights; fetchedAt: number; kstDate: string };

const CACHE_TTL_MILLIS = 5 * 60 * 1000;

export function useMarketInsights({
  active,
  regionCode,
  scope,
}: {
  active: boolean;
  regionCode: string | null;
  scope: InsightScopeType;
}) {
  const cacheRef = useRef(new Map<string, CacheEntry>());
  const requestSequenceRef = useRef(0);
  const [data, setData] = useState<MarketInsights | null>(null);
  const [state, setState] = useState<InsightRequestState>('idle');
  const [error, setError] = useState<string | null>(null);
  const [retrySeq, setRetrySeq] = useState(0);
  const cacheKey = `${scope}:${regionCode ?? ''}`;

  useEffect(() => {
    if (!active) return undefined;
    const cached = cacheRef.current.get(cacheKey);
    if (cached) {
      setData(cached.data);
      setState('ready');
      setError(null);
      if (cacheFresh(cached)) return undefined;
    } else {
      setData(null);
      setState('loading');
      setError(null);
    }
    const controller = new AbortController();
    const requestSequence = requestSequenceRef.current + 1;
    requestSequenceRef.current = requestSequence;
    fetchMarketInsights({ scope, regionCode, limit: 20 }, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || requestSequence !== requestSequenceRef.current) return;
        cacheRef.current.set(cacheKey, {
          data: next,
          fetchedAt: Date.now(),
          kstDate: currentKstDate(),
        });
        setData(next);
        setState('ready');
        setError(null);
      })
      .catch((requestError: unknown) => {
        if (controller.signal.aborted || requestSequence !== requestSequenceRef.current) return;
        if (cached) {
          setState('ready');
          return;
        }
        setState('error');
        setError(requestError instanceof Error ? requestError.message : '알 수 없는 거래 정보 오류');
      });
    return () => controller.abort();
  }, [active, cacheKey, regionCode, retrySeq, scope]);

  useEffect(() => {
    if (!active) return undefined;
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') {
        setRetrySeq((current) => current + 1);
      }
    };
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => document.removeEventListener('visibilitychange', refreshWhenVisible);
  }, [active]);

  return {
    data,
    error,
    retry: () => {
      cacheRef.current.delete(cacheKey);
      setRetrySeq((current) => current + 1);
    },
    state,
  };
}

function cacheFresh(entry: CacheEntry): boolean {
  return Date.now() - entry.fetchedAt < CACHE_TTL_MILLIS && entry.kstDate === currentKstDate();
}

function currentKstDate(): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    day: '2-digit',
    month: '2-digit',
    timeZone: 'Asia/Seoul',
    year: 'numeric',
  }).formatToParts(new Date(Date.now()));
  const read = (type: Intl.DateTimeFormatPartTypes) => (
    parts.find((part) => part.type === type)?.value ?? ''
  );
  return `${read('year')}-${read('month')}-${read('day')}`;
}
