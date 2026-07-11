import { useCallback, useEffect, useRef, useState } from 'react';

import type {
  ComplexMapMarker,
  ComplexSelection,
  DetailRequestState,
} from '../../../app/mapAppTypes';
import type { RegionComplexSummary } from '../../region/api/fetchRegions';
import {
  fetchComplexDetail,
  fetchComplexDetailByComplexId,
  type ComplexDetail,
} from '../api/fetchComplexDetail';
import {
  fetchParcelComplexes,
  type ParcelComplexSummary,
} from '../api/fetchParcelComplexes';
import {
  fetchComplexTrades,
  fetchParcelTrades,
  type ParcelTrades,
  type TradeItem,
} from '../api/fetchParcelTrades';
import {
  fetchComplexTradeTrend,
  fetchParcelTradeTrend,
  type TradeTrendPoint,
} from '../api/fetchTradeTrend';

const TRADE_PAGE_SIZE = 25;
const PREDICTION_POLL_INTERVAL_MILLIS = 2000;
const PREDICTION_POLL_MAX_ATTEMPTS = 5;

export function useComplexDetail() {
  const [selectedComplex, setSelectedComplex] = useState<ComplexSelection | null>(
    initialComplexSelectionFromUrl,
  );
  const [complexDetail, setComplexDetail] = useState<ComplexDetail | null>(null);
  const [parcelTrades, setParcelTrades] = useState<ParcelTrades | null>(null);
  const [tradeTrend, setTradeTrend] = useState<TradeTrendPoint[]>([]);
  const [tradePage, setTradePage] = useState(0);
  const [tradeRows, setTradeRows] = useState<TradeItem[]>([]);
  const [parcelComplexes, setParcelComplexes] = useState<ParcelComplexSummary[]>([]);
  const [detailState, setDetailState] = useState<DetailRequestState>('idle');
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailRetrySeq, setDetailRetrySeq] = useState(0);
  const detailRequestSeq = useRef(0);
  const tradePageRequestSeq = useRef(0);
  const parcelComplexRequestSeq = useRef(0);
  const predictionPoll = useRef({ key: '', attempts: 0 });
  const detailRequestPending = useRef(false);

  useEffect(() => {
    if (selectedComplex == null) {
      detailRequestPending.current = false;
      setComplexDetail(null);
      setParcelTrades(null);
      setTradeTrend([]);
      setTradePage(0);
      setTradeRows([]);
      setParcelComplexes([]);
      setDetailState('idle');
      setDetailError(null);
      return undefined;
    }

    const requestSeq = detailRequestSeq.current + 1;
    detailRequestSeq.current = requestSeq;
    tradePageRequestSeq.current += 1;
    let ignore = false;

    setDetailState('loading');
    detailRequestPending.current = true;
    setDetailError(null);

    const isComplexScoped = selectedComplex.parcelId == null && selectedComplex.complexId != null;
    const detailRequest = isComplexScoped
      ? fetchComplexDetailByComplexId(selectedComplex.complexId as number)
      : fetchComplexDetail(requiredParcelId(selectedComplex), selectedComplex.complexId);
    const tradeRequest = isComplexScoped
      ? fetchComplexTrades(selectedComplex.complexId as number)
      : fetchParcelTrades(requiredParcelId(selectedComplex), selectedComplex.complexId);
    const trendRequest = (isComplexScoped
      ? fetchComplexTradeTrend(selectedComplex.complexId as number)
      : fetchParcelTradeTrend(requiredParcelId(selectedComplex), selectedComplex.complexId)
    ).catch((): TradeTrendPoint[] => []);

    Promise.all([detailRequest, tradeRequest, trendRequest])
      .then(([nextDetail, nextTrades, nextTrend]) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }
        setComplexDetail(nextDetail);
        setParcelTrades(nextTrades);
        setTradeTrend(nextTrend);
        setTradePage(nextTrades.page);
        setTradeRows(nextTrades.trades);
        setDetailState('ready');
        detailRequestPending.current = false;
      })
      .catch((error: unknown) => {
        if (ignore || requestSeq !== detailRequestSeq.current) {
          return;
        }
        setComplexDetail(null);
        setParcelTrades(null);
        setTradeTrend([]);
        setTradePage(0);
        setTradeRows([]);
        setDetailState('error');
        setDetailError(error instanceof Error ? error.message : '알 수 없는 상세 정보 오류');
        detailRequestPending.current = false;
      });

    return () => {
      ignore = true;
    };
  }, [selectedComplex, detailRetrySeq]);

  useEffect(() => {
    if (complexDetail == null || detailState !== 'ready') {
      setParcelComplexes([]);
      return undefined;
    }

    const requestSeq = parcelComplexRequestSeq.current + 1;
    parcelComplexRequestSeq.current = requestSeq;
    let ignore = false;

    fetchParcelComplexes(complexDetail.parcelId)
      .then((nextComplexes) => {
        if (!ignore && requestSeq === parcelComplexRequestSeq.current) {
          setParcelComplexes(nextComplexes);
        }
      })
      .catch(() => {
        if (!ignore && requestSeq === parcelComplexRequestSeq.current) {
          setParcelComplexes([]);
        }
      });

    return () => {
      ignore = true;
    };
  }, [complexDetail, detailState]);

  useEffect(() => {
    if (
      selectedComplex == null
      || detailState !== 'ready'
      || complexDetail?.prediction?.status !== 'PENDING'
    ) {
      predictionPoll.current = { key: selectedComplexKey(selectedComplex), attempts: 0 };
      return undefined;
    }

    const key = selectedComplexKey(selectedComplex);
    if (predictionPoll.current.key !== key) {
      predictionPoll.current = { key, attempts: 0 };
    }
    if (predictionPoll.current.attempts >= PREDICTION_POLL_MAX_ATTEMPTS) {
      return undefined;
    }

    let ignore = false;
    const timer = setTimeout(() => {
      if (ignore) {
        return;
      }
      predictionPoll.current = {
        key,
        attempts: predictionPoll.current.key === key ? predictionPoll.current.attempts + 1 : 1,
      };
      fetchSelectedComplexDetail(selectedComplex)
        .then((nextDetail) => {
          if (!ignore && selectedComplexKey(selectedComplex) === key) {
            setComplexDetail(nextDetail);
          }
        })
        .catch(() => {
          // Prediction polling must not disturb an already rendered detail drawer.
        });
    }, PREDICTION_POLL_INTERVAL_MILLIS);

    return () => {
      ignore = true;
      clearTimeout(timer);
    };
  }, [complexDetail?.prediction, detailState, selectedComplex]);

  const selectComplex = useCallback((selection: ComplexSelection) => {
    setSelectedComplex(selection);
  }, []);

  const selectComplexMarker = useCallback((marker: ComplexMapMarker) => {
    setSelectedComplex({ parcelId: marker.parcelId, complexId: marker.complexId });
  }, []);

  const selectComplexSummary = useCallback((complex: ParcelComplexSummary | RegionComplexSummary) => {
    setSelectedComplex({ parcelId: complex.parcelId, complexId: complex.complexId });
  }, []);

  const closeDetail = useCallback(() => setSelectedComplex(null), []);
  const retryDetail = useCallback(() => {
    if (detailRequestPending.current) return;
    detailRequestPending.current = true;
    setDetailState('loading');
    setDetailRetrySeq((current) => current + 1);
  }, []);

  function loadMoreTrades() {
    if (selectedComplex == null) {
      return;
    }

    const nextPage = tradePage + 1;
    const requestSeq = tradePageRequestSeq.current + 1;
    tradePageRequestSeq.current = requestSeq;
    const request = selectedComplex.parcelId == null && selectedComplex.complexId != null
      ? fetchComplexTrades(selectedComplex.complexId, { page: nextPage, size: TRADE_PAGE_SIZE })
      : fetchParcelTrades(
        requiredParcelId(selectedComplex),
        selectedComplex.complexId,
        { page: nextPage, size: TRADE_PAGE_SIZE },
      );

    request
      .then((next) => {
        if (requestSeq !== tradePageRequestSeq.current) {
          return;
        }
        setTradePage(next.page);
        setTradeRows((current) => [...current, ...next.trades]);
      })
      .catch(() => {
        // Keep the loaded rows rendered when a page fetch fails.
      });
  }

  return {
    closeDetail,
    complexDetail,
    detailError,
    detailState,
    loadMoreTrades,
    parcelComplexes,
    parcelTrades,
    retryDetail,
    selectComplex,
    selectComplexMarker,
    selectComplexSummary,
    selectedComplex,
    tradeRows,
    tradeTrend,
  };
}

function requiredParcelId(selection: ComplexSelection): number {
  if (selection.parcelId == null) {
    throw new Error('parcelId is required for parcel-scoped detail request');
  }
  return selection.parcelId;
}

function fetchSelectedComplexDetail(selection: ComplexSelection): Promise<ComplexDetail> {
  return selection.parcelId == null && selection.complexId != null
    ? fetchComplexDetailByComplexId(selection.complexId)
    : fetchComplexDetail(requiredParcelId(selection), selection.complexId);
}

function selectedComplexKey(selection: ComplexSelection | null): string {
  if (selection == null) {
    return 'none';
  }
  return `${selection.parcelId ?? '_'}:${selection.complexId ?? '_'}`;
}

function initialComplexSelectionFromUrl(): ComplexSelection | null {
  const complexId = Number(new URLSearchParams(window.location.search).get('complexId'));
  if (!Number.isSafeInteger(complexId) || complexId <= 0) {
    return null;
  }
  return { parcelId: null, complexId };
}
