import { afterEach, describe, expect, it, vi } from 'vitest';

import { fetchComplexNews, fetchMarketNews } from './fetchMarketNews';

describe('뉴스 API adapter', () => {
  afterEach(() => vi.restoreAllMocks());

  it('scope/category/cursor를 보존하고 공개 필드만 정규화한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      snapshotId: 'd0fb824c-938e-4cc8-a674-336262ef4206',
      generatedAt: '2026-07-24T06:31:00Z',
      dataCutoff: '2026-07-24T06:30:00Z',
      dataStatus: 'FRESH',
      scope: { type: 'SIDO', regionCode: '11' },
      category: 'POLICY',
      items: [{
        articleId: 31,
        category: 'POLICY',
        title: '서울 아파트 정책 발표',
        providedAt: '2026-07-24T06:00:00Z',
        url: 'https://news.example.test/article/31',
        region: { code: '11', name: '서울특별시' },
      }],
      nextCursor: 'next',
    }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);

    const result = await fetchMarketNews({
      scope: 'SIDO',
      regionCode: '11',
      category: 'POLICY',
      cursor: 'opaque',
      limit: 20,
    });

    expect(String(fetchMock.mock.calls[0][0])).toContain(
      '/api/v1/insights/news?scope=SIDO&regionCode=11&category=POLICY&cursor=opaque&limit=20',
    );
    expect(result.items[0].title).toBe('서울 아파트 정책 발표');
  });

  it('단지 뉴스 endpoint는 최대 다섯 건과 relationType만 받는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify([{
      articleId: 31,
      category: 'TRANSACTION_PRICE',
      title: '래미안 테스트 거래 증가',
      providedAt: '2026-07-24T06:00:00Z',
      url: 'https://news.example.test/article/31',
      region: { code: '11', name: '서울특별시' },
      relationType: 'DIRECT_COMPLEX',
    }]), { status: 200 })));

    const result = await fetchComplexNews(501);

    expect(result).toHaveLength(1);
    expect(result[0].relationType).toBe('DIRECT_COMPLEX');
  });
});
