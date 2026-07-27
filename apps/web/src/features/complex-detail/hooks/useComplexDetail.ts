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
  type ParcelTrades,
  type TradeItem,
} from '../api/fetchParcelTrades';
import {
  fetchComplexTradeTrend,
  type TradeTrendPoint,
} from '../api/fetchTradeTrend';
import {
  fetchTradeAreas,
  type TradeAreas,
} from '../api/fetchTradeAreas';
import {
  isCancelledFailure,
  toRequestFailure,
  type RequestFailure,
} from '../../../shared/http/requestFailure';

const TRADE_PAGE_SIZE = 25;
const PREDICTION_POLL_INTERVAL_MILLIS = 2000;
const PREDICTION_POLL_MAX_ATTEMPTS = 5;

export function useComplexDetail() {
  const [selectedComplex, setSelectedComplex] = useState<ComplexSelection | null>(
    initialComplexSelectionFromUrl,
  );
  const [complexDetail, setComplexDetail] = useState<ComplexDetail | null>(null);
  const [parcelTrades, setParcelTrades] = useState<ParcelTrades | null>(null);
  const [tradeAreas, setTradeAreas] = useState<TradeAreas | null>(null);
  const [selectedExclArea, setSelectedExclArea] = useState<number | null>(null);
  const [tradeTrend, setTradeTrend] = useState<TradeTrendPoint[]>([]);
  const [tradePage, setTradePage] = useState(0);
  const [tradeRows, setTradeRows] = useState<TradeItem[]>([]);
  const [parcelComplexes, setParcelComplexes] = useState<ParcelComplexSummary[]>([]);
  const [detailState, setDetailState] = useState<DetailRequestState>('idle');
  const [detailError, setDetailError] = useState<RequestFailure | null>(null);
  const [tradeState, setTradeState] = useState<DetailRequestState>('idle');
  const [tradeError, setTradeError] = useState<RequestFailure | null>(null);
  const [tradeMoreState, setTradeMoreState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [trendState, setTrendState] = useState<DetailRequestState>('idle');
  const [trendError, setTrendError] = useState<RequestFailure | null>(null);
  const [areaState, setAreaState] = useState<DetailRequestState>('idle');
  const [areaError, setAreaError] = useState<RequestFailure | null>(null);
  const [detailRetrySeq, setDetailRetrySeq] = useState(0);
  const [tradeRetrySeq, setTradeRetrySeq] = useState(0);
  const [trendRetrySeq, setTrendRetrySeq] = useState(0);
  const [areaRetrySeq, setAreaRetrySeq] = useState(0);
  const detailRequestSeq = useRef(0);
  const tradePageRequestSeq = useRef(0);
  const trendRequestSeq = useRef(0);
  const parcelComplexRequestSeq = useRef(0);
  const areaRequestSeq = useRef(0);
  const predictionPoll = useRef({ key: '', attempts: 0 });
  const detailRequestPending = useRef(false);
  const tradeMoreController = useRef<AbortController | null>(null);

  useEffect(() => () => {
    tradeMoreController.current?.abort();
    tradeMoreController.current = null;
  }, []);

  useEffect(() => {
    if (selectedComplex == null) {
      detailRequestPending.current = false;
      setComplexDetail(null);
      setParcelTrades(null);
      setTradeAreas(null);
      setSelectedExclArea(null);
      setTradeTrend([]);
      setTradePage(0);
      setTradeRows([]);
      setParcelComplexes([]);
      setDetailState('idle');
      setDetailError(null);
      setAreaState('idle');
      setAreaError(null);
      return undefined;
    }

    const requestSeq = detailRequestSeq.current + 1;
    detailRequestSeq.current = requestSeq;
    const controller = new AbortController();

    setDetailState('loading');
    detailRequestPending.current = true;
    setDetailError(null);

    const isComplexScoped = selectedComplex.parcelId == null && selectedComplex.complexId != null;
    const detailRequest = isComplexScoped
      ? fetchComplexDetailByComplexId(selectedComplex.complexId as number, controller.signal)
      : fetchComplexDetail(requiredParcelId(selectedComplex), selectedComplex.complexId, controller.signal);

    detailRequest
      .then((nextDetail) => {
        if (controller.signal.aborted || requestSeq !== detailRequestSeq.current) {
          return;
        }
        setComplexDetail(nextDetail);
        setDetailState('ready');
        detailRequestPending.current = false;
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSeq !== detailRequestSeq.current) {
          return;
        }
        const failure = toRequestFailure(error, {
          service: 'property-data',
          operation: 'complex-detail',
        }, controller.signal);
        if (isCancelledFailure(failure)) return;
        setDetailState('error');
        setDetailError(failure);
        detailRequestPending.current = false;
      });

    return () => {
      controller.abort();
    };
  }, [selectedComplex, detailRetrySeq]);

  useEffect(() => {
    areaRequestSeq.current += 1;
    tradePageRequestSeq.current += 1;
    trendRequestSeq.current += 1;
    tradeMoreController.current?.abort();
    tradeMoreController.current = null;
    setTradeAreas(null);
    setSelectedExclArea(null);
    setParcelTrades(null);
    setTradeRows([]);
    setTradeTrend([]);
    setTradePage(0);
    setTradeState('idle');
    setTradeError(null);
    setTradeMoreState('idle');
    setTrendState('idle');
    setTrendError(null);

    if (detailState !== 'ready' || complexDetail?.complexId == null) {
      setAreaState('idle');
      setAreaError(null);
      return undefined;
    }

    const complexId = complexDetail.complexId;
    const requestSeq = areaRequestSeq.current;
    const controller = new AbortController();
    setAreaState('loading');
    setAreaError(null);

    fetchTradeAreas(complexId, controller.signal)
      .then((nextAreas) => {
        if (controller.signal.aborted || requestSeq !== areaRequestSeq.current) return;
        setTradeAreas(nextAreas);
        setSelectedExclArea(nextAreas.defaultExclArea);
        setAreaState('ready');
        if (nextAreas.defaultExclArea == null) {
          setTradeState('ready');
          setTrendState('ready');
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSeq !== areaRequestSeq.current) return;
        const failure = toRequestFailure(error, {
          service: 'property-data',
          operation: 'trade-areas',
        }, controller.signal);
        if (isCancelledFailure(failure)) return;
        setAreaState('error');
        setAreaError(failure);
      });

    return () => controller.abort();
  }, [complexDetail?.complexId, detailState, areaRetrySeq]);

  useEffect(() => {
    tradePageRequestSeq.current += 1;
    if (complexDetail?.complexId == null || areaState !== 'ready' || selectedExclArea == null) {
      tradeMoreController.current?.abort();
      tradeMoreController.current = null;
      setParcelTrades(null);
      setTradePage(0);
      setTradeRows([]);
      setTradeState(areaState === 'ready' ? 'ready' : 'idle');
      setTradeError(null);
      setTradeMoreState('idle');
      return undefined;
    }
    const requestSeq = tradePageRequestSeq.current;
    const controller = new AbortController();
    setTradeState('loading');
    setTradeError(null);
    const request = fetchComplexTrades(
      complexDetail.complexId,
      { exclArea: selectedExclArea },
      controller.signal,
    );
    request.then((nextTrades) => {
      if (controller.signal.aborted || requestSeq !== tradePageRequestSeq.current) return;
      setParcelTrades(nextTrades);
      setTradePage(nextTrades.page);
      setTradeRows(nextTrades.trades);
      setTradeState('ready');
      setTradeMoreState('idle');
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestSeq !== tradePageRequestSeq.current) return;
      const failure = toRequestFailure(error, {
        service: 'property-data',
        operation: 'complex-trades',
      }, controller.signal);
      if (isCancelledFailure(failure)) return;
      setTradeState('error');
      setTradeError(failure);
    });
    return () => controller.abort();
  }, [areaState, complexDetail?.complexId, selectedExclArea, tradeRetrySeq]);

  useEffect(() => {
    trendRequestSeq.current += 1;
    if (complexDetail?.complexId == null || areaState !== 'ready' || selectedExclArea == null) {
      setTradeTrend([]);
      setTrendState(areaState === 'ready' ? 'ready' : 'idle');
      setTrendError(null);
      return undefined;
    }
    const controller = new AbortController();
    const requestSeq = trendRequestSeq.current;
    setTrendState('loading');
    setTrendError(null);
    const request = fetchComplexTradeTrend(
      complexDetail.complexId,
      selectedExclArea,
      controller.signal,
    );
    request.then((nextTrend) => {
      if (controller.signal.aborted || requestSeq !== trendRequestSeq.current) return;
      setTradeTrend(nextTrend);
      setTrendState('ready');
    }).catch((error: unknown) => {
      if (controller.signal.aborted || requestSeq !== trendRequestSeq.current) return;
      const failure = toRequestFailure(error, {
        service: 'property-data',
        operation: 'trade-trend',
      }, controller.signal);
      if (isCancelledFailure(failure)) return;
      setTrendState('error');
      setTrendError(failure);
    });
    return () => controller.abort();
  }, [areaState, complexDetail?.complexId, selectedExclArea, trendRetrySeq]);

  useEffect(() => {
    if (complexDetail == null || detailState !== 'ready') {
      setParcelComplexes([]);
      return undefined;
    }

    const requestSeq = parcelComplexRequestSeq.current + 1;
    parcelComplexRequestSeq.current = requestSeq;
    const controller = new AbortController();

    fetchParcelComplexes(complexDetail.parcelId, controller.signal)
      .then((nextComplexes) => {
        if (!controller.signal.aborted && requestSeq === parcelComplexRequestSeq.current) {
          setParcelComplexes(nextComplexes);
        }
      })
      .catch(() => {
        if (!controller.signal.aborted && requestSeq === parcelComplexRequestSeq.current) {
          setParcelComplexes([]);
        }
      });

    return () => {
      controller.abort();
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
    tradeMoreController.current?.abort();
    tradeMoreController.current = null;
    setComplexDetail(null);
    setParcelTrades(null);
    setTradeAreas(null);
    setSelectedExclArea(null);
    setTradeTrend([]);
    setTradePage(0);
    setTradeRows([]);
    setParcelComplexes([]);
    setDetailError(null);
    setTradeError(null);
    setTradeMoreState('idle');
    setTrendError(null);
    setAreaError(null);
    setDetailState('loading');
    setTradeState('idle');
    setTrendState('idle');
    setAreaState('idle');
    setSelectedComplex(selection);
  }, []);

  const selectComplexMarker = useCallback((marker: ComplexMapMarker) => {
    selectComplex({ parcelId: marker.parcelId, complexId: marker.complexId });
  }, [selectComplex]);

  const selectComplexSummary = useCallback((complex: ParcelComplexSummary | RegionComplexSummary) => {
    selectComplex({ parcelId: complex.parcelId, complexId: complex.complexId });
  }, [selectComplex]);

  const closeDetail = useCallback(() => setSelectedComplex(null), []);
  const retryDetail = useCallback(() => {
    if (detailRequestPending.current) return;
    detailRequestPending.current = true;
    setDetailState('loading');
    setDetailRetrySeq((current) => current + 1);
  }, []);
  const retryTrades = useCallback(() => setTradeRetrySeq((current) => current + 1), []);
  const retryTrend = useCallback(() => setTrendRetrySeq((current) => current + 1), []);
  const retryTradeAreas = useCallback(() => setAreaRetrySeq((current) => current + 1), []);
  const selectExclArea = useCallback((exclArea: number) => {
    tradeMoreController.current?.abort();
    tradeMoreController.current = null;
    setParcelTrades(null);
    setTradeRows([]);
    setTradeTrend([]);
    setTradePage(0);
    setTradeMoreState('idle');
    setSelectedExclArea(exclArea);
  }, []);

  function loadMoreTrades() {
    if (
      complexDetail?.complexId == null
      || selectedExclArea == null
      || tradeMoreState === 'loading'
    ) {
      return;
    }

    tradeMoreController.current?.abort();
    const controller = new AbortController();
    tradeMoreController.current = controller;
    const nextPage = tradePage + 1;
    const requestSeq = tradePageRequestSeq.current + 1;
    tradePageRequestSeq.current = requestSeq;
    const request = fetchComplexTrades(
      complexDetail.complexId,
      { page: nextPage, size: TRADE_PAGE_SIZE, exclArea: selectedExclArea },
      controller.signal,
    );

    setTradeMoreState('loading');
    request
      .then((next) => {
        if (controller.signal.aborted || requestSeq !== tradePageRequestSeq.current) {
          return;
        }
        setTradePage(next.page);
        setTradeRows((current) => [...current, ...next.trades]);
        setTradeMoreState('idle');
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted || requestSeq !== tradePageRequestSeq.current) return;
        const failure = toRequestFailure(error, {
          service: 'property-data',
          operation: 'complex-trades-more',
        }, controller.signal);
        if (!isCancelledFailure(failure)) setTradeMoreState('error');
      })
      .finally(() => {
        if (tradeMoreController.current === controller) {
          tradeMoreController.current = null;
        }
      });
  }

  return {
    areaError,
    areaState,
    closeDetail,
    complexDetail,
    detailError,
    detailState,
    loadMoreTrades,
    parcelComplexes,
    parcelTrades,
    retryDetail,
    retryTrades,
    retryTrend,
    retryTradeAreas,
    selectComplex,
    selectComplexMarker,
    selectComplexSummary,
    selectedComplex,
    selectedExclArea,
    selectExclArea,
    tradeAreas,
    tradeRows,
    tradeError,
    tradeMoreState,
    tradeState,
    tradeTrend,
    trendError,
    trendState,
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
