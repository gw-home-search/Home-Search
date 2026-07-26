import type { MarketNewsCategory } from './api/fetchMarketNews';

const CATEGORY_LABELS: Record<Exclude<MarketNewsCategory, 'ALL'>, string> = {
  POLICY: '정책',
  FINANCE_LOAN: '금융·대출',
  SUPPLY_SALE: '공급·분양',
  REDEVELOPMENT: '재건축·재개발',
  TRANSACTION_PRICE: '거래·가격',
  TRANSPORT_DEVELOPMENT: '교통·개발',
};

export function categoryLabel(category: Exclude<MarketNewsCategory, 'ALL'>): string {
  return CATEGORY_LABELS[category];
}
