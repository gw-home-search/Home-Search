import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { DetailSidebar } from './DetailSidebar';

describe('DetailSidebar 모바일 탭', () => {
  let root: Root | null = null;

  afterEach(() => {
    if (root) {
      act(() => root?.unmount());
      root = null;
    }
  });

  it('정보·시세·거래 tab은 aria-selected와 활성 section을 하나씩 전환한다', async () => {
    const host = document.createElement('div');
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <DetailSidebar
          complexDetail={{
            parcelId: 1001,
            complexId: 501,
            latitude: 37.5,
            longitude: 127,
            address: '서울시 테스트로',
            tradeName: '테스트아파트',
            name: '테스트아파트',
            dongCnt: 5,
            unitCnt: 740,
            platArea: null,
            archArea: null,
            totArea: null,
            bcRat: null,
            vlRat: null,
            useDate: '2018-01-01',
            prediction: null,
          }}
          detailError={null}
          detailState="ready"
          onBack={vi.fn()}
          onComplexSelect={vi.fn()}
          onRetryDetail={vi.fn()}
          onLoadMoreTrades={vi.fn()}
          parcelComplexes={[]}
          parcelTrades={{
            parcelId: 1001,
            complexId: 501,
            trades: [{
              tradeId: 1,
              dealDate: '2026-06-01',
              exclArea: 84.9,
              dealAmount: 125000,
              aptDong: '101',
              floor: 12,
            }],
            page: 0,
            size: 25,
            totalElements: 1,
            totalPages: 1,
          }}
          tradeTrend={[{
            month: '2026-06',
            avgAmount: 125000,
            count: 1,
            minAmount: 125000,
            maxAmount: 125000,
          }]}
          tradeRows={[{
            tradeId: 1,
            dealDate: '2026-06-01',
            exclArea: 84.9,
            dealAmount: 125000,
            aptDong: '101',
            floor: 12,
          }]}
          selection={{ parcelId: 1001, complexId: 501 }}
        />,
      );
    });

    const infoTab = host.querySelector<HTMLButtonElement>('button[role="tab"][aria-label="정보 보기"]');
    const tradeTab = host.querySelector<HTMLButtonElement>('button[role="tab"][aria-label="거래 보기"]');
    expect(tradeTab?.textContent).toBe('거래 1');
    expect(infoTab?.getAttribute('aria-selected')).toBe('true');
    expect(host.querySelectorAll('[data-mobile-tab-panel="info"][data-mobile-tab-active="true"]')).toHaveLength(2);

    act(() => tradeTab?.click());

    expect(tradeTab?.getAttribute('aria-selected')).toBe('true');
    expect(infoTab?.getAttribute('aria-selected')).toBe('false');
    expect(host.querySelectorAll('[data-mobile-tab-active="true"]')).toHaveLength(1);
    expect(host.querySelector('[data-mobile-tab-panel="trades"]')?.getAttribute('data-mobile-tab-active'))
      .toBe('true');

    const orderedSections = Array.from(host.querySelectorAll<HTMLElement>('[data-detail-order]'))
      .map((section) => section.dataset.detailOrder);
    expect(orderedSections).toEqual(['identity', 'summary', 'switcher', 'information', 'trend', 'trades']);
    expect(host.querySelector('.data-status-list')).toBeNull();
    expect(host.querySelectorAll('.detail-key-stats .detail-metric')).toHaveLength(2);
    expect(host.querySelector('.detail-key-stats')?.textContent).toContain('740세대 · 5개동 · 2018년');
    expect(host.querySelector('[data-detail-field="address"]')?.textContent).toContain('서울시 테스트로');
    expect(host.querySelector('[data-detail-field="unitCnt"]')?.textContent).toContain('740');
    expect(host.querySelector('details.detail-additional-information')?.hasAttribute('open')).toBe(false);
    expect(host.querySelector('details.detail-additional-information')?.textContent).toContain('면적');
    expect(host.querySelector('[data-trade-cell="area"]')?.textContent).toBe('84.9㎡25.7평');
    expect(host.querySelector('[data-trade-cell="amount"] .trade-amount-eok')?.textContent).toBe('12억');
    expect(host.querySelector('[data-trade-cell="amount"] .trade-amount-man')?.textContent).toBe('5,000만원');
    expect(host.querySelector('[data-trade-cell="floor"] .trade-building')?.textContent).toBe('101동');
    expect(host.querySelector('[data-trade-cell="floor"] .trade-floor')?.textContent).toBe('12층');
  });
});
