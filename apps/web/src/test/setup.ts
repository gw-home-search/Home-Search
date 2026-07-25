import { vi } from 'vitest';

vi.stubEnv('VITE_MARKET_NEWS_ENABLED', 'true');

(globalThis as typeof globalThis & { IS_REACT_ACT_ENVIRONMENT: boolean })
  .IS_REACT_ACT_ENVIRONMENT = true;
