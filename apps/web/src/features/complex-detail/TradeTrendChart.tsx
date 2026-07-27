import { useMemo, useState } from 'react';
import {
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';

import type { TradeTrendPoint } from './api/fetchTradeTrend';
import { formatAmount, formatEokAxis, formatExactArea } from './formatDetailValue';

type TradeTrendRange = 'all' | '3y';
const TREND_LINE_FALLBACK = '#0e7490';

export function TradeTrendChart({
  trend,
  selectedExclArea = null,
}: {
  trend: TradeTrendPoint[];
  selectedExclArea?: number | null;
}) {
  const [range, setRange] = useState<TradeTrendRange>('all');
  const points = useMemo(() => filterTrendByRange(trend, range), [trend, range]);

  return (
    <section
      className="trade-chart"
      aria-label={selectedExclArea == null ? '거래가 차트' : `${formatExactArea(selectedExclArea)} 거래가 차트`}
      data-detail-order="trend"
      data-detail-section="trade-chart"
    >
      <div className="trade-section-header">
        <div>
          <h3>{selectedExclArea == null ? '실거래가 흐름' : `${formatExactArea(selectedExclArea)} 실거래가 흐름`}</h3>
          <p className="trade-chart-description">선택 전용면적의 월별 평균 거래금액</p>
        </div>
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
          <ResponsiveContainer width="100%" height="100%" minWidth={0}>
            <LineChart data={points} margin={{ top: 8, right: 12, bottom: 0, left: 0 }}>
              <XAxis
                dataKey="month"
                tickFormatter={formatTrendMonth}
                tick={{ fontSize: 10 }}
                tickMargin={6}
                minTickGap={24}
              />
              <YAxis tickFormatter={formatEokAxis} tick={{ fontSize: 10 }} width={44} />
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
      <table className="sr-only">
        <caption>{selectedExclArea == null ? '월별 실거래가 요약' : `${formatExactArea(selectedExclArea)} 월별 실거래가 요약`}</caption>
        <thead><tr><th>월</th><th>평균</th><th>최저</th><th>최고</th><th>거래 건수</th></tr></thead>
        <tbody>{points.map((point) => <tr key={point.month}>
          <td>{point.month}</td><td>{formatAmount(point.avgAmount)}</td>
          <td>{formatAmount(point.minAmount)}</td><td>{formatAmount(point.maxAmount)}</td>
          <td>{point.count.toLocaleString('ko-KR')}건</td>
        </tr>)}</tbody>
      </table>
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
      <strong>평균 {formatAmount(point.avgAmount)}</strong>
      <span>최저 {formatAmount(point.minAmount)} ~ 최고 {formatAmount(point.maxAmount)}</span>
      <span className="trade-chart-tooltip-count">거래 {point.count.toLocaleString('ko-KR')}건</span>
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

function trendLineColor(): string {
  if (typeof window === 'undefined') {
    return TREND_LINE_FALLBACK;
  }
  const surface = document.querySelector<HTMLElement>('[data-ui-surface="map-first"]')
    ?? document.documentElement;
  const resolved = getComputedStyle(surface)
    .getPropertyValue('--hs-map-color-trend')
    .trim();
  return resolved.length > 0 ? resolved : TREND_LINE_FALLBACK;
}
