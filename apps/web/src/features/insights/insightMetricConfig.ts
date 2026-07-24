import type { ComponentType, SVGProps } from 'react';


import {
  FallIcon,
  HighestDealIcon,
  MapGridIcon,
  NewTradeIcon,
  RecordHighIcon,
  RiseIcon,
} from '../../shared/icons';
import type { MarketInsights } from './api/fetchMarketInsights';

export type InsightMetric = 'new' | 'highest' | 'record-high' | 'rise' | 'fall';
export type InsightSectionKey = keyof Pick<
  MarketInsights,
  'newTrades' | 'highestDeals' | 'recordHighs' | 'previousRises' | 'previousFalls'
>;

type MetricConfig = {
  metric: InsightMetric;
  label: string;
  icon: ComponentType<SVGProps<SVGSVGElement>>;
  section: InsightSectionKey;
  emptyMessage: string;
  sortLabel: string;
  title: string;
};

export const REGION_MODE = {
  label: '지역',
  icon: MapGridIcon,
} as const;

export const INSIGHT_METRICS: readonly MetricConfig[] = [
  { metric: 'new', label: '신규', icon: NewTradeIcon, section: 'newTrades', title: '최근 7일 신규 거래', sortLabel: '기준일 최신순', emptyMessage: '최근 7일 거래가 없습니다' },
  { metric: 'highest', label: '최고가', icon: HighestDealIcon, section: 'highestDeals', title: '최근 7일 최고가 거래', sortLabel: '거래 금액순', emptyMessage: '표시할 최고가 거래가 없습니다' },
  { metric: 'record-high', label: '신고가', icon: RecordHighIcon, section: 'recordHighs', title: '최근 7일 신고가', sortLabel: '상승 금액순', emptyMessage: '최근 7일 신고가가 없습니다' },
  { metric: 'rise', label: '상승', icon: RiseIcon, section: 'previousRises', title: '최근 7일 직전 거래 대비 상승', sortLabel: '상승률순', emptyMessage: '비교할 수 있는 거래가 없습니다' },
  { metric: 'fall', label: '하락', icon: FallIcon, section: 'previousFalls', title: '최근 7일 직전 거래 대비 하락', sortLabel: '하락률순', emptyMessage: '비교할 수 있는 거래가 없습니다' },
] as const;

export function readInsightMetric(search: string): InsightMetric {
  const metric = new URLSearchParams(search).get('metric');
  return INSIGHT_METRICS.some((config) => config.metric === metric)
    ? metric as InsightMetric
    : 'new';
}

export function insightMetricConfig(metric: InsightMetric): MetricConfig {
  return INSIGHT_METRICS.find((config) => config.metric === metric) ?? INSIGHT_METRICS[0];
}
