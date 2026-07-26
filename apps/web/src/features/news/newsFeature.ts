export function isMarketNewsEnabled(value: string | undefined): boolean {
  return value === 'true';
}

export const MARKET_NEWS_ENABLED = isMarketNewsEnabled(import.meta.env.VITE_MARKET_NEWS_ENABLED);
