import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { fetchMarketInsights } from './fetchMarketInsights';

describe('fetchMarketInsights 공개 API', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('주간 공개 endpoint의 UNAVAILABLE 빈 section을 정규화한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      snapshotId: null,
      periodStart: '2026-07-22',
      periodEnd: '2026-07-22',
      generatedAt: null,
      dataCutoff: null,
      dataStatus: 'UNAVAILABLE',
      scope: { type: 'NATIONWIDE', regionCode: null },
      quality: {
        missingRegistrationDateCount: 0,
        invalidRegistrationDateCount: 0,
        missingCancellationDateCount: 0,
        invalidCancellationDateCount: 0,
        excludedCount: 0,
      },
      newTrades: [],
      highestDeals: [],
      recordHighs: [],
      previousRises: [],
      previousFalls: [],
      cancellations: [],
    }), { status: 200, headers: { 'content-type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchMarketInsights();

    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/insights/trades/weekly?scope=NATIONWIDE&limit=10'),
      expect.objectContaining({ method: 'GET' }),
    );
    expect(result.dataStatus).toBe('UNAVAILABLE');
    expect(result.newTrades).toEqual([]);
  });

  it('두 자리 면적과 등록일 및 품질 근거를 보존한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      snapshotId: 'd0fb824c-938e-4cc8-a674-336262ef4206',
      periodStart: '2026-07-22',
      periodEnd: '2026-07-22',
      generatedAt: '2026-07-22T06:31:00Z',
      dataCutoff: '2026-07-22T06:30:00Z',
      dataStatus: 'FRESH',
      scope: { type: 'NATIONWIDE', regionCode: null },
      quality: {
        missingRegistrationDateCount: 1,
        invalidRegistrationDateCount: 2,
        missingCancellationDateCount: 3,
        invalidCancellationDateCount: 4,
        excludedCount: 7,
      },
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
        registrationDate: '2026-07-18',
        cancellationDate: null,
        canceledAt: null,
        tradeStatus: 'ACTIVE',
      }],
      highestDeals: [], recordHighs: [], previousRises: [], previousFalls: [], cancellations: [],
    }), { status: 200 })));

    const result = await fetchMarketInsights();

    expect(result.newTrades[0]).toMatchObject({
      exclArea: 84.99,
      dealDate: '2026-07-01',
      disclosedAt: '2026-07-22T03:14:15Z',
      registrationDate: '2026-07-18',
      cancellationDate: null,
      canceledAt: null,
    });
    expect(result.quality.excludedCount).toBe(7);
  });
});
