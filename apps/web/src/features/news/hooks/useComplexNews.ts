import { useEffect, useRef, useState } from 'react';

import { fetchComplexNews, type MarketNewsItem } from '../api/fetchMarketNews';

export function useComplexNews(complexId: number | null) {
  const sequenceRef = useRef(0);
  const [items, setItems] = useState<MarketNewsItem[]>([]);
  const [state, setState] = useState<'idle' | 'loading' | 'ready' | 'error'>('idle');
  const [retrySequence, setRetrySequence] = useState(0);

  useEffect(() => {
    if (complexId == null) {
      setItems([]);
      setState('idle');
      return undefined;
    }
    const controller = new AbortController();
    const sequence = ++sequenceRef.current;
    setItems([]);
    setState('loading');
    fetchComplexNews(complexId, controller.signal)
      .then((next) => {
        if (controller.signal.aborted || sequence !== sequenceRef.current) return;
        setItems(next);
        setState('ready');
      })
      .catch(() => {
        if (controller.signal.aborted || sequence !== sequenceRef.current) return;
        setState('error');
      });
    return () => controller.abort();
  }, [complexId, retrySequence]);

  return { items, retry: () => setRetrySequence((value) => value + 1), state };
}
