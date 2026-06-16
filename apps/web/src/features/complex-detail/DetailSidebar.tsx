import { useMemo, useState } from 'react';
import {
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { ComplexDetail } from './api/fetchComplexDetail';
import type { ParcelComplexSummary } from './api/fetchParcelComplexes';
import type { ParcelTrades, TradeItem } from './api/fetchParcelTrades';
import type { TradeTrendPoint } from './api/fetchTradeTrend';
import type { RegionComplexSummary } from '../region/api/fetchRegions';

type DetailRequestState = 'idle' | 'loading' | 'ready' | 'error';

type ComplexSelection = {
  parcelId: number | null;
  complexId: number | null;
};

type DetailSidebarProps = {
  complexDetail: ComplexDetail | null;
  detailError: string | null;
  detailState: DetailRequestState;
  onBack: () => void;
  onComplexSelect: (complex: ParcelComplexSummary | RegionComplexSummary) => void;
  onRetryDetail: () => void;
  onLoadMoreTrades: () => void;
  parcelComplexes: ParcelComplexSummary[];
  parcelTrades: ParcelTrades | null;
  tradeTrend: TradeTrendPoint[];
  tradeRows: TradeItem[];
  selection: ComplexSelection;
};

const TREND_LINE_FALLBACK = '#153847';

export function DetailSidebar({
  complexDetail,
  detailError,
  detailState,
  onBack,
  onComplexSelect,
  onRetryDetail,
  onLoadMoreTrades,
  parcelComplexes,
  parcelTrades,
  tradeTrend,
  tradeRows,
  selection,
}: DetailSidebarProps) {
  return (
    <section aria-label="단지 상세 패널" className="detail-sidebar" data-ui-layer="detail-sidebar">
      <div className="detail-drawer-header">
        <button
          type="button"
          aria-label="상세에서 뒤로가기"
          className="detail-back-button"
          onClick={onBack}
        >
          ←
        </button>
        <div>
          <p className="detail-drawer-kicker">{detailDrawerKicker(selection)}</p>
          <p className="detail-drawer-state">{detailRequestLabel(detailState)}</p>
        </div>
      </div>

      <DataStatusList
        ariaLabel="상세 API 데이터 요약"
        flow="detail"
        items={[
          ['상세', detailRequestLabel(detailState)],
          ['실거래', parcelTrades == null ? '대기' : `${parcelTrades.totalElements.toLocaleString()}건`],
          ['같은 필지', detailState === 'ready' ? `${parcelComplexes.length.toLocaleString()}개` : '대기'],
        ]}
      />

      {detailState === 'loading' ? (
        <p className="detail-message" role="status" aria-live="polite">
          상세 정보 불러오는 중
        </p>
      ) : null}

      {detailState === 'error' ? (
        <p className="detail-message detail-message-error" role="alert">
          상세 정보를 불러오지 못했습니다.
          {detailError ? ` ${detailError}` : null}
          {' '}
          <button type="button" aria-label="상세 정보 다시 불러오기" onClick={onRetryDetail}>
            다시 시도
          </button>
        </p>
      ) : null}

      {detailState === 'ready' && complexDetail ? (
        <>
          <section className="detail-identity" data-detail-section="identity">
            <h2>{complexDetail.name}</h2>
            <p className="detail-address">{formatAddress(complexDetail.address)}</p>
            <dl className="detail-key-stats">
              {detailMetric('최근 거래', latestTradeAmountLabel(parcelTrades?.trades ?? []))}
              {detailMetric('실거래', `${(parcelTrades?.totalElements ?? 0).toLocaleString()}건`)}
              {detailMetric('세대수', formatNumber(complexDetail.unitCnt, '세대'))}
            </dl>
            {parcelComplexes.length > 0 ? (
              <section aria-label="같은 필지 단지 선택" className="detail-complex-switcher">
                <div className="detail-section-heading">
                  <h3>같은 필지 단지</h3>
                  <span>{parcelComplexes.length.toLocaleString()}개</span>
                </div>
                <ul>
                  {parcelComplexes.map((complex) => (
                    <li key={complex.complexId}>
                      <button
                        type="button"
                        aria-label={`같은 필지 단지 선택 ${complex.complexName}`}
                        aria-current={complex.complexId === complexDetail.complexId ? 'true' : undefined}
                        onClick={() => {
                          onComplexSelect(complex);
                        }}
                      >
                        <span>{complex.complexName}</span>
                        <span>{complexSummaryMeta(complex)}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
            <dl className="detail-metrics">
              {detailMetric('거래명', complexDetail.tradeName)}
              {detailMetric('동수', formatNumber(complexDetail.dongCnt, '개동'))}
              {detailMetric('사용승인일', complexDetail.useDate)}
              {detailMetric('대지면적', formatNumber(complexDetail.platArea, '㎡'))}
              {detailMetric('건축면적', formatNumber(complexDetail.archArea, '㎡'))}
              {detailMetric('연면적', formatNumber(complexDetail.totArea, '㎡'))}
              {detailMetric('건폐율', formatNumber(complexDetail.bcRat, '%'))}
              {detailMetric('용적률', formatNumber(complexDetail.vlRat, '%'))}
            </dl>
          </section>
          <TradeTrendChart trend={tradeTrend} />
          <TradeList
            rows={tradeRows}
            totalElements={parcelTrades?.totalElements ?? 0}
            onLoadMore={onLoadMoreTrades}
          />
        </>
      ) : null}
    </section>
  );
}

function DataStatusList({
  ariaLabel,
  flow,
  items,
}: {
  ariaLabel: string;
  flow: string;
  items: Array<[string, string]>;
}) {
  return (
    <ul aria-label={ariaLabel} className="data-status-list" data-api-flow={flow}>
      {items.map(([label, value]) => (
        <li key={label}>
          <span>{label}</span>
          {' '}
          <strong>{value}</strong>
        </li>
      ))}
    </ul>
  );
}

function detailDrawerKicker(selection: ComplexSelection): string {
  if (selection.parcelId == null) {
    return `단지 ${selection.complexId}`;
  }

  return selection.complexId == null
    ? `필지 ${selection.parcelId}`
    : `단지 ${selection.complexId} / 필지 ${selection.parcelId}`;
}

function detailRequestLabel(state: DetailRequestState): string {
  switch (state) {
    case 'idle':
      return '대기';
    case 'loading':
      return '불러오는 중';
    case 'ready':
      return '완료';
    case 'error':
      return '오류';
  }
}

function formatAddress(address: string | null): string {
  return address ?? '주소 정보 없음';
}

function detailMetric(label: string, value: string | null) {
  if (value == null) {
    return null;
  }

  return (
    <div className="detail-metric" key={label}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

function latestTradeAmountLabel(trades: TradeItem[]): string {
  const latestTrade = trades.slice().sort(compareTradesNewestFirst)[0];
  return latestTrade == null ? '최근 거래 없음' : formatAmount(latestTrade.dealAmount);
}

function complexSummaryMeta(complex: ParcelComplexSummary | RegionComplexSummary): string {
  const values = [
    formatNumber(complex.unitCnt, '세대'),
    complex.useDate,
    formatAddress(complex.address),
  ].filter((value): value is string => value != null);

  return values.length === 0 ? '요약 정보 없음' : values.join(' · ');
}

function formatNumber(value: number | null, suffix: string): string | null {
  if (value == null) {
    return null;
  }

  return `${value.toLocaleString()}${suffix}`;
}

type TradeTrendRange = 'all' | '3y';

function TradeTrendChart({ trend }: { trend: TradeTrendPoint[] }) {
  const [range, setRange] = useState<TradeTrendRange>('all');
  const points = useMemo(() => filterTrendByRange(trend, range), [trend, range]);

  return (
    <section className="trade-chart" aria-label="거래가 차트" data-detail-section="trade-chart">
      <div className="trade-section-header">
        <h3>실거래가 흐름</h3>
        <div className="trade-range-toggle" role="group" aria-label="기간 선택">
          <button
            type="button"
            className="trade-range-button"
            aria-pressed={range === 'all'}
            onClick={() => setRange('all')}
          >
            전체
          </button>
          <button
            type="button"
            className="trade-range-button"
            aria-pressed={range === '3y'}
            onClick={() => setRange('3y')}
          >
            최근 3년
          </button>
        </div>
      </div>

      {points.length === 0 ? (
        <p className="trade-chart-empty">표시할 거래가 없습니다</p>
      ) : (
        <div className="trade-chart-canvas">
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={points} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              <XAxis
                dataKey="month"
                tickFormatter={formatTrendMonth}
                tick={{ fontSize: 10 }}
                tickMargin={6}
                minTickGap={24}
              />
              <YAxis tickFormatter={formatTrendAxis} tick={{ fontSize: 10 }} width={44} />
              <Tooltip content={<TrendTooltip />} />
              <Line
                type="monotone"
                dataKey="avgAmount"
                stroke={trendLineColor()}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 3 }}
              />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}

function TrendTooltip({
  active,
  payload,
}: {
  active?: boolean;
  payload?: Array<{ payload: TradeTrendPoint }>;
}) {
  if (!active || payload == null || payload.length === 0) {
    return null;
  }

  const point = payload[0].payload;
  return (
    <div className="trade-chart-tooltip">
      <span className="trade-chart-tooltip-month">{formatTrendMonth(point.month)}</span>
      <strong>{formatAmount(point.avgAmount)}</strong>
      <span className="trade-chart-tooltip-count">{point.count.toLocaleString()}건</span>
    </div>
  );
}

function filterTrendByRange(trend: TradeTrendPoint[], range: TradeTrendRange): TradeTrendPoint[] {
  if (range === 'all') {
    return trend;
  }

  const cutoff = new Date();
  cutoff.setFullYear(cutoff.getFullYear() - 3);
  const cutoffKey = `${cutoff.getFullYear()}-${String(cutoff.getMonth() + 1).padStart(2, '0')}`;
  return trend.filter((point) => point.month >= cutoffKey);
}

function formatTrendMonth(month: string): string {
  const [year, monthPart] = month.split('-');
  return year && monthPart ? `${year.slice(2)}-${monthPart}` : month;
}

function formatTrendAxis(value: number): string {
  return `${(value / 10000).toFixed(1)}억`;
}

function trendLineColor(): string {
  if (typeof window === 'undefined') {
    return TREND_LINE_FALLBACK;
  }

  const resolved = getComputedStyle(document.documentElement)
    .getPropertyValue('--hs-color-primary')
    .trim();
  return resolved.length > 0 ? resolved : TREND_LINE_FALLBACK;
}

function TradeList({
  rows,
  totalElements,
  onLoadMore,
}: {
  rows: TradeItem[];
  totalElements: number;
  onLoadMore: () => void;
}) {
  const hasMore = rows.length < totalElements;
  return (
    <section className="trade-list" aria-label="거래 목록" data-detail-section="trade-history">
      <div className="trade-section-header">
        <h3>거래 내역</h3>
        {totalElements > 0 ? (
          <p>{rows.length.toLocaleString()} / {totalElements.toLocaleString()}건</p>
        ) : null}
      </div>
      {rows.length === 0 ? (
        <p>거래 내역이 없습니다</p>
      ) : (
        <>
          <table>
            <caption className="sr-only">선택한 단지 또는 필지의 실거래 목록</caption>
            <thead>
              <tr>
                <th scope="col">일자</th>
                <th scope="col">금액</th>
                <th scope="col">면적</th>
                <th scope="col">층</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((trade) => (
                <tr key={trade.tradeId}>
                  <td>{trade.dealDate}</td>
                  <td data-trade-cell="amount">{formatAmount(trade.dealAmount)}</td>
                  <td data-trade-cell="area">{trade.exclArea.toLocaleString()}㎡</td>
                  <td data-trade-cell="floor">{formatTradeFloor(trade)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {hasMore ? (
            <button
              type="button"
              className="trade-load-more"
              aria-label="거래 더 보기"
              onClick={onLoadMore}
            >
              더보기
            </button>
          ) : null}
        </>
      )}
    </section>
  );
}

function formatAmount(amount: number | null): string {
  if (amount == null) {
    return '최근 거래 없음';
  }

  if (amount < 10000) {
    return `${amount.toLocaleString()}만원`;
  }

  const eok = Math.floor(amount / 10000);
  const man = amount % 10000;
  return man === 0
    ? `${eok.toLocaleString()}억`
    : `${eok.toLocaleString()}억 ${man.toLocaleString()}만원`;
}

function compareTradesNewestFirst(first: TradeItem, second: TradeItem): number {
  return second.dealDate.localeCompare(first.dealDate) || second.tradeId - first.tradeId;
}

function formatTradeFloor(trade: TradeItem): string {
  const floor = trade.floor == null ? '층 정보 없음' : `${trade.floor}층`;
  return trade.aptDong == null ? floor : `${trade.aptDong} / ${floor}`;
}
