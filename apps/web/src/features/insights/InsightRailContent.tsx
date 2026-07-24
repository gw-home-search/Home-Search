import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';

import type { ComplexSelection } from '../../app/mapAppTypes';
import { ChevronRightIcon, HelpIcon } from '../../shared/icons';
import { RegionChoiceGrid } from '../exploration/RegionChoiceGrid';
import { fetchRegionDetail, fetchRootRegions, type RegionSummary } from '../region/api/fetchRegions';
import type { InsightScopeType, InsightTradeItem } from './api/fetchMarketInsights';
import { insightMetricConfig, readInsightMetric, type InsightMetric } from './insightMetricConfig';
import { useMarketInsights } from './hooks/useMarketInsights';

const NATIONWIDE_REGION: RegionSummary = { id: 0, code: '', name: '전국' };

export function InsightRailContent({
  active,
  detailOpen,
  onFocusNationwide,
  onFocusRegion,
  onSelectComplex,
}: {
  active: boolean;
  detailOpen: boolean;
  onFocusNationwide: () => void;
  onFocusRegion: (latitude: number, longitude: number) => void;
  onSelectComplex: (selection: ComplexSelection) => void;
}) {
  const location = useLocation();
  const navigate = useNavigate();
  const params = new URLSearchParams(location.search);
  const metric = readInsightMetric(location.search);
  const scope: InsightScopeType = params.get('scope') === 'NATIONWIDE' ? 'NATIONWIDE' : 'SIDO';
  const regionCode = scope === 'SIDO' ? params.get('regionCode') ?? '11' : null;
  const { data, state, retry } = useMarketInsights({ active, regionCode, scope });
  const [regions, setRegions] = useState<RegionSummary[]>([]);
  const [chooserOpen, setChooserOpen] = useState(false);
  const [liveMessage, setLiveMessage] = useState('');
  const focusRequestRef = useRef<AbortController | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const changeRegionRef = useRef<HTMLButtonElement>(null);
  const returnFocusRef = useRef<HTMLButtonElement | null>(null);
  const returnScrollRef = useRef(0);
  const previousDetailOpenRef = useRef(detailOpen);
  const listIdentity = `${metric}:${scope}:${regionCode ?? ''}`;
  const previousListIdentityRef = useRef(listIdentity);

  useEffect(() => {
    if (!active || regions.length > 0) return undefined;
    let canceled = false;
    fetchRootRegions().then((next) => {
      if (!canceled) setRegions(next);
    }).catch(() => {
      if (!canceled) setRegions([]);
    });
    return () => { canceled = true; };
  }, [active, regions.length]);

  useEffect(() => {
    const wasOpen = previousDetailOpenRef.current;
    previousDetailOpenRef.current = detailOpen;
    if (!wasOpen || detailOpen || !active) return;
    queueMicrotask(() => {
      if (listRef.current) listRef.current.scrollTop = returnScrollRef.current;
      if (returnFocusRef.current?.isConnected) returnFocusRef.current.focus();
    });
  }, [active, detailOpen]);

  useEffect(() => {
    if (previousListIdentityRef.current === listIdentity) return;
    previousListIdentityRef.current = listIdentity;
    returnFocusRef.current = null;
    returnScrollRef.current = 0;
    if (listRef.current) listRef.current.scrollTop = 0;
  }, [listIdentity]);

  useEffect(() => () => focusRequestRef.current?.abort(), []);

  const config = insightMetricConfig(metric);
  const items = data?.dataStatus === 'UNAVAILABLE' ? [] : data?.[config.section] ?? [];
  const currentRegionName = scope === 'NATIONWIDE'
    ? '전국'
    : shortRegionName(regions.find((region) => region.code === regionCode)?.name ?? '서울특별시');

  function closeChooser() {
    setChooserOpen(false);
    queueMicrotask(() => changeRegionRef.current?.focus());
  }

  function selectRegion(region: RegionSummary) {
    focusRequestRef.current?.abort();
    const nextParams = new URLSearchParams();
    nextParams.set('metric', metric);
    if (region.id === 0) {
      nextParams.set('scope', 'NATIONWIDE');
      onFocusNationwide();
    } else {
      nextParams.set('scope', 'SIDO');
      nextParams.set('regionCode', region.code);
      const controller = new AbortController();
      focusRequestRef.current = controller;
      fetchRegionDetail(region.id, controller.signal)
        .then((detail) => {
          if (!controller.signal.aborted) onFocusRegion(detail.latitude, detail.longitude);
        })
        .catch(() => undefined);
    }
    navigate(`/insights?${nextParams.toString()}`);
    setChooserOpen(false);
    setLiveMessage(`${shortRegionName(region.name)} 지역으로 변경했어요`);
    queueMicrotask(() => changeRegionRef.current?.focus());
  }

  function selectItem(item: InsightTradeItem, trigger: HTMLButtonElement) {
    returnFocusRef.current = trigger;
    returnScrollRef.current = listRef.current?.scrollTop ?? 0;
    onSelectComplex({ parcelId: item.parcelId, complexId: item.complexId });
  }

  return (
    <section
      aria-label="거래 인사이트"
      className="insight-rail-content"
      data-insight-state={data?.dataStatus ?? state}
      hidden={!active}
      onKeyDown={(event) => {
        if (event.key === 'Escape' && chooserOpen) {
          event.preventDefault();
          closeChooser();
        }
      }}
    >
      <header className="insight-context-header">
        <div className="insight-context-region">
          <strong>{currentRegionName}</strong>
          <button
            aria-controls="insight-region-chooser"
            aria-expanded={chooserOpen}
            aria-label="인사이트 지역 변경"
            ref={changeRegionRef}
            type="button"
            onClick={() => setChooserOpen((current) => !current)}
          >
            지역 변경 <ChevronRightIcon aria-hidden="true" />
          </button>
        </div>
        <div className="insight-context-period">
          <p><strong>최근 7일</strong> · {data ? `${formatDate(data.periodStart)}–${formatDate(data.periodEnd)}` : '기간 확인 중'}</p>
          <span>{state === 'ready' && data?.dataStatus !== 'UNAVAILABLE' ? `${items.length.toLocaleString()}건` : ''}</span>
        </div>
        <p className="insight-generated-at">
          {data?.generatedAt ? `${formatInstant(data.generatedAt)} 집계` : '집계 시각 확인 중'}
          {data?.dataStatus === 'STALE' ? <span>최신 집계 확인 중</span> : null}
        </p>
      </header>

      <details className="insight-basis-details">
        <summary>
          <HelpIcon aria-hidden="true" />
          <span>
            <strong>최근 계약 · 직전 거래 비교</strong>
            <small>순위와 비교에 사용한 조건을 확인할 수 있어요</small>
          </span>
          <ChevronRightIcon aria-hidden="true" />
        </summary>
        <dl>
          <div><dt>현재 거래</dt><dd>집계 종료일 기준 최근 1개월 이내 계약</dd></div>
          <div><dt>직전 비교</dt><dd>같은 단지·같은 전용면적의 6개월 이내 직전 계약</dd></div>
          <div><dt>신고가 기준</dt><dd>최근 직전 거래가 있는 거래 중 과거 최고가 갱신</dd></div>
          <div><dt>등록일</dt><dd>국토부 실거래가 자료에 거래가 등록된 날짜</dd></div>
          <div><dt>계약일</dt><dd>실제 매매 계약이 체결된 날짜</dd></div>
        </dl>
        <p>최근 7일은 등록일을 우선하고 등록일이 없는 정상 거래는 계약일로 판단합니다.</p>
      </details>

      <p aria-live="polite" className="sr-only">{liveMessage}</p>

      {chooserOpen ? (
        <div className="insight-region-chooser" id="insight-region-chooser">
          <RegionChoiceGrid
            ariaLabel="인사이트 지역 선택"
            onSelect={selectRegion}
            regions={[NATIONWIDE_REGION, ...regions]}
            selectedCode={regionCode ?? ''}
            selectionLabel="인사이트 지역 선택"
          />
        </div>
      ) : (
        <div className="insight-trade-list" ref={listRef}>
          <div className="insight-list-heading"><strong>{config.title}</strong><span>{config.sortLabel}</span></div>
          {state === 'loading' ? <InsightLoadingRows /> : null}
          {state === 'error' ? (
            <div className="insight-state-copy" role="alert">
              <p>거래 정보를 불러오지 못했어요</p>
              <button type="button" onClick={retry}>다시 시도</button>
            </div>
          ) : null}
          {state === 'ready' && data?.dataStatus === 'UNAVAILABLE' ? <p className="insight-state-copy">집계 준비 중</p> : null}
          {state === 'ready' && data?.dataStatus !== 'UNAVAILABLE' && items.length === 0 ? (
            <div className="insight-state-copy">
              <p>{config.emptyMessage}</p>
              <button type="button" onClick={() => setChooserOpen(true)}>지역 변경</button>
            </div>
          ) : null}
          {items.length > 0 ? (
            <ol aria-label={`${config.label} 거래`}>
              {items.map((item) => (
                <li key={`${item.rank}:${item.parcelId}:${item.complexId}`}>
                  <InsightTradeRow item={item} metric={metric} onSelect={(trigger) => selectItem(item, trigger)} />
                </li>
              ))}
            </ol>
          ) : null}
        </div>
      )}
    </section>
  );
}

function InsightLoadingRows() {
  return <div aria-label="거래 정보를 불러오는 중" className="insight-loading-rows" role="status">{[1, 2, 3].map((row) => <span key={row} />)}</div>;
}

function InsightTradeRow({ item, metric, onSelect }: {
  item: InsightTradeItem;
  metric: InsightMetric;
  onSelect: (trigger: HTMLButtonElement) => void;
}) {
  return (
    <button aria-label={`${item.rank}위 ${item.complexName} 상세 보기`} className="insight-trade-row" type="button" onClick={(event) => onSelect(event.currentTarget)}>
      <span className="insight-rank">{String(item.rank).padStart(2, '0')}</span>
      <span className="insight-trade-main">
        <span className="insight-trade-title-line">
          <span className="insight-complex-name">{item.complexName}</span>
          {['record-high', 'rise', 'fall'].includes(metric) ? <TrendDelta item={item} metric={metric} /> : <strong className="insight-primary-amount">{formatAmount(item.dealAmount)}</strong>}
        </span>
        <span className="insight-trade-context">{[item.sidoName, item.sigunguName].filter(Boolean).join(' ')} · {item.exclArea.toFixed(2)}㎡</span>
        {['record-high', 'rise', 'fall'].includes(metric) && item.previousAmount != null ? (
          <span className="insight-comparison">
            {metric === 'record-high' ? '이전 최고' : '직전'}
            {item.previousDealDate ? `(${formatDate(item.previousDealDate)})` : ''}{' '}
            {formatAmount(item.previousAmount)} → {formatAmount(item.dealAmount)}
            {item.deltaAmount != null ? <small>금액차 {signedAmount(item.deltaAmount)}</small> : null}
          </span>
        ) : null}
        <span className="insight-trade-dates">
          계약 {formatDate(item.dealDate)} · {item.registrationDate
            ? `등록 ${formatDate(item.registrationDate)}`
            : '등록일 미제공 · 계약일 기준'}
        </span>
      </span>
    </button>
  );
}

function TrendDelta({ item, metric }: { item: InsightTradeItem; metric: InsightMetric }) {
  if (item.deltaRate == null) return null;
  const direction = metric === 'fall' ? '↘' : metric === 'record-high' ? '⚑' : '↗';
  const label = metric === 'record-high' ? '신고가' : metric === 'fall' ? '하락' : '상승';
  const sign = item.deltaRate > 0 ? '+' : '';
  return <span className="trend-delta" data-trend={metric}>{direction} {label} <strong>{sign}{item.deltaRate.toFixed(1)}%</strong></span>;
}

function formatAmount(amount: number): string {
  const absolute = Math.abs(amount);
  const eok = Math.floor(absolute / 10_000);
  const manwon = absolute % 10_000;
  if (eok === 0) return `${manwon.toLocaleString()}만원`;
  if (manwon === 0) return `${eok.toLocaleString()}억`;
  return `${eok.toLocaleString()}억 ${manwon.toLocaleString()}만원`;
}

function signedAmount(amount: number): string {
  return `${amount > 0 ? '+' : amount < 0 ? '-' : ''}${formatAmount(Math.abs(amount))}`;
}

function formatDate(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(value);
  return match ? `${Number(match[2])}.${match[3]}` : value;
}

function formatInstant(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts = new Intl.DateTimeFormat('ko-KR', { day: '2-digit', hour: '2-digit', hour12: false, minute: '2-digit', month: 'numeric', timeZone: 'Asia/Seoul' }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? '';
  return `${part('month')}.${part('day')} ${part('hour')}:${part('minute')}`;
}

function shortRegionName(name: string): string {
  return name.replace(/특별자치도|특별자치시|특별시|광역시|도$/, '');
}
