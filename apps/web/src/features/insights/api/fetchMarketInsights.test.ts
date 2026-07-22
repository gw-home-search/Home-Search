import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchMarketInsights } from './fetchMarketInsights';

describe('fetchMarketInsights', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('normalizes UNAVAILABLE empty sections from the additive public endpoint', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      snapshotId: null,
      periodStart: '2026-07-22',
      periodEnd: '2026-07-22',
      generatedAt: null,
      dataCutoff: null,
      dataStatus: 'UNAVAILABLE',
      scope: { type: 'NATIONWIDE', regionCode: null },
      newTrades: [],
      highestDeals: [],
      recordHighs: [],
      previousRises: [],
      previousFalls: [],
      cancellations: [],
    }), { status: 200, headers: { 'content-type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchMarketInsights({ date: '2026-07-22' });

    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:8080/api/v1/insights/trades/latest?scope=NATIONWIDE&date=2026-07-22&limit=10',
      expect.objectContaining({ method: 'GET' }),
    );
    expect(result.dataStatus).toBe('UNAVAILABLE');
    expect(result.newTrades).toEqual([]);
  });

  it('preserves exact two-decimal area and disclosure/contract dates', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      snapshotId: 'd0fb824c-938e-4cc8-a674-336262ef4206',
      periodStart: '2026-07-22',
      periodEnd: '2026-07-22',
      generatedAt: '2026-07-22T06:31:00Z',
      dataCutoff: '2026-07-22T06:30:00Z',
      dataStatus: 'FRESH',
      scope: { type: 'NATIONWIDE', regionCode: null },
      newTrades: [{
        rank: 1,
        complexId: 501,
        parcelId: 1001,
        complexName: 'Sample Apartment',
        sidoName: '서울특별시',
        sigunguName: '강남구',
        exclArea: 84.99,
        dealAmount: 125000,
        dealDate: '2026-07-01',
        disclosedAt: '2026-07-22T03:14:15Z',
        tradeStatus: 'ACTIVE',
      }],
      highestDeals: [], recordHighs: [], previousRises: [], previousFalls: [], cancellations: [],
    }), { status: 200 })));

    const result = await fetchMarketInsights({ date: '2026-07-22' });

    expect(result.newTrades[0]).toMatchObject({
      exclArea: 84.99,
      dealDate: '2026-07-01',
      disclosedAt: '2026-07-22T03:14:15Z',
    });
  });
});
