import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it } from 'vitest';

import { TradeTrendChart } from './TradeTrendChart';

describe('TradeTrendChart 접근 가능한 거래금액 요약', () => {
  let host: HTMLDivElement | null = null;

  afterEach(() => {
    host?.remove();
    host = null;
  });

  it('선택 전용면적과 월별 평균·최저·최고·건수를 text summary로 제공한다', () => {
    host = document.createElement('div');
    document.body.append(host);
    const root = createRoot(host);
    act(() => root.render(<TradeTrendChart selectedExclArea={84.94} trend={[{
      month: '2026-07', avgAmount: 88000, count: 3, minAmount: 85000, maxAmount: 91000,
    }]} />));

    expect(host.querySelector('section')?.getAttribute('aria-label')).toBe('84.94㎡ 거래가 차트');
    expect(host.querySelector('h3')?.textContent).toBe('84.94㎡ 실거래가 흐름');
    expect(host.textContent).toContain('선택 전용면적의 월별 평균 거래금액');
    expect(host.querySelector('caption')?.textContent).toBe('84.94㎡ 월별 실거래가 요약');
    expect(host.querySelector('tbody')?.textContent).toContain('2026-07');
    expect(host.querySelector('tbody')?.textContent).toContain('8억 8,000만원');
    expect(host.querySelector('tbody')?.textContent).toContain('8억 5,000만원');
    expect(host.querySelector('tbody')?.textContent).toContain('9억 1,000만원');
    expect(host.querySelector('tbody')?.textContent).toContain('3건');
    act(() => root.unmount());
  });
});
