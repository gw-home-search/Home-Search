import { vi } from 'vitest';

vi.stubEnv('VITE_MARKET_NEWS_ENABLED', 'true');

Object.defineProperty(window, 'localStorage', {
  configurable: true,
  value: window.sessionStorage,
});

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
  .IS_REACT_ACT_ENVIRONMENT = true;
