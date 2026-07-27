import { afterEach, describe, expect, it, vi } from 'vitest';

import { resolveApiUrl } from '../../map/api/resolveApiUrl';
import { fetchTradeAreas } from './fetchTradeAreas';

describe('fetchTradeAreas API 어댑터', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('exact 전용면적 선택 계약을 canonical number로 파싱한다', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({
      complexId: 502,
      defaultExclArea: 84.94,
      areas: [
        { exclArea: 59.93, tradeCount: 8, latestDealDate: '2026-06-01' },
        { exclArea: 84.94, tradeCount: 12, latestDealDate: '2026-07-16' },
      ],
    }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(fetchTradeAreas(502)).resolves.toEqual({
      complexId: 502,
      defaultExclArea: 84.94,
      areas: [
        { exclArea: 59.93, tradeCount: 8, latestDealDate: '2026-06-01' },
        { exclArea: 84.94, tradeCount: 12, latestDealDate: '2026-07-16' },
      ],
    });
    expect(fetchMock).toHaveBeenCalledWith(
      resolveApiUrl('/api/v1/complex/502/trade-areas'),
      expect.objectContaining({ method: 'GET' }),
    );
  });

  it('유효 무거래 단지는 null default와 empty areas를 유지한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      complexId: 502,
      defaultExclArea: null,
      areas: [],
    })));

    await expect(fetchTradeAreas(502)).resolves.toEqual({
      complexId: 502,
      defaultExclArea: null,
      areas: [],
    });
  });

  it('문자열 숫자와 default에 없는 면적을 invalid response로 거부한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      complexId: 502,
      defaultExclArea: 84.94,
      areas: [{ exclArea: '84.94', tradeCount: 1, latestDealDate: '2026-07-16' }],
    })));

    await expect(fetchTradeAreas(502)).rejects.toMatchObject({
      failure: { kind: 'invalid-response', operation: 'trade-areas' },
    });
  });

  it('lookup 실패를 ProblemDetail 원문 없이 구조화한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(404, { detail: 'secret detail' })));

    await expect(fetchTradeAreas(404)).rejects.toMatchObject({
      failure: { kind: 'not-found', operation: 'trade-areas', status: 404 },
    });
  });
});

function jsonResponse(body: unknown): Response {
  return { ok: true, status: 200, json: () => Promise.resolve(body) } as Response;
}

function errorResponse(status: number, body: unknown): Response {
  return { ok: false, status, json: () => Promise.resolve(body) } as Response;
}
