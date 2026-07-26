import { describe, expect, it } from 'vitest';

import { isMarketNewsEnabled } from './newsFeature';

describe('시장 뉴스 feature flag', () => {
  it('명시적인 true에서만 뉴스를 활성화한다', () => {
    expect(isMarketNewsEnabled('true')).toBe(true);
    expect(isMarketNewsEnabled(undefined)).toBe(false);
    expect(isMarketNewsEnabled('false')).toBe(false);
    expect(isMarketNewsEnabled('TRUE')).toBe(false);
    expect(isMarketNewsEnabled('1')).toBe(false);
  });
});
