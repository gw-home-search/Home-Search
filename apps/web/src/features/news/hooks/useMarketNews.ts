import { useCallback, useEffect, useRef, useState } from 'react';

import {
  fetchMarketNews,
  type MarketNews,
  type MarketNewsCategory,
  type MarketNewsScopeType,
} from '../api/fetchMarketNews';

type NewsRequestState = 'idle' | 'loading' | 'ready' | 'error' | 'loading-more';
type CacheEntry = { data: MarketNews; fetchedAt: number; kstDate: string };
const CACHE_TTL_MILLIS = 5 * 60 * 1000;

export function useMarketNews({
  active,
  category,
  regionCode,
  scope,
}: {
  active: boolean;
  category: MarketNewsCategory;
  regionCode: string | null;
  scope: MarketNewsScopeType;
}) {
  const cacheRef = useRef(new Map<string, CacheEntry>());
  const controllerRef = useRef<AbortController | null>(null);
  const sequenceRef = useRef(0);
  const [data, setData] = useState<MarketNews | null>(null);
  const [state, setState] = useState<NewsRequestState>('idle');
  const [refreshSequence, setRefreshSequence] = useState(0);
  const cacheKey = `${scope}:${regionCode ?? ''}:${category}`;

  useEffect(() => {
    if (!active) return undefined;
    const cached = cacheRef.current.get(cacheKey);
    if (cached) {
      setData(cached.data);
      setState('ready');
      if (isFresh(cached)) return undefined;
    } else {
      setData(null);
      setState('loading');
    }
    const controller = new AbortController();
    controllerRef.current?.abort();
    controllerRef.current = controller;
    const sequence = ++sequenceRef.current;
    fetchMarketNews({ category, limit: 20, regionCode, scope }, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || sequence !== sequenceRef.current) return;
        cacheRef.current.set(cacheKey, { data: next, fetchedAt: Date.now(), kstDate: currentKstDate() });
        setData(next);
        setState('ready');
      })
      .catch(() => {
        if (controller.signal.aborted || sequence !== sequenceRef.current) return;
        setState(cached ? 'ready' : 'error');
      });
    return () => controller.abort();
  }, [active, cacheKey, category, refreshSequence, regionCode, scope]);

  useEffect(() => {
    if (!active) return undefined;
    const refreshWhenVisible = () => {
      if (document.visibilityState === 'visible') setRefreshSequence((value) => value + 1);
    };
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => document.removeEventListener('visibilitychange', refreshWhenVisible);
  }, [active]);

  const loadMore = useCallback(() => {
    if (!data?.nextCursor || state === 'loading-more') return;
    const controller = new AbortController();
    controllerRef.current?.abort();
    controllerRef.current = controller;
    const sequence = ++sequenceRef.current;
    setState('loading-more');
    fetchMarketNews(
      { category, cursor: data.nextCursor, limit: 20, regionCode, scope },
      controller.signal,
    ).then((next) => {
      if (controller.signal.aborted || sequence !== sequenceRef.current) return;
      const merged = { ...next, items: [...data.items, ...next.items] };
      cacheRef.current.set(cacheKey, { data: merged, fetchedAt: Date.now(), kstDate: currentKstDate() });
      setData(merged);
      setState('ready');
    }).catch(() => {
      if (!controller.signal.aborted && sequence === sequenceRef.current) setState('ready');
    });
  }, [cacheKey, category, data, regionCode, scope, state]);

  return {
    data,
    loadMore,
    retry: () => {
      cacheRef.current.delete(cacheKey);
      setRefreshSequence((value) => value + 1);
    },
    state,
  };
}

function isFresh(entry: CacheEntry): boolean {
  return Date.now() - entry.fetchedAt < CACHE_TTL_MILLIS && entry.kstDate === currentKstDate();
}

function currentKstDate(): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Seoul',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
}
