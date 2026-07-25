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

  it('뉴스 flag가 꺼져도 기존 시세 요약은 유지하고 뉴스 tab과 section만 숨긴다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
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
            displayName: '테스트아파트',
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
          newsEnabled={false}
          onBack={vi.fn()}
          onComplexSelect={vi.fn()}
          onRetryDetail={vi.fn()}
          onLoadMoreTrades={vi.fn()}
          parcelComplexes={[]}
          parcelTrades={null}
          selection={{ parcelId: 1001, complexId: 501 }}
          tradeRows={[]}
          tradeTrend={[]}
        />,
      );
    });

    expect(host.querySelector('.detail-price-overview')).not.toBeNull();
    expect(host.querySelector('#detail-tab-news')).toBeNull();
    expect(host.querySelector('#detail-tabpanel-news')).toBeNull();
    host.remove();
  });

  it('정보·시세·거래 tab은 aria-selected와 활성 section을 하나씩 전환한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
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
            displayName: '응봉동 테스트아파트',
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
    expect(infoTab?.id).toBe('detail-tab-info');
    expect(infoTab?.getAttribute('aria-controls')).toBe('detail-tabpanel-info');
    expect(infoTab?.tabIndex).toBe(0);
    expect(host.querySelector('#detail-tabpanel-info')?.getAttribute('role')).toBe('tabpanel');
    expect(host.querySelector('#detail-tabpanel-info')?.getAttribute('aria-labelledby')).toBe('detail-tab-info');
    expect(host.querySelectorAll('[data-mobile-tab-panel="info"][data-mobile-tab-active="true"]')).toHaveLength(2);

    act(() => infoTab?.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true })));
    const trendTab = host.querySelector<HTMLButtonElement>('#detail-tab-trend');
    expect(trendTab?.getAttribute('aria-selected')).toBe('true');
    expect(trendTab?.tabIndex).toBe(0);
    expect(document.activeElement).toBe(trendTab);

    act(() => trendTab?.dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true })));
    const newsTab = host.querySelector<HTMLButtonElement>('#detail-tab-news');
    expect(document.activeElement).toBe(newsTab);

    act(() => tradeTab?.click());

    expect(tradeTab?.getAttribute('aria-selected')).toBe('true');
    expect(infoTab?.getAttribute('aria-selected')).toBe('false');
    expect(host.querySelectorAll('[data-mobile-tab-active="true"]')).toHaveLength(1);
    expect(host.querySelector('[data-mobile-tab-panel="trades"]')?.getAttribute('data-mobile-tab-active'))
      .toBe('true');

    const orderedSections = Array.from(host.querySelectorAll<HTMLElement>('[data-detail-order]'))
      .map((section) => section.dataset.detailOrder);
    expect(orderedSections).toEqual(['identity', 'summary', 'switcher', 'information', 'news', 'trend', 'trades']);
    expect(host.querySelector('.data-status-list')).toBeNull();
    expect(host.querySelectorAll('.detail-key-stats .detail-metric')).toHaveLength(2);
    expect(host.querySelector('.detail-key-stats')?.textContent).toContain('740세대 · 5개동 · 2018년');
    expect(host.querySelector('.detail-drawer-identity h2')?.textContent).toBe('응봉동 테스트아파트');
    expect(host.querySelector('[data-detail-field="address"]')?.textContent).toContain('서울시 테스트로');
    expect(host.querySelector('[data-detail-field="unitCnt"]')?.textContent).toContain('740');
    expect(host.querySelector('details.detail-additional-information')?.hasAttribute('open')).toBe(false);
    expect(host.querySelector('details.detail-additional-information')?.textContent).toContain('면적');
    expect(host.querySelector('details.detail-additional-information')?.textContent).toContain('단지명테스트아파트');
    expect(host.querySelector('[data-trade-cell="area"]')?.textContent).toBe('84.9㎡25.7평');
    expect(host.querySelector('[data-trade-cell="amount"] .trade-amount-label')?.textContent)
      .toBe('12억 5,000만원');
    expect(host.querySelector('[data-trade-cell="amount"]')?.children).toHaveLength(1);
    expect(host.querySelector('[data-trade-cell="floor"] .trade-building')?.textContent).toBe('101동');
    expect(host.querySelector('[data-trade-cell="floor"] .trade-floor')?.textContent).toBe('12층');
    host.remove();
  });

  it('상세 500의 서버 원문과 상태를 노출하지 않고 복구 action만 제공한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
    root = createRoot(host);

    await act(async () => {
      root?.render(
        <DetailSidebar
          complexDetail={null}
          detailError={{
            kind: 'service-unavailable',
            service: 'property-data',
            operation: 'complex-detail',
            status: 500,
            code: 'C500',
          }}
          detailState="error"
          onBack={vi.fn()}
          onComplexSelect={vi.fn()}
          onLoadMoreTrades={vi.fn()}
          onRetryDetail={vi.fn()}
          parcelComplexes={[]}
          parcelTrades={null}
          selection={{ parcelId: 1001, complexId: 501 }}
          tradeRows={[]}
          tradeTrend={[]}
        />,
      );
    });

    expect(host.textContent).toContain('단지 정보를 불러오지 못했어요');
    expect(host.textContent).toContain('단지 다시 불러오기');
    expect(host.textContent).not.toContain('HTTP 500');
    expect(host.textContent).not.toContain('Internal server error');
    expect(host.textContent).not.toContain('오류 정보');
    expect(host.textContent).not.toContain('상세 API 데이터 요약');
    expect(host.textContent).toContain('선택한 단지');
    expect(host.textContent).not.toContain('단지 501');
    expect(host.querySelector('[aria-label="로그인하고 관심 단지 저장"]')).toBeNull();
    expect(host.querySelector('.request-state-notice')?.classList).toContain('detail-request-state');
    expect(host.querySelector('.data-status-list')).toBeNull();
    expect(host.querySelector('details')).toBeNull();
    host.remove();
  });

  it('추가 거래 실패 시 기존 거래를 유지하고 해당 목록에서만 복구 action을 제공한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
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
            displayName: '테스트아파트',
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
            trades: [],
            page: 0,
            size: 25,
            totalElements: 40,
            totalPages: 2,
          }}
          tradeRows={[{
            tradeId: 1,
            dealDate: '2026-06-01',
            exclArea: 84.9,
            dealAmount: 125000,
            aptDong: '101',
            floor: 12,
          }]}
          tradeTrend={[]}
          tradeMoreState="error"
          selection={{ parcelId: 1001, complexId: 501 }}
        />,
      );
    });

    expect(host.textContent).toContain('12억 5,000만원');
    expect(host.textContent).toContain('거래를 더 불러오지 못했어요');
    expect(host.textContent).toContain('거래 이어서 불러오기');
    expect(host.textContent).not.toContain('Internal server error');
    host.remove();
  });

  it('AI 예상가 실패의 서버 message를 노출하지 않고 검토된 문구만 표시한다', async () => {
    const host = document.createElement('div');
    document.body.append(host);
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
            displayName: '테스트아파트',
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
            prediction: {
              status: 'FAILED',
              modelVersion: null,
              predictedDealAmount: null,
              predictedPricePerM2: null,
              predictedPricePerPyeong: null,
              intervalLow: null,
              intervalHigh: null,
              intervalBasis: null,
              targetAreaM2: null,
              targetFloor: null,
              basisTradeId: null,
              basisDealDate: null,
              generatedAt: null,
            },
          }}
          detailError={null}
          detailState="ready"
          onBack={vi.fn()}
          onComplexSelect={vi.fn()}
          onRetryDetail={vi.fn()}
          onLoadMoreTrades={vi.fn()}
          parcelComplexes={[]}
          parcelTrades={null}
          selection={{ parcelId: 1001, complexId: 501 }}
          tradeRows={[]}
          tradeTrend={[]}
        />,
      );
    });

    expect(host.textContent).toContain('AI 예상가를 준비하지 못했어요');
    expect(host.textContent).toContain('최근 거래와 가격 흐름은 계속 확인할 수 있어요');
    expect(host.textContent).not.toContain('Internal server error');
    expect(host.textContent).not.toContain('model-worker-03');
    host.remove();
  });
});
